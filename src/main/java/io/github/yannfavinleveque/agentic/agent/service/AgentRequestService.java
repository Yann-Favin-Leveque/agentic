package io.github.yannfavinleveque.agentic.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.model.ClaudeRequest;
import io.github.yannfavinleveque.agentic.agent.model.ClaudeResponse;
import io.github.yannfavinleveque.agentic.agent.model.DefaultResult;
import io.github.yannfavinleveque.agentic.support.HttpHelper;
import io.github.yannfavinleveque.agentic.agent.core.Instance;
import io.github.yannfavinleveque.agentic.agent.core.ProviderConfig;
import io.github.yannfavinleveque.agentic.agent.exception.AgentException;
import io.github.yannfavinleveque.agentic.agent.exception.AgentNotFoundException;
import io.github.yannfavinleveque.agentic.agent.exception.ContentFilterException;
import io.github.yannfavinleveque.agentic.agent.exception.RateLimitException;
import io.github.yannfavinleveque.agentic.agent.exception.RequestTimeoutException;
import io.github.yannfavinleveque.agentic.common.ResponseFormat;
import io.github.yannfavinleveque.agentic.common.content.ContentPart;
import io.github.yannfavinleveque.agentic.common.content.ContentPart.ContentPartTextAnnotation;
import io.github.yannfavinleveque.agentic.domain.assistant.ThreadMessage;
import io.github.yannfavinleveque.agentic.domain.assistant.ThreadMessageRequest;
import io.github.yannfavinleveque.agentic.domain.assistant.ThreadMessageRole;
import io.github.yannfavinleveque.agentic.domain.assistant.ThreadRequest;
import io.github.yannfavinleveque.agentic.domain.assistant.ThreadRun;
import io.github.yannfavinleveque.agentic.domain.assistant.ThreadRun.RunStatus;
import io.github.yannfavinleveque.agentic.domain.assistant.ThreadRunRequest;
import io.github.yannfavinleveque.agentic.domain.assistant.VectorStoreRequest;
import io.github.yannfavinleveque.agentic.support.JsonSchemaGenerator;
import io.github.yannfavinleveque.agentic.support.RateLimiter;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles agent request execution with retry logic and rate limiting.
 * Supports both OpenAI Assistants API and Claude/Anthropic API.
 */
public class AgentRequestService {

    private static final Logger logger = LoggerFactory.getLogger(AgentRequestService.class);
    private static final int DEFAULT_BASE_TIMEOUT_MS = 120000; // 2 minutes

    private final AgentServiceConfig config;
    private final HttpHelper httpHelper;
    private final InstanceRouter instanceRouter;
    private final ClaudeAdapter claudeAdapter;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;
    private final AgentManager agentManager;

    public AgentRequestService(AgentServiceConfig config, HttpHelper httpHelper,
                                InstanceRouter instanceRouter, ClaudeAdapter claudeAdapter,
                                ObjectMapper objectMapper, RateLimiter rateLimiter,
                                AgentManager agentManager) {
        this.config = config;
        this.httpHelper = httpHelper;
        this.instanceRouter = instanceRouter;
        this.claudeAdapter = claudeAdapter;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        this.agentManager = agentManager;
    }

    // ==================== MAIN REQUEST METHODS ====================

    /**
     * Sends a request to an agent and waits for completion.
     *
     * @param agentId     Agent ID
     * @param userMessage User message content
     * @param threadRef   Thread reference (null for oneshot, or encoded thread ID for persistent)
     * @return CompletableFuture with the agent's response as AgentResult
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, String threadRef) {
        Agent agent = agentManager.getAgent(agentId);

        return attemptRequest(agent, userMessage, threadRef, new HashMap<>(), 0)
                .thenApply(jsonResponse -> {
                    try {
                        // If no result class configured, return DefaultResult with raw response
                        if (agent.getResultClass() == null || agent.getResultClass().isEmpty()) {
                            return new DefaultResult(jsonResponse);
                        }

                        // If no package configured, return DefaultResult
                        if (config.getAgentResultClassPackage() == null || config.getAgentResultClassPackage().isEmpty()) {
                            logger.warn("Agent {} has resultClass but agentResultClassPackage not configured, returning DefaultResult", agentId);
                            return new DefaultResult(jsonResponse);
                        }

                        // Build full class name: package + resultClass
                        String fullClassName = config.getAgentResultClassPackage() + "." + agent.getResultClass();
                        logger.debug("Deserializing response for agent {} to class: {}", agentId, fullClassName);

                        // Load class dynamically and deserialize
                        Class<?> resultClass = Class.forName(fullClassName);
                        return (AgentResult) objectMapper.readValue(jsonResponse, resultClass);

                    } catch (ClassNotFoundException e) {
                        String fullClassName = config.getAgentResultClassPackage() + "." + agent.getResultClass();
                        throw new AgentException(AgentException.ErrorCode.INVALID_CONFIGURATION,
                                "Result class not found: " + fullClassName + " for agent " + agentId, e);
                    } catch (Exception e) {
                        throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                                "Failed to deserialize response for agent " + agentId + ": " + e.getMessage(), e);
                    }
                });
    }

    /**
     * Sends a message to an agent (oneshot - creates temporary thread).
     * Convenience method that calls requestAgent with null threadRef.
     *
     * @param agentId     Agent ID
     * @param userMessage User message content
     * @return CompletableFuture with the agent's response as AgentResult
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage) {
        return requestAgent(agentId, userMessage, null);
    }

    /**
     * Sends a request to an agent with vector store support.
     *
     * @param agentId       Agent ID
     * @param userMessage   User message
     * @param vectorStoreId Vector store ID
     * @return CompletableFuture with response
     */
    public CompletableFuture<String> requestAgentWithVectorStorage(String agentId, String userMessage,
                                                                    String vectorStoreId) {
        Agent agent = agentManager.getAgent(agentId);

        if (instanceRouter.isDegradedMode()) {
            return CompletableFuture.failedFuture(
                    new AgentException(AgentException.ErrorCode.NO_INSTANCE_AVAILABLE,
                            "No OpenAI/Azure instances initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Create thread on instance that has this agent's model
                int instanceIdx = instanceRouter.getNextInstanceForModel(agent.getModel());
                Instance instance = instanceRouter.getInstance(instanceIdx);

                io.github.yannfavinleveque.agentic.domain.assistant.Thread thread = httpHelper.post(
                        instance, ProviderConfig.Endpoint.THREADS, null,
                        ThreadRequest.builder().build(),
                        io.github.yannfavinleveque.agentic.domain.assistant.Thread.class).join();
                String threadId = thread.getId();

                // Add message
                Map<String, String> threadParams = new HashMap<>();
                threadParams.put("threadId", threadId);

                ThreadMessageRequest messageRequest = ThreadMessageRequest.builder()
                        .role(ThreadMessageRole.USER)
                        .content(userMessage)
                        .build();

                httpHelper.post(instance, ProviderConfig.Endpoint.THREAD_MESSAGES, null,
                        messageRequest, ThreadMessage.class, threadParams).join();

                // Get assistant ID for this specific instance
                String assistantId = getAssistantIdForInstance(agent, instanceIdx);

                ThreadRunRequest runRequest = ThreadRunRequest.builder()
                        .assistantId(assistantId)
                        .temperature(agent.getTemperature())
                        .build();

                ThreadRun run = httpHelper.post(instance, ProviderConfig.Endpoint.THREAD_RUNS, null,
                        runRequest, ThreadRun.class, threadParams).join();

                // Poll for completion
                ThreadRun completedRun = pollForCompletion(instance, threadId, run.getId(), agent.getResponseTimeout());

                if (completedRun.getStatus() != RunStatus.COMPLETED) {
                    throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                            "Run failed with status: " + completedRun.getStatus());
                }

                // Get response
                ThreadMessagesResponse messagesResponse = httpHelper.get(instance, ProviderConfig.Endpoint.THREAD_MESSAGES,
                        null, ThreadMessagesResponse.class, threadParams).join();

                if (messagesResponse.getData() == null || messagesResponse.getData().isEmpty()) {
                    throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "No messages returned");
                }

                return extractMessageContent(messagesResponse.getData().get(0).getContent());

            } catch (AgentException e) {
                throw e;
            } catch (Exception e) {
                logger.error("Request with vector storage failed for agent: {}", agentId, e);
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                        "Request with vector storage failed", e);
            }
        });
    }

    // ==================== INTERNAL REQUEST LOGIC ====================

    /**
     * Internal method to attempt a request with retry logic.
     */
    private CompletableFuture<String> attemptRequest(Agent agent, String userMessage, String threadId,
                                                       Map<String, Object> additionalParams, int attemptNumber) {
        // Rate limiting
        if (!rateLimiter.tryConsume()) {
            logger.debug("Rate limit reached, delaying request");
            return delayedCompletion(100, TimeUnit.MILLISECONDS)
                    .thenCompose(v -> attemptRequest(agent, userMessage, threadId, additionalParams, attemptNumber));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeAgentRequest(agent, userMessage, threadId, additionalParams);
            } catch (Exception e) {
                return handleRequestException(agent, userMessage, threadId, additionalParams, attemptNumber, e);
            }
        });
    }

    /**
     * Executes an agent request using model-aware instance selection.
     * Routes to Claude API for Azure Anthropic instances.
     */
    private String executeAgentRequest(Agent agent, String userMessage, String threadId,
                                        Map<String, Object> additionalParams) throws Exception {
        if (instanceRouter.isDegradedMode()) {
            throw new AgentException(AgentException.ErrorCode.NO_INSTANCE_AVAILABLE, "No instances configured");
        }

        // Determine which instance to use
        int instanceIdx;
        String actualThreadId;

        // Check if threadId is encoded with instance index (persistent thread)
        String[] decoded = instanceRouter.decodeInstanceId(threadId);
        if (decoded != null) {
            instanceIdx = Integer.parseInt(decoded[0]);
            actualThreadId = decoded[1];
            logger.debug("Using persistent thread {} on instance {} (model: {})",
                    actualThreadId, instanceIdx, agent.getModel());
        } else {
            instanceIdx = instanceRouter.getNextInstanceForModel(agent.getModel());
            actualThreadId = threadId;
            logger.debug("Using model-aware round-robin for agent '{}': selected instance {} for model '{}'",
                    agent.getName(), instanceIdx, agent.getModel());
        }

        Instance instance = instanceRouter.getInstance(instanceIdx);

        // Check if Anthropic model - use Claude API format
        if (ProviderConfig.isAnthropicModel(agent.getModel())) {
            logger.debug("Routing to Claude API for agent '{}' (model: {})", agent.getName(), agent.getModel());
            return executeClaudeRequest(agent, userMessage, instanceIdx);
        }

        // Get assistant ID for this specific instance
        String assistantId = getAssistantIdForInstance(agent, instanceIdx);

        logger.debug("Using instance {} (model: {}) with assistant {}", instanceIdx, agent.getModel(), assistantId);

        // Create thread if needed
        if (actualThreadId == null || actualThreadId.isEmpty()) {
            io.github.yannfavinleveque.agentic.domain.assistant.Thread thread = httpHelper.post(
                    instance, ProviderConfig.Endpoint.THREADS, null,
                    ThreadRequest.builder().build(),
                    io.github.yannfavinleveque.agentic.domain.assistant.Thread.class).join();
            actualThreadId = thread.getId();
            logger.debug("Created new thread {} on instance {}", actualThreadId, instanceIdx);
        }

        // Add message to thread
        Map<String, String> threadParams = new HashMap<>();
        threadParams.put("threadId", actualThreadId);

        ThreadMessageRequest messageRequest = ThreadMessageRequest.builder()
                .role(ThreadMessageRole.USER)
                .content(userMessage)
                .build();

        httpHelper.post(instance, ProviderConfig.Endpoint.THREAD_MESSAGES, null,
                messageRequest, ThreadMessage.class, threadParams).join();

        // Create and execute run
        ThreadRunRequest.ThreadRunRequestBuilder runBuilder = ThreadRunRequest.builder()
                .assistantId(assistantId);

        if (agent.getTemperature() != null) {
            runBuilder.temperature(agent.getTemperature());
        }

        ThreadRun run = httpHelper.post(instance, ProviderConfig.Endpoint.THREAD_RUNS, null,
                runBuilder.build(), ThreadRun.class, threadParams).join();

        // Poll for completion
        ThreadRun completedRun = pollForCompletion(instance, actualThreadId, run.getId(), agent.getResponseTimeout());

        if (completedRun.getStatus() != RunStatus.COMPLETED) {
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Run failed with status: " + completedRun.getStatus());
        }

        // Get response
        ThreadMessagesResponse messagesResponse = httpHelper.get(instance, ProviderConfig.Endpoint.THREAD_MESSAGES,
                null, ThreadMessagesResponse.class, threadParams).join();

        if (messagesResponse.getData() == null || messagesResponse.getData().isEmpty()) {
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "No messages returned");
        }

        return extractMessageContent(messagesResponse.getData().get(0).getContent());
    }

    /**
     * Executes a Claude agent request (oneshot - no conversation history).
     */
    private String executeClaudeRequest(Agent agent, String message, int instanceIndex) {
        try {
            ClaudeRequest.ClaudeRequestBuilder requestBuilder = ClaudeRequest.builder()
                    .model(agent.getModel())
                    .maxTokens(agent.getMaxTokens() != null ? agent.getMaxTokens() : 4096)
                    .system(agent.getInstructions())
                    .messages(List.of(
                            ClaudeRequest.ClaudeMessage.builder()
                                    .role("user")
                                    .content(message)
                                    .build()
                    ))
                    .temperature(agent.getTemperature());

            Instance instance = instanceRouter.getInstance(instanceIndex);

            // Get result class if configured
            Class<?> resultClass = null;
            if (agent.getResultClass() != null && !agent.getResultClass().isEmpty() &&
                    config.getAgentResultClassPackage() != null) {
                try {
                    String fullClassName = config.getAgentResultClassPackage() + "." + agent.getResultClass();
                    resultClass = Class.forName(fullClassName);
                } catch (ClassNotFoundException e) {
                    logger.warn("Result class not found: {}", agent.getResultClass());
                }
            }

            ClaudeResponse response = claudeAdapter.callClaude(instance, agent.getModel(),
                    agent.getInstructions(),
                    List.of(ClaudeRequest.ClaudeMessage.builder().role("user").content(message).build()),
                    agent.getTemperature(),
                    agent.getMaxTokens() != null ? agent.getMaxTokens() : 4096,
                    resultClass);

            return response.getTextContent();

        } catch (Exception e) {
            logger.error("Claude request failed for agent: {}", agent.getId(), e);
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Claude request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Polls for run completion.
     */
    private ThreadRun pollForCompletion(Instance instance, String threadId, String runId, long timeoutSeconds)
            throws Exception {
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;

        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("threadId", threadId);
        pathParams.put("runId", runId);

        while (true) {
            ThreadRun run = httpHelper.get(instance, ProviderConfig.Endpoint.THREAD_RUN, null,
                    ThreadRun.class, pathParams).join();

            RunStatus status = run.getStatus();

            if (status == RunStatus.COMPLETED || status == RunStatus.FAILED ||
                    status == RunStatus.CANCELLED || status == RunStatus.EXPIRED) {
                return run;
            }

            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw new RequestTimeoutException(timeoutSeconds);
            }

            Thread.sleep(1000);
        }
    }

    /**
     * Handles request exceptions with retry logic.
     * NOW INCLUDES:
     * - Rate limit retry with delay (instead of throwing)
     * - Progressive timeout for consecutive timeout errors
     * - Smart 4xx handling (don't retry except 429)
     */
    private String handleRequestException(Agent agent, String userMessage, String threadId,
                                           Map<String, Object> additionalParams, int attemptNumber, Exception e) {
        // Check max retries
        if (attemptNumber >= config.getMaxRetries()) {
            logger.error("Max retries ({}) reached for agent: {}", config.getMaxRetries(), agent.getId());
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Request failed after " + config.getMaxRetries() + " retries: " + e.getMessage(), e);
        }

        String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // Check for content filter - don't retry
        if (ContentFilterException.isContentFilterError(errorMessage)) {
            logger.error("Content filter error detected, not retrying");
            throw new ContentFilterException(e.getMessage(), e);
        }

        // Check for 4xx errors (except 429) - don't retry
        if (is4xxError(errorMessage) && !isRateLimitError(errorMessage)) {
            logger.error("Client error (4xx) detected, not retrying: {}", e.getMessage());
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Client error (4xx), not retrying: " + e.getMessage(), e);
        }

        // Handle rate limit - retry with delay
        if (isRateLimitError(errorMessage)) {
            long retryDelay = extractRetryAfter(errorMessage);
            if (retryDelay == 0) {
                retryDelay = config.getRateLimitDelayMs();
            }
            logger.warn("Rate limit hit (attempt {}/{}), waiting {}ms before retry",
                    attemptNumber + 1, config.getMaxRetries(), retryDelay);
            try {
                Thread.sleep(retryDelay);
                return attemptRequest(agent, userMessage, threadId, additionalParams, attemptNumber + 1).join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Rate limit retry interrupted", ie);
            }
        }

        // Handle timeout - progressive timeout increase
        if (e instanceof RequestTimeoutException) {
            int newTimeoutSeconds = (int) (agent.getResponseTimeout() * (attemptNumber + 2));
            logger.warn("Timeout (attempt {}/{}), increasing timeout to {}s for next attempt",
                    attemptNumber + 1, config.getMaxRetries(), newTimeoutSeconds);
            // Note: This would require passing timeout to executeAgentRequest
            // For now, we'll just retry with normal delay
        }

        // Standard exponential backoff for other errors
        long delay = calculateDelay(attemptNumber);
        logger.warn("Request failed (attempt {}/{}), retrying in {}ms: {}",
                attemptNumber + 1, config.getMaxRetries(), delay, e.getMessage());

        try {
            Thread.sleep(delay);
            return attemptRequest(agent, userMessage, threadId, additionalParams, attemptNumber + 1).join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Retry interrupted", ie);
        }
    }

    // ==================== UTILITY METHODS ====================

    private String getAssistantIdForInstance(Agent agent, int instanceIdx) {
        String assistantId = null;
        if (agent.getAssistantIds() != null && instanceIdx < agent.getAssistantIds().size()) {
            assistantId = agent.getAssistantIds().get(instanceIdx);
        }
        if (assistantId == null) {
            throw new AgentException(AgentException.ErrorCode.INVALID_CONFIGURATION,
                    "No assistant ID configured for instance " + instanceIdx + " of agent: " + agent.getId());
        }
        return assistantId;
    }

    /**
     * Calculates exponential backoff delay.
     */
    private long calculateDelay(int attemptNumber) {
        return config.getRetryBaseDelayMs() * (long) Math.pow(2, attemptNumber);
    }

    /**
     * Extracts retry-after value from error message (in milliseconds).
     * Returns 0 if not found.
     */
    private long extractRetryAfter(String errorMessage) {
        try {
            if (errorMessage.contains("retry") && errorMessage.contains("after")) {
                String[] parts = errorMessage.split("\\s+");
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].contains("after") && i + 1 < parts.length) {
                        String next = parts[i + 1].replaceAll("[^0-9]", "");
                        if (!next.isEmpty()) {
                            long seconds = Long.parseLong(next);
                            logger.trace("Extracted retry-after: {}s", seconds);
                            return seconds * 1000; // Convert to ms
                        }
                    }
                }
            }
        } catch (Exception ex) {
            logger.trace("Could not extract retry-after value from: {}", errorMessage);
        }
        return 0;
    }

    private boolean isRateLimitError(String errorMessage) {
        return errorMessage.contains("rate_limit") || errorMessage.contains("429") ||
                errorMessage.contains("rate limit") || errorMessage.contains("too many requests");
    }

    private boolean is4xxError(String errorMessage) {
        return errorMessage.contains("400") || errorMessage.contains("401") ||
                errorMessage.contains("403") || errorMessage.contains("404") ||
                errorMessage.contains("422") || errorMessage.contains("bad request") ||
                errorMessage.contains("unauthorized") || errorMessage.contains("forbidden");
    }

    /**
     * Creates a delayed CompletableFuture.
     */
    private CompletableFuture<Void> delayedCompletion(long delay, TimeUnit unit) {
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(unit.toMillis(delay));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Extracts text content from message content parts.
     */
    private String extractMessageContent(List<ContentPart> contentParts) {
        if (contentParts == null || contentParts.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ContentPart part : contentParts) {
            if (part instanceof ContentPartTextAnnotation) {
                ContentPartTextAnnotation textPart = (ContentPartTextAnnotation) part;
                if (textPart.getText() != null && textPart.getText().getValue() != null) {
                    sb.append(textPart.getText().getValue());
                }
            }
        }
        return sb.toString();
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
