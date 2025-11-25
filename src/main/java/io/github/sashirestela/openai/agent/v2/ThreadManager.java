package io.github.sashirestela.openai.agent.v2;

import io.github.sashirestela.openai.agent.Agent;
import io.github.sashirestela.openai.agent.ClaudeRequest;
import io.github.sashirestela.openai.agent.ClaudeResponse;
import io.github.sashirestela.openai.agent.HttpHelper;
import io.github.sashirestela.openai.agent.Instance;
import io.github.sashirestela.openai.agent.ProviderConfig;
import io.github.sashirestela.openai.agent.exception.AgentException;
import io.github.sashirestela.openai.agent.exception.RequestTimeoutException;
import io.github.sashirestela.openai.agent.exception.ThreadNotFoundException;
import io.github.sashirestela.openai.common.content.ContentPart;
import io.github.sashirestela.openai.common.content.ContentPart.ContentPartTextAnnotation;
import io.github.sashirestela.openai.domain.assistant.ThreadMessage;
import io.github.sashirestela.openai.domain.assistant.ThreadMessageRequest;
import io.github.sashirestela.openai.domain.assistant.ThreadMessageRole;
import io.github.sashirestela.openai.domain.assistant.ThreadRequest;
import io.github.sashirestela.openai.domain.assistant.ThreadRun;
import io.github.sashirestela.openai.domain.assistant.ThreadRun.RunStatus;
import io.github.sashirestela.openai.domain.assistant.ThreadRunRequest;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages thread operations for both OpenAI and Claude.
 * Handles thread creation, message sending, and cleanup.
 */
public class ThreadManager {

    private static final Logger logger = LoggerFactory.getLogger(ThreadManager.class);

    private final HttpHelper httpHelper;
    private final InstanceRouter instanceRouter;
    private final ClaudeAdapter claudeAdapter;

    // Claude virtual thread storage (in-memory conversation history)
    private final Map<String, List<ClaudeRequest.ClaudeMessage>> claudeThreads;

    public ThreadManager(HttpHelper httpHelper, InstanceRouter instanceRouter, ClaudeAdapter claudeAdapter) {
        this.httpHelper = httpHelper;
        this.instanceRouter = instanceRouter;
        this.claudeAdapter = claudeAdapter;
        this.claudeThreads = new ConcurrentHashMap<>();
    }

    // ==================== THREAD LIFECYCLE ====================

    /**
     * Creates a new persistent thread for the given model.
     * Returns an encoded thread ID (format: "instanceIndex_threadId").
     *
     * @param model Model name (e.g., "gpt-4o", "claude-sonnet-4-5")
     * @return Encoded thread ID
     */
    public CompletableFuture<String> createThread(String model) {
        return CompletableFuture.supplyAsync(() -> {
            int instIndex = instanceRouter.getNextInstanceForModel(model);
            Instance instance = instanceRouter.getInstance(instIndex);

            // Check if Anthropic model - use virtual threads
            if (ProviderConfig.isAnthropicModel(model)) {
                String threadId = "claude_" + java.util.UUID.randomUUID().toString();
                claudeThreads.put(threadId, new ArrayList<>());
                logger.debug("Created virtual Claude thread {} on instance {}", threadId, instIndex);
                return encodeWithInstance(instIndex, threadId);
            }

            // OpenAI: create real thread
            io.github.sashirestela.openai.domain.assistant.Thread thread = httpHelper.post(
                    instance, ProviderConfig.Endpoint.THREADS, null,
                    ThreadRequest.builder().build(),
                    io.github.sashirestela.openai.domain.assistant.Thread.class).join();

            logger.debug("Created OpenAI thread {} on instance {}", thread.getId(), instIndex);
            return encodeWithInstance(instIndex, thread.getId());
        });
    }

    /**
     * Sends a message to an existing thread.
     *
     * @param agent Agent to use
     * @param threadRef Encoded thread reference
     * @param message User message
     * @return Agent's response
     */
    public CompletableFuture<String> sendMessage(Agent agent, String threadRef, String message) {
        return CompletableFuture.supplyAsync(() -> {
            int instanceIndex = extractInstanceIndex(threadRef);
            String actualThreadId = extractThreadId(threadRef);
            Instance instance = instanceRouter.getInstance(instanceIndex);

            // Claude virtual thread
            if (actualThreadId.startsWith("claude_") || ProviderConfig.isAnthropicModel(agent.getModel())) {
                return sendMessageToClaude(agent, actualThreadId, message, instanceIndex);
            }

            // OpenAI thread
            return sendMessageToOpenAI(agent, actualThreadId, message, instance, instanceIndex);
        });
    }

    /**
     * Deletes a thread.
     *
     * @param threadRef Encoded thread reference
     * @return true if deleted successfully
     */
    public CompletableFuture<Boolean> deleteThread(String threadRef) {
        return CompletableFuture.supplyAsync(() -> {
            int instanceIndex = extractInstanceIndex(threadRef);
            String actualThreadId = extractThreadId(threadRef);

            // Claude virtual thread
            if (actualThreadId.startsWith("claude_")) {
                boolean removed = claudeThreads.remove(actualThreadId) != null;
                logger.debug("Deleted virtual Claude thread: {} (success: {})", actualThreadId, removed);
                return removed;
            }

            // OpenAI thread
            Instance instance = instanceRouter.getInstance(instanceIndex);
            Map<String, String> pathParams = new HashMap<>();
            pathParams.put("threadId", actualThreadId);
            httpHelper.delete(instance, ProviderConfig.Endpoint.THREAD, null, pathParams).join();
            logger.debug("Deleted OpenAI thread {} from instance {}", actualThreadId, instanceIndex);
            return true;
        });
    }

    // ==================== PRIVATE METHODS ====================

    private String sendMessageToClaude(Agent agent, String threadId, String message, int instanceIndex) {
        List<ClaudeRequest.ClaudeMessage> history = claudeThreads.get(threadId);
        if (history == null) {
            throw new ThreadNotFoundException(threadId);
        }

        // Add user message to history
        history.add(ClaudeRequest.ClaudeMessage.builder()
                .role("user")
                .content(message)
                .build());

        // Call Claude API with full history
        ClaudeResponse response = claudeAdapter.callClaude(
                instanceRouter.getInstance(instanceIndex),
                agent.getModel(),
                agent.getInstructions(),
                new ArrayList<>(history),
                agent.getTemperature(),
                agent.getMaxTokens(),
                null  // No structured output for thread messages
        );

        String responseText = response.getTextContent();

        // Add assistant response to history
        history.add(ClaudeRequest.ClaudeMessage.builder()
                .role("assistant")
                .content(responseText)
                .build());

        logger.debug("Claude thread {} message exchanged (history: {} messages)", threadId, history.size());
        return responseText;
    }

    private String sendMessageToOpenAI(Agent agent, String threadId, String message,
                                        Instance instance, int instanceIndex) {
        Map<String, String> threadParams = new HashMap<>();
        threadParams.put("threadId", threadId);

        // Add message
        var messageRequest = ThreadMessageRequest.builder()
                .role(ThreadMessageRole.USER)
                .content(message)
                .build();
        httpHelper.post(instance, ProviderConfig.Endpoint.THREAD_MESSAGES, null,
                messageRequest, ThreadMessage.class, threadParams).join();

        // Get assistant ID for this instance
        String assistantId = getAssistantId(agent, instanceIndex);

        // Create and run
        ThreadRunRequest runRequest = ThreadRunRequest.builder()
                .assistantId(assistantId)
                .temperature(agent.getTemperature())
                .build();

        ThreadRun run = httpHelper.post(instance, ProviderConfig.Endpoint.THREAD_RUNS, null,
                runRequest, ThreadRun.class, threadParams).join();

        // Poll for completion
        ThreadRun completedRun = pollForCompletion(instance, threadId, run.getId(), agent.getResponseTimeout());

        // Get response
        ThreadMessagesResponse messagesResponse = httpHelper.get(instance, ProviderConfig.Endpoint.THREAD_MESSAGES,
                null, ThreadMessagesResponse.class, threadParams).join();

        return extractTextFromMessages(messagesResponse);
    }

    private ThreadRun pollForCompletion(Instance instance, String threadId, String runId, long timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;

        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("threadId", threadId);
        pathParams.put("runId", runId);

        while (true) {
            ThreadRun run = httpHelper.get(instance, ProviderConfig.Endpoint.THREAD_RUN,
                    null, ThreadRun.class, pathParams).join();

            RunStatus status = run.getStatus();
            if (status == RunStatus.COMPLETED || status == RunStatus.FAILED ||
                status == RunStatus.CANCELLED || status == RunStatus.EXPIRED) {
                return run;
            }

            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw new RequestTimeoutException(timeoutSeconds);
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Polling interrupted", e);
            }
        }
    }

    private String getAssistantId(Agent agent, int instanceIndex) {
        if (agent.getAssistantIds() != null && instanceIndex < agent.getAssistantIds().size()) {
            String id = agent.getAssistantIds().get(instanceIndex);
            if (id != null && !id.isEmpty()) {
                return id;
            }
        }
        throw new AgentException(AgentException.ErrorCode.AGENT_CREATION_FAILED,
                "No assistant ID configured for instance " + instanceIndex + " of agent: " + agent.getId());
    }

    private String extractTextFromMessages(ThreadMessagesResponse response) {
        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            return "";
        }

        var message = response.getData().get(0);
        if (message.getContent() == null || message.getContent().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ContentPart part : message.getContent()) {
            if (part instanceof ContentPartTextAnnotation) {
                ContentPartTextAnnotation textPart = (ContentPartTextAnnotation) part;
                if (textPart.getText() != null && textPart.getText().getValue() != null) {
                    sb.append(textPart.getText().getValue());
                }
            }
        }
        return sb.toString();
    }

    // ==================== ENCODING/DECODING ====================

    private String encodeWithInstance(int instanceIndex, String actualId) {
        return instanceIndex + "_" + actualId;
    }

    private int extractInstanceIndex(String ref) {
        if (ref == null || !ref.contains("_")) {
            return 0;
        }
        int underscoreIndex = ref.indexOf('_');
        try {
            return Integer.parseInt(ref.substring(0, underscoreIndex));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String extractThreadId(String ref) {
        if (ref == null || !ref.contains("_")) {
            return ref;
        }
        int underscoreIndex = ref.indexOf('_');
        return ref.substring(underscoreIndex + 1);
    }

    // ==================== RESPONSE WRAPPER ====================

    @Data
    public static class ThreadMessagesResponse {
        private List<ThreadMessage> data;
        private String firstId;
        private String lastId;
        private boolean hasMore;
    }
}
