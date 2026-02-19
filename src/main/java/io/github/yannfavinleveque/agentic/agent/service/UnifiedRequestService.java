package io.github.yannfavinleveque.agentic.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.core.Instance;
import io.github.yannfavinleveque.agentic.agent.core.ProviderConfig;
import io.github.yannfavinleveque.agentic.agent.exception.AgentException;
import io.github.yannfavinleveque.agentic.agent.exception.ContentFilterException;
import io.github.yannfavinleveque.agentic.agent.exception.RateLimitException;
import io.github.yannfavinleveque.agentic.agent.exception.RequestTimeoutException;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import io.github.yannfavinleveque.agentic.agent.model.ClaudeRequest;
import io.github.yannfavinleveque.agentic.agent.model.ClaudeResponse;
import io.github.yannfavinleveque.agentic.agent.model.DefaultResult;
import io.github.yannfavinleveque.agentic.agent.model.FunctionCall;
import io.github.yannfavinleveque.agentic.agent.model.Message;
import io.github.yannfavinleveque.agentic.agent.model.ModelRequestOptions;
import io.github.yannfavinleveque.agentic.common.ResponseFormat;
import io.github.yannfavinleveque.agentic.domain.chat.Chat;
import io.github.yannfavinleveque.agentic.domain.chat.ChatMessage;
import io.github.yannfavinleveque.agentic.domain.chat.ChatRequest;
import io.github.yannfavinleveque.agentic.domain.embedding.EmbeddingRequest;
import io.github.yannfavinleveque.agentic.domain.image.Image;
import io.github.yannfavinleveque.agentic.domain.image.ImageRequest;
import io.github.yannfavinleveque.agentic.domain.image.ImageRequest.Quality;
import io.github.yannfavinleveque.agentic.domain.image.ImageResponseFormat;
import io.github.yannfavinleveque.agentic.domain.image.Size;
import io.github.yannfavinleveque.agentic.support.HttpHelper;
import io.github.yannfavinleveque.agentic.support.JsonSchemaGenerator;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unified stateless request service for both OpenAI and Claude. Uses OpenAI Responses API and
 * Claude Messages API for a fully stateless architecture.
 * <p>
 * This service handles:
 * </p>
 * <ul>
 * <li>Agent requests: OpenAI Responses API and Claude Messages API</li>
 * <li>Chat completions: OpenAI Chat Completions API and Claude Messages API</li>
 * <li>Embeddings: OpenAI Embeddings API</li>
 * <li>Image generation: DALL-E API</li>
 * </ul>
 * <p>
 * Key features:
 * </p>
 * <ul>
 * <li>Fully stateless - no threads, no assistants</li>
 * <li>Conversation history passed with each request</li>
 * <li>Unified tool support (web search, code interpreter, custom functions)</li>
 * <li>Auto-detection of provider based on model name</li>
 * </ul>
 *
 * @see AgentService#requestAgent(String, String, List)
 */
public class UnifiedRequestService {

    private static final Logger logger = LoggerFactory.getLogger(UnifiedRequestService.class);

    /**
     * Scheduler for non-blocking delays.
     */
    private static final ScheduledExecutorService DELAY_SCHEDULER = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "unified-delay-scheduler");
        t.setDaemon(true);
        return t;
    });

    // Concurrency control
    private final Map<String, InstanceLimiter> instanceLimiters = new ConcurrentHashMap<>();

    private static class InstanceLimiter {

        final AtomicInteger inProgress = new AtomicInteger(0);
        final AtomicLong lastRequestTimeMs = new AtomicLong(0);
        final int maxConcurrent;
        final long minIntervalMs; // minimum ms between requests (1000 / requestsPerSecond)
        final String instanceId;

        InstanceLimiter(String instanceId, int maxConcurrent, int requestsPerSecond) {
            this.instanceId = instanceId;
            this.maxConcurrent = maxConcurrent;
            this.minIntervalMs = requestsPerSecond > 0 ? 1000L / requestsPerSecond : 0;
        }

        /**
         * Returns the delay in ms needed before the next request can be sent.
         * Updates lastRequestTimeMs atomically to "claim" the next slot.
         */
        long acquireRateSlot() {
            if (minIntervalMs <= 0) return 0;
            while (true) {
                long last = lastRequestTimeMs.get();
                long now = System.currentTimeMillis();
                long earliest = last + minIntervalMs;
                long nextTime = Math.max(now, earliest);
                if (lastRequestTimeMs.compareAndSet(last, nextTime)) {
                    return Math.max(0, nextTime - now);
                }
            }
        }

        boolean tryAcquire() {
            int current = inProgress.get();
            if (current >= maxConcurrent)
                return false;
            return inProgress.compareAndSet(current, current + 1);
        }

        void release() {
            inProgress.decrementAndGet();
        }

        int getCurrentCount() {
            return inProgress.get();
        }

    }

    /**
     * Internal class representing a parsed API response with text content and function calls.
     */
    @Data
    private static class ParsedResponse {
        private final String textContent;
        private final List<FunctionCall> functionCalls;

        static ParsedResponse ofText(String text) {
            return new ParsedResponse(text, Collections.emptyList());
        }

        static ParsedResponse ofFunctionCalls(List<FunctionCall> calls) {
            return new ParsedResponse(null, calls);
        }

        static ParsedResponse of(String text, List<FunctionCall> calls) {
            return new ParsedResponse(text, calls != null ? calls : Collections.emptyList());
        }

        boolean hasFunctionCalls() {
            return functionCalls != null && !functionCalls.isEmpty();
        }
    }

    private final AgentServiceConfig config;
    private final HttpHelper httpHelper;
    private final InstanceRouter instanceRouter;
    private final ClaudeAdapter claudeAdapter;
    private final ObjectMapper objectMapper;
    private final AgentManager agentManager;

    public UnifiedRequestService(AgentServiceConfig config, HttpHelper httpHelper,
            InstanceRouter instanceRouter, ClaudeAdapter claudeAdapter,
            ObjectMapper objectMapper, AgentManager agentManager) {
        this.config = config;
        this.httpHelper = httpHelper;
        this.instanceRouter = instanceRouter;
        this.claudeAdapter = claudeAdapter;
        this.objectMapper = objectMapper;
        this.agentManager = agentManager;
        logger.info("UnifiedRequestService initialized (stateless mode)");
    }

    // ==================== MAIN API ====================

    /**
     * Sends a stateless request to an agent with conversation history.
     *
     * @param agentId     Agent ID
     * @param userMessage Current user message
     * @param history     Previous conversation messages (can be null or empty)
     * @return CompletableFuture with the agent's response
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, List<Message> history) {
        Agent agent = agentManager.getAgent(agentId);
        return attemptRequestWithRetry(agent, userMessage, history, 0);
    }

    /**
     * Sends a stateless request without history (single-turn).
     *
     * @param agentId     Agent ID
     * @param userMessage User message
     * @return CompletableFuture with the agent's response
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage) {
        return requestAgent(agentId, userMessage, (List<Message>) null);
    }

    /**
     * Sends a stateless request with a single image (vision).
     *
     * @param agentId      Agent ID
     * @param userMessage  Text prompt for the image
     * @param imageBase64  Base64-encoded image (PNG assumed)
     * @return CompletableFuture with the agent's response
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, String imageBase64) {
        return requestAgent(agentId, userMessage, null, List.of(imageBase64));
    }

    /**
     * Sends a stateless request with history and optional images (vision).
     *
     * @param agentId       Agent ID
     * @param userMessage   Text prompt
     * @param history       Previous conversation messages (can be null)
     * @param imagesBase64  List of base64-encoded images (can be null)
     * @return CompletableFuture with the agent's response
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, List<Message> history,
            List<String> imagesBase64) {
        Agent agent = agentManager.getAgent(agentId);

        // Build content parts: text + images
        List<Message.ContentPart> contentParts = new ArrayList<>();
        contentParts.add(Message.ContentPart.text(userMessage));

        if (imagesBase64 != null) {
            for (String imageBase64 : imagesBase64) {
                contentParts.add(Message.ContentPart.pngBase64(imageBase64));
            }
        }

        // Create user message with multimodal content
        Message userMsg = Message.builder()
                .role("user")
                .content(contentParts)
                .build();

        // Build full history with current message
        List<Message> fullHistory = new ArrayList<>();
        if (history != null) {
            fullHistory.addAll(history);
        }
        fullHistory.add(userMsg);

        return attemptRequestAgentWithImages(agent, fullHistory, 0);
    }

    private CompletableFuture<AgentResult> attemptRequestAgentWithImages(Agent agent, List<Message> messagesWithUser, int attempt) {
        final int MAX_RETRIES = config.getMaxRetries();

        return executeRequestAgentWithImages(agent, messagesWithUser, attempt)
                .thenCompose(parsed -> deserializeResponse(agent, parsed))
                .handle((result, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(result);
                    }

                    Throwable cause = unwrapException(error);

                    if (!shouldRetry(cause) || attempt >= MAX_RETRIES) {
                        return CompletableFuture.<AgentResult>failedFuture(cause);
                    }

                    long delay = calculateDelay(cause, attempt);
                    logger.warn("Request failed (attempt {}/{}), retrying in {}ms: {}",
                            attempt + 1, MAX_RETRIES, delay, cause.getMessage());

                    return delayAsync(delay)
                            .thenCompose(v -> attemptRequestAgentWithImages(agent, messagesWithUser, attempt + 1));
                })
                .thenCompose(f -> f);
    }

    private CompletableFuture<ParsedResponse> executeRequestAgentWithImages(Agent agent, List<Message> messagesWithUser, int attempt) {
        if (instanceRouter.isDegradedMode()) {
            return CompletableFuture.failedFuture(
                    new AgentException(AgentException.ErrorCode.NO_INSTANCE_AVAILABLE, "No instances configured"));
        }

        int instanceIdx = instanceRouter.getNextInstanceForModel(agent.getModel());
        Instance instance = instanceRouter.getInstance(instanceIdx);
        InstanceLimiter limiter = getLimiterForInstance(instance);

        // First acquire concurrent stream permit
        if (!limiter.tryAcquire()) {
            return delayAsync(50)
                    .thenCompose(v -> executeRequestAgentWithImages(agent, messagesWithUser, attempt));
        }

        // Then rate limit: non-blocking wait if too soon since last request on this instance
        long rateDelay = limiter.acquireRateSlot();
        if (rateDelay > 0) {
            return delayAsync(rateDelay)
                    .thenCompose(v -> executeRequestAgentWithImagesAfterPermit(agent, messagesWithUser, instance, limiter));
        }

        return executeRequestAgentWithImagesAfterPermit(agent, messagesWithUser, instance, limiter);
    }

    private CompletableFuture<ParsedResponse> executeRequestAgentWithImagesAfterPermit(
            Agent agent, List<Message> messagesWithUser, Instance instance, InstanceLimiter limiter) {

        logger.info("-> REQUEST AGENT [VISION] | Agent: {} | Model: {} | Instance: {} | Messages: {}",
                agent.getName(), agent.getModel(), instance.getId(), messagesWithUser.size());

        CompletableFuture<ParsedResponse> requestFuture;

        if (ProviderConfig.isAnthropicModel(agent.getModel())) {
            requestFuture = executeClaudeRequestWithImages(agent, messagesWithUser, instance);
        } else {
            requestFuture = executeOpenAIRequestWithImages(agent, messagesWithUser, instance);
        }

        return requestFuture
                .whenComplete((response, error) -> {
                    limiter.release();
                    if (error == null) {
                        logger.info("<- RESPONSE AGENT [VISION] | Agent: {} | Response: {}",
                                agent.getName(), response);
                    } else {
                        logger.error("<- ERROR AGENT [VISION] | Agent: {} | Error: {}",
                                agent.getName(), error.getMessage());
                    }
                });
    }

    private CompletableFuture<ParsedResponse> executeOpenAIRequestWithImages(Agent agent, List<Message> messagesWithUser, Instance instance) {
        List<Map<String, Object>> input = new ArrayList<>();

        for (Message msg : messagesWithUser) {
            input.addAll(buildOpenAIInputItems(msg));
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", agent.getModel());
        requestBody.put("input", input);

        if (agent.getInstructions() != null && !agent.getInstructions().isEmpty()) {
            requestBody.put("instructions", agent.getInstructions());
        }

        if (agent.getTemperature() != null) {
            requestBody.put("temperature", agent.getTemperature());
        }

        requestBody.put("max_output_tokens", agent.getMaxTokens() != null ? agent.getMaxTokens() : 4096);

        // Reasoning configuration
        addOpenAIReasoningConfig(requestBody, agent.getReasoningEffort());

        List<Object> tools = buildOpenAIToolsForRequest(agent);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
        }

        if (agent.getResultClass() != null && !agent.getResultClass().isEmpty()) {
            String fullClassName = config.resolveResultClassName(agent.getResultClass());
            if (fullClassName != null) {
                try {
                    Class<?> resultClass = Class.forName(fullClassName);
                    Map<String, Object> schema = JsonSchemaGenerator.createFunctionSchemaFromClass(resultClass);

                    Map<String, Object> textFormat = new HashMap<>();
                    textFormat.put("type", "json_schema");
                    textFormat.put("name", resultClass.getSimpleName().toLowerCase() + "_response");
                    textFormat.put("schema", schema);
                    textFormat.put("strict", true);

                    Map<String, Object> text = new HashMap<>();
                    text.put("format", textFormat);
                    requestBody.put("text", text);
                } catch (ClassNotFoundException e) {
                    logger.warn("Result class not found: {} (resolved: {})", agent.getResultClass(), fullClassName);
                }
            } else {
                logger.warn("Cannot resolve result class '{}' - use FQCN or configure agentResultClassPackage",
                        agent.getResultClass());
            }
        }

        return httpHelper.postRaw(
                instance,
                ProviderConfig.Endpoint.RESPONSES,
                agent.getModel(),
                requestBody).thenApply(this::extractResponsesContentParsed);
    }

    private CompletableFuture<ParsedResponse> executeClaudeRequestWithImages(Agent agent, List<Message> messagesWithUser, Instance instance) {
        List<ClaudeRequest.ClaudeMessage> messages = new ArrayList<>();
        for (Message msg : messagesWithUser) {
            if (!msg.isSystem()) {
                messages.add(buildClaudeMessage(msg));
            }
        }

        List<ClaudeRequest.ClaudeTool> tools = ToolBuilder.buildClaudeTools(agent);

        Class<?> resultClass = null;
        if (agent.getResultClass() != null && !agent.getResultClass().isEmpty()) {
            String fullClassName = config.resolveResultClassName(agent.getResultClass());
            if (fullClassName != null) {
                try {
                    resultClass = Class.forName(fullClassName);
                } catch (ClassNotFoundException e) {
                    logger.warn("Result class not found: {} (resolved: {})", agent.getResultClass(), fullClassName);
                }
            }
        }

        return claudeAdapter.callClaudeAsync(
                instance,
                agent.getModel(),
                agent.getInstructions(),
                messages,
                agent.getTemperature(),
                agent.getMaxTokens() != null ? agent.getMaxTokens() : 4096,
                resultClass,
                tools,
                agent.getReasoningEffort()).thenApply(this::parseClaudeResponse);
    }

    /**
     * Parses a Claude response into a ParsedResponse with text and function calls.
     */
    private ParsedResponse parseClaudeResponse(ClaudeResponse response) {
        if (response == null || response.getContent() == null) {
            return ParsedResponse.ofText("");
        }

        StringBuilder textContent = new StringBuilder();
        List<FunctionCall> functionCalls = new ArrayList<>();

        for (ClaudeResponse.Content content : response.getContent()) {
            if ("text".equals(content.getType())) {
                if (content.getText() != null) {
                    textContent.append(content.getText());
                }
            } else if ("tool_use".equals(content.getType())) {
                // Claude returns tool_use blocks for function calls
                // Input is already parsed as Map/Object by Jackson, need to serialize back to JSON
                String argsJson = "{}";
                if (content.getInput() != null) {
                    try {
                        argsJson = objectMapper.writeValueAsString(content.getInput());
                    } catch (Exception e) {
                        logger.warn("Failed to serialize Claude tool_use input: {}", e.getMessage());
                        argsJson = content.getInput().toString();
                    }
                }
                FunctionCall call = FunctionCall.builder()
                        .id(content.getId())
                        .name(content.getName())
                        .arguments(argsJson)
                        .build();
                functionCalls.add(call);
                logger.debug("Parsed Claude tool_use: {} with input: {}", content.getName(), argsJson);
            } else if ("thinking".equals(content.getType())) {
                // Claude extended thinking block — skip for output, just log
                logger.debug("Claude response contains thinking block (skipped for output)");
            }
        }

        if (!functionCalls.isEmpty()) {
            String text = textContent.length() > 0 ? textContent.toString() : null;
            return ParsedResponse.of(text, functionCalls);
        }

        return ParsedResponse.ofText(textContent.toString());
    }

    // ==================== REQUEST MODEL (DIRECT MODEL CALLS) ====================

    /**
     * Sends a direct request to a model (without agent configuration).
     * <p>
     * Use this method when you want to call a model directly without registering an agent.
     * Supports structured output, web search, code interpreter, vision, and conversation history.
     * All options are configured via {@link ModelRequestOptions}.
     * </p>
     *
     * <p>
     * Example:
     * </p>
     *
     * <pre>{@code
     * // Simple text request
     * AgentResult result = agentService.requestModel("gpt-4o", "Hello!").join();
     *
     * // With structured output
     * ModelRequestOptions options = ModelRequestOptions.builder()
     *         .resultClass(MyResult.class)
     *         .build();
     * MyResult result = (MyResult) agentService.requestModel("gpt-4o", "Extract data", options).join();
     *
     * // With web search
     * ModelRequestOptions options = ModelRequestOptions.withWebSearch();
     * AgentResult result = agentService.requestModel("gpt-4o", "What's the latest news?", options).join();
     *
     * // With vision (single image)
     * ModelRequestOptions options = ModelRequestOptions.builder()
     *         .image(imageBase64)
     *         .build();
     * AgentResult result = agentService.requestModel("gpt-4o", "What's in this image?", options).join();
     *
     * // With vision (multiple images) and history
     * ModelRequestOptions options = ModelRequestOptions.builder()
     *         .images(List.of(img1, img2))
     *         .history(previousMessages)
     *         .build();
     * AgentResult result = agentService.requestModel("gpt-4o", "Compare these images", options).join();
     * }</pre>
     *
     * @param model       Model name (e.g., "gpt-4o", "claude-sonnet-4-5")
     * @param userMessage Current user message
     * @param options     Request options (can be null for defaults)
     * @return CompletableFuture with the model's response
     */
    public CompletableFuture<AgentResult> requestModel(String model, String userMessage, ModelRequestOptions options) {
        // Extract images from options
        List<String> images = null;
        List<Message> history = null;

        if (options != null) {
            history = options.getHistory();
            if (options.getImage() != null) {
                images = List.of(options.getImage());
            } else if (options.getImages() != null && !options.getImages().isEmpty()) {
                images = options.getImages();
            }
        }

        // If we have images, build multimodal message
        if (images != null && !images.isEmpty()) {
            List<Message.ContentPart> contentParts = new ArrayList<>();
            contentParts.add(Message.ContentPart.text(userMessage));
            for (String imageBase64 : images) {
                contentParts.add(Message.ContentPart.pngBase64(imageBase64));
            }

            Message userMsg = Message.builder()
                    .role("user")
                    .content(contentParts)
                    .build();

            List<Message> fullHistory = new ArrayList<>();
            if (history != null) {
                fullHistory.addAll(history);
            }
            fullHistory.add(userMsg);

            return requestModelInternal(model, fullHistory, options);
        }

        // No images - build simple text message
        Message userMsg = Message.builder()
                .role("user")
                .content(userMessage)
                .build();

        List<Message> fullHistory = new ArrayList<>();
        if (history != null) {
            fullHistory.addAll(history);
        }
        fullHistory.add(userMsg);

        return requestModelInternal(model, fullHistory, options);
    }

    /**
     * Sends a direct request to a model with default options.
     *
     * @param model       Model name
     * @param userMessage User message
     * @return CompletableFuture with the model's response
     */
    public CompletableFuture<AgentResult> requestModel(String model, String userMessage) {
        return requestModel(model, userMessage, null);
    }

    /**
     * Internal method for requestModel with pre-built history (including current user message).
     */
    private CompletableFuture<AgentResult> requestModelInternal(String model, List<Message> messagesWithUser,
            ModelRequestOptions options) {
        Agent.AgentBuilder agentBuilder = Agent.builder()
                .id("__direct_" + model + "__")
                .name("Direct " + model)
                .model(model);

        if (options != null) {
            if (options.getInstructions() != null) {
                agentBuilder.instructions(options.getInstructions());
            }
            if (options.getTemperature() != null) {
                agentBuilder.temperature(options.getTemperature());
            }
            if (options.getMaxTokens() != null) {
                agentBuilder.maxTokens(options.getMaxTokens());
            }
            agentBuilder.webSearch(options.isWebSearch());
            agentBuilder.codeInterpreter(options.isCodeInterpreter());

            if (options.getResultClass() != null) {
                agentBuilder.resultClass(options.getResultClass().getSimpleName());
            }
        }

        Agent tempAgent = agentBuilder.build();
        return attemptRequestModelInternalWithRetry(tempAgent, messagesWithUser, options, 0);
    }

    private CompletableFuture<AgentResult> attemptRequestModelInternalWithRetry(
            Agent tempAgent, List<Message> messagesWithUser, ModelRequestOptions options, int attempt) {

        final int MAX_RETRIES = config.getMaxRetries();

        return executeRequestModelInternal(tempAgent, messagesWithUser, options, attempt)
                .thenCompose(parsed -> deserializeModelResponse(parsed, options))
                .handle((result, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(result);
                    }

                    Throwable cause = unwrapException(error);

                    if (!shouldRetry(cause) || attempt >= MAX_RETRIES) {
                        return CompletableFuture.<AgentResult>failedFuture(cause);
                    }

                    long delay = calculateDelay(cause, attempt);
                    logger.warn("Request failed (attempt {}/{}), retrying in {}ms: {}",
                            attempt + 1, MAX_RETRIES, delay, cause.getMessage());

                    return delayAsync(delay)
                            .thenCompose(v -> attemptRequestModelInternalWithRetry(tempAgent, messagesWithUser, options, attempt + 1));
                })
                .thenCompose(f -> f);
    }

    private CompletableFuture<ParsedResponse> executeRequestModelInternal(
            Agent tempAgent, List<Message> messagesWithUser, ModelRequestOptions options, int attempt) {

        if (instanceRouter.isDegradedMode()) {
            return CompletableFuture.failedFuture(
                    new AgentException(AgentException.ErrorCode.NO_INSTANCE_AVAILABLE, "No instances configured"));
        }

        int instanceIdx = instanceRouter.getNextInstanceForModel(tempAgent.getModel());
        Instance instance = instanceRouter.getInstance(instanceIdx);
        InstanceLimiter limiter = getLimiterForInstance(instance);

        // First acquire concurrent stream permit
        if (!limiter.tryAcquire()) {
            return delayAsync(50)
                    .thenCompose(v -> executeRequestModelInternal(tempAgent, messagesWithUser, options, attempt));
        }

        // Then rate limit: non-blocking wait if too soon since last request on this instance
        long rateDelay = limiter.acquireRateSlot();
        if (rateDelay > 0) {
            return delayAsync(rateDelay)
                    .thenCompose(v -> executeRequestModelInternalAfterPermit(tempAgent, messagesWithUser, options, instance, limiter));
        }

        return executeRequestModelInternalAfterPermit(tempAgent, messagesWithUser, options, instance, limiter);
    }

    private CompletableFuture<ParsedResponse> executeRequestModelInternalAfterPermit(
            Agent tempAgent, List<Message> messagesWithUser, ModelRequestOptions options,
            Instance instance, InstanceLimiter limiter) {

        logger.info("-> REQUEST MODEL | Model: {} | Instance: {} | Messages: {}",
                tempAgent.getModel(), instance.getId(), messagesWithUser.size());

        CompletableFuture<ParsedResponse> requestFuture;

        if (ProviderConfig.isAnthropicModel(tempAgent.getModel())) {
            requestFuture = executeClaudeRequestModelInternal(tempAgent, messagesWithUser, options, instance);
        } else {
            requestFuture = executeOpenAIRequestModelInternal(tempAgent, messagesWithUser, options, instance);
        }

        return requestFuture
                .whenComplete((response, error) -> {
                    limiter.release();
                    if (error == null) {
                        logger.info("<- RESPONSE MODEL | Model: {} | Response: {}",
                                tempAgent.getModel(), response);
                    } else {
                        logger.error("<- ERROR MODEL | Model: {} | Error: {}",
                                tempAgent.getModel(), error.getMessage());
                    }
                });
    }

    private CompletableFuture<ParsedResponse> executeOpenAIRequestModelInternal(
            Agent tempAgent, List<Message> messagesWithUser, ModelRequestOptions options, Instance instance) {

        List<Map<String, Object>> input = new ArrayList<>();

        for (Message msg : messagesWithUser) {
            input.addAll(buildOpenAIInputItems(msg));
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", tempAgent.getModel());
        requestBody.put("input", input);

        if (options != null && options.getInstructions() != null) {
            requestBody.put("instructions", options.getInstructions());
        }

        if (options != null && options.getTemperature() != null) {
            requestBody.put("temperature", options.getTemperature());
        }

        Integer maxTokens = (options != null && options.getMaxTokens() != null) ? options.getMaxTokens() : 4096;
        requestBody.put("max_output_tokens", maxTokens);

        // Reasoning configuration
        addOpenAIReasoningConfig(requestBody, options != null ? options.getReasoningEffort() : null);

        List<Object> tools = buildToolsForRequestModel(options);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
        }

        if (options != null) {
            Map<String, Object> textFormat = buildStructuredOutputFormat(options);
            if (textFormat != null) {
                Map<String, Object> text = new HashMap<>();
                text.put("format", textFormat);
                requestBody.put("text", text);
            }
        }

        return httpHelper.postRaw(
                instance,
                ProviderConfig.Endpoint.RESPONSES,
                tempAgent.getModel(),
                requestBody).thenApply(this::extractResponsesContentParsed);
    }

    private CompletableFuture<ParsedResponse> executeClaudeRequestModelInternal(
            Agent tempAgent, List<Message> messagesWithUser, ModelRequestOptions options, Instance instance) {

        List<ClaudeRequest.ClaudeMessage> messages = new ArrayList<>();
        for (Message msg : messagesWithUser) {
            if (!msg.isSystem()) {
                messages.add(buildClaudeMessage(msg));
            }
        }

        String systemPrompt = (options != null) ? options.getInstructions() : null;
        Double temperature = (options != null) ? options.getTemperature() : null;
        int maxTokens = (options != null && options.getMaxTokens() != null) ? options.getMaxTokens() : 4096;

        Class<?> resultClass = (options != null) ? options.getResultClass() : null;

        return claudeAdapter.callClaudeAsync(
                instance,
                tempAgent.getModel(),
                systemPrompt,
                messages,
                temperature,
                maxTokens,
                resultClass,
                null,
                options != null ? options.getReasoningEffort() : null).thenApply(this::parseClaudeResponse);
    }

    @SuppressWarnings("unchecked")
    private List<Object> buildToolsForRequestModel(ModelRequestOptions options) {
        if (options == null) {
            return null;
        }

        List<Object> tools = new ArrayList<>();

        if (options.isWebSearch()) {
            Map<String, Object> webSearchTool = new HashMap<>();
            webSearchTool.put("type", "web_search_preview");
            tools.add(webSearchTool);
        }

        if (options.isCodeInterpreter()) {
            Map<String, Object> codeInterpreterTool = new HashMap<>();
            codeInterpreterTool.put("type", "code_interpreter");
            Map<String, Object> container = new HashMap<>();
            container.put("type", "auto");
            codeInterpreterTool.put("container", container);
            tools.add(codeInterpreterTool);
        }

        return tools.isEmpty() ? null : tools;
    }

    private Map<String, Object> buildStructuredOutputFormat(ModelRequestOptions options) {
        if (options.getResultClass() != null) {
            try {
                Map<String, Object> schema = JsonSchemaGenerator.createFunctionSchemaFromClass(options.getResultClass());
                Map<String, Object> textFormat = new HashMap<>();
                textFormat.put("type", "json_schema");
                textFormat.put("name", options.getResultClass().getSimpleName().toLowerCase() + "_response");
                textFormat.put("schema", schema);
                textFormat.put("strict", true);
                return textFormat;
            } catch (Exception e) {
                logger.warn("Failed to build schema from class: {}", options.getResultClass(), e);
                return null;
            }
        }

        if (options.getResultSchema() != null) {
            Map<String, Object> textFormat = new HashMap<>();
            textFormat.put("type", "json_schema");
            textFormat.put("name", options.getSchemaName() != null ? options.getSchemaName() : "response");
            textFormat.put("schema", options.getResultSchema());
            textFormat.put("strict", true);
            return textFormat;
        }

        return null;
    }

    private CompletableFuture<AgentResult> deserializeModelResponse(ParsedResponse parsed, ModelRequestOptions options) {
        try {
            // If there are function calls, return them in DefaultResult
            if (parsed.hasFunctionCalls()) {
                return CompletableFuture.completedFuture(
                        new DefaultResult(parsed.getTextContent(), parsed.getFunctionCalls()));
            }

            String jsonResponse = parsed.getTextContent();

            if (options == null || options.getResultClass() == null) {
                return CompletableFuture.completedFuture(new DefaultResult(jsonResponse));
            }

            AgentResult result = (AgentResult) objectMapper.readValue(jsonResponse, options.getResultClass());
            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            return CompletableFuture.failedFuture(new AgentException(
                    AgentException.ErrorCode.REQUEST_FAILED,
                    "Failed to deserialize response: " + e.getMessage(), e));
        }
    }

    // ==================== INTERNAL FLOW ====================

    private CompletableFuture<AgentResult> attemptRequestWithRetry(
            Agent agent, String userMessage, List<Message> history, int attempt) {

        final int MAX_RETRIES = config.getMaxRetries();

        return executeRequest(agent, userMessage, history, attempt)
                .thenCompose(parsed -> deserializeResponse(agent, parsed))
                .handle((result, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(result);
                    }

                    Throwable cause = unwrapException(error);

                    // Don't retry certain errors
                    if (!shouldRetry(cause) || attempt >= MAX_RETRIES) {
                        return CompletableFuture.<AgentResult>failedFuture(cause);
                    }

                    long delay = calculateDelay(cause, attempt);
                    logger.warn("Request failed (attempt {}/{}), retrying in {}ms: {}",
                            attempt + 1, MAX_RETRIES, delay, cause.getMessage());

                    return delayAsync(delay)
                            .thenCompose(v -> attemptRequestWithRetry(agent, userMessage, history, attempt + 1));
                })
                .thenCompose(f -> f);
    }

    private CompletableFuture<ParsedResponse> executeRequest(
            Agent agent, String userMessage, List<Message> history, int attempt) {

        if (instanceRouter.isDegradedMode()) {
            return CompletableFuture.failedFuture(
                    new AgentException(AgentException.ErrorCode.NO_INSTANCE_AVAILABLE, "No instances configured"));
        }

        int instanceIdx = instanceRouter.getNextInstanceForModel(agent.getModel());
        Instance instance = instanceRouter.getInstance(instanceIdx);
        InstanceLimiter limiter = getLimiterForInstance(instance);

        // First acquire concurrent stream permit
        if (!limiter.tryAcquire()) {
            logger.debug("⏳ No permit for {} - waiting", instance.getId());
            return delayAsync(50)
                    .thenCompose(v -> executeRequest(agent, userMessage, history, attempt));
        }

        // Then rate limit: non-blocking wait if too soon since last request on this instance
        long rateDelay = limiter.acquireRateSlot();
        if (rateDelay > 0) {
            return delayAsync(rateDelay)
                    .thenCompose(v -> executeRequestAfterPermit(agent, userMessage, history, instance, limiter));
        }

        return executeRequestAfterPermit(agent, userMessage, history, instance, limiter);
    }

    private CompletableFuture<ParsedResponse> executeRequestAfterPermit(
            Agent agent, String userMessage, List<Message> history,
            Instance instance, InstanceLimiter limiter) {

        String msgPreview = userMessage != null && userMessage.length() > 200
                ? userMessage.substring(0, 200) + "..." : userMessage;
        logger.info("→ REQUEST START [V2] | Agent: {} | Model: {} | Instance: {} | Input: {}",
                agent.getName(), agent.getModel(), instance.getId(), msgPreview);

        CompletableFuture<ParsedResponse> requestFuture;

        if (ProviderConfig.isAnthropicModel(agent.getModel())) {
            requestFuture = executeClaudeRequest(agent, userMessage, history, instance);
        } else {
            requestFuture = executeOpenAIRequest(agent, userMessage, history, instance);
        }

        return requestFuture
                .whenComplete((response, error) -> {
                    limiter.release();
                    if (error == null) {
                        logger.info("← RESPONSE END [V2] | Agent: {} | Response: {}",
                                agent.getName(), response);
                    } else {
                        logger.error("← RESPONSE ERROR [V2] | Agent: {} | Error: {}",
                                agent.getName(), error.getMessage());
                    }
                });
    }

    // ==================== OPENAI RESPONSES API ====================

    private CompletableFuture<ParsedResponse> executeOpenAIRequest(
            Agent agent, String userMessage, List<Message> history, Instance instance) {

        // Build input array for Responses API
        // Format: array of messages with role and content (can be multimodal)
        List<Map<String, Object>> input = new ArrayList<>();

        // Add history first
        if (history != null) {
            for (Message msg : history) {
                input.addAll(buildOpenAIInputItems(msg));
            }
        }

        // Add current user message (null on subsequent autonomous loop iterations)
        if (userMessage != null) {
            Map<String, Object> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            input.add(userMsg);
        }

        // Build request body for Responses API
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", agent.getModel());
        requestBody.put("input", input);

        // System instructions go in separate field
        if (agent.getInstructions() != null && !agent.getInstructions().isEmpty()) {
            requestBody.put("instructions", agent.getInstructions());
        }

        if (agent.getTemperature() != null) {
            requestBody.put("temperature", agent.getTemperature());
        }

        // Responses API uses max_output_tokens
        if (agent.getMaxTokens() != null) {
            requestBody.put("max_output_tokens", agent.getMaxTokens());
        } else {
            requestBody.put("max_output_tokens", 4096);
        }

        // Reasoning configuration
        addOpenAIReasoningConfig(requestBody, agent.getReasoningEffort());

        // Add tools if configured
        List<Object> tools = buildOpenAIToolsForRequest(agent);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
            logger.debug("Added {} tools to Responses API request for agent {}", tools.size(), agent.getId());
        }

        // Add structured output (text.format) if configured
        if (agent.getResultClass() != null && !agent.getResultClass().isEmpty()) {
            String fullClassName = config.resolveResultClassName(agent.getResultClass());
            if (fullClassName != null) {
                try {
                    Class<?> resultClass = Class.forName(fullClassName);
                    Map<String, Object> schema = JsonSchemaGenerator.createFunctionSchemaFromClass(resultClass);

                    // OpenAI Responses API format: text.format.{type, name, schema, strict}
                    // name must be at format level, not inside json_schema
                    Map<String, Object> textFormat = new HashMap<>();
                    textFormat.put("type", "json_schema");
                    textFormat.put("name", resultClass.getSimpleName().toLowerCase() + "_response");
                    textFormat.put("schema", schema);
                    textFormat.put("strict", true);

                    Map<String, Object> text = new HashMap<>();
                    text.put("format", textFormat);
                    requestBody.put("text", text);
                } catch (ClassNotFoundException e) {
                    logger.warn("Result class not found: {} (resolved: {})", agent.getResultClass(), fullClassName);
                }
            }
        }

        return httpHelper.postRaw(
                instance,
                ProviderConfig.Endpoint.RESPONSES,
                agent.getModel(),
                requestBody).thenApply(this::extractResponsesContentParsed);
    }

    /**
     * Adds reasoning configuration to an OpenAI Responses API request body.
     * If null or "none" → don't send reasoning param (model default, no reasoning for non-reasoning models).
     * "enabled" → "medium". "low"/"medium"/"high" → sent as-is.
     *
     * @param requestBody     The request body map to add reasoning config to
     * @param reasoningEffort The reasoning effort level (null, "none", "low", "medium", "high", "enabled")
     */
    private void addOpenAIReasoningConfig(Map<String, Object> requestBody, String reasoningEffort) {
        // null or blank → don't send reasoning param at all → model uses its default
        if (reasoningEffort == null || reasoningEffort.isBlank()) {
            return;
        }
        // "enabled" maps to "medium", everything else ("none", "low", "medium", "high") sent as-is
        String effort = "enabled".equalsIgnoreCase(reasoningEffort) ? "medium" : reasoningEffort.toLowerCase();
        Map<String, Object> reasoning = new HashMap<>();
        reasoning.put("effort", effort);
        requestBody.put("reasoning", reasoning);
    }

    /**
     * Extracts the text content from a Responses API response as a simple string.
     * This is a legacy method that flattens function calls to text.
     *
     * @deprecated Use {@link #extractResponsesContentParsed(String)} for structured function call access
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    private String extractResponsesContent(String jsonResponse) {
        ParsedResponse parsed = extractResponsesContentParsed(jsonResponse);
        if (parsed.hasFunctionCalls()) {
            // Legacy behavior: return text description of function calls
            StringBuilder sb = new StringBuilder();
            if (parsed.getTextContent() != null && !parsed.getTextContent().isEmpty()) {
                sb.append(parsed.getTextContent());
            }
            for (FunctionCall call : parsed.getFunctionCalls()) {
                sb.append("[Function call: ").append(call.getName())
                  .append("(").append(call.getArguments()).append(")] ");
            }
            return sb.toString().trim();
        }
        return parsed.getTextContent();
    }

    /**
     * Extracts content and function calls from a Responses API response.
     * Returns a ParsedResponse containing both text content and structured FunctionCall objects.
     */
    @SuppressWarnings("unchecked")
    private ParsedResponse extractResponsesContentParsed(String jsonResponse) {
        try {
            Map<String, Object> response = objectMapper.readValue(jsonResponse,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });

            // Responses API returns output array
            List<Map<String, Object>> output = (List<Map<String, Object>>) response.get("output");
            if (output == null || output.isEmpty()) {
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "No output in response");
            }

            StringBuilder textContent = new StringBuilder();
            List<FunctionCall> functionCalls = new ArrayList<>();
            boolean hasReasoning = false;

            // Process all output items
            for (Map<String, Object> item : output) {
                String type = (String) item.get("type");

                if ("message".equals(type)) {
                    // Extract text from message
                    List<Map<String, Object>> content = (List<Map<String, Object>>) item.get("content");
                    if (content != null) {
                        for (Map<String, Object> contentItem : content) {
                            String contentType = (String) contentItem.get("type");
                            if ("output_text".equals(contentType) || "text".equals(contentType)) {
                                String text = (String) contentItem.get("text");
                                if (text != null) {
                                    textContent.append(text);
                                }
                            }
                        }
                    }
                } else if ("function_call".equals(type)) {
                    // Model wants to call a function - extract as structured FunctionCall
                    String callId = (String) item.get("call_id");
                    String name = (String) item.get("name");
                    String args = item.get("arguments") != null ? item.get("arguments").toString() : "{}";

                    FunctionCall call = FunctionCall.builder()
                            .id(callId)
                            .name(name)
                            .arguments(args)
                            .build();
                    functionCalls.add(call);
                    logger.debug("Parsed function call: {} with args: {}", name, args);
                } else if ("reasoning".equals(type)) {
                    // Reasoning block — model used reasoning tokens. Skip content, just flag it.
                    hasReasoning = true;
                    logger.debug("Response contains reasoning block (skipped for output)");
                }
            }

            // If we have function calls, return them (with or without text)
            if (!functionCalls.isEmpty()) {
                String text = textContent.length() > 0 ? textContent.toString() : null;
                return ParsedResponse.of(text, functionCalls);
            }

            // Return text content if available
            if (textContent.length() > 0) {
                return ParsedResponse.ofText(textContent.toString());
            }

            // Last fallback: try to extract any text from first output
            Map<String, Object> firstOutput = output.get(0);
            if (firstOutput.containsKey("content")) {
                List<Map<String, Object>> content = (List<Map<String, Object>>) firstOutput.get("content");
                if (content != null && !content.isEmpty()) {
                    Object text = content.get(0).get("text");
                    if (text != null) {
                        return ParsedResponse.ofText(text.toString());
                    }
                }
            }

            // Debug: log the response structure
            List<Object> outputTypes = output.stream()
                    .map(o -> o.get("type"))
                    .collect(java.util.stream.Collectors.toList());

            if (hasReasoning) {
                logger.error("Model returned only reasoning with no text output. "
                        + "The model likely exhausted its token budget on reasoning. "
                        + "Consider setting reasoningEffort to 'none' or increasing maxTokens.");
            }

            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "No text content in response. Output contains: " + outputTypes
                    + (hasReasoning ? " (model used all tokens for reasoning, none left for output)" : ""));

        } catch (Exception e) {
            if (e instanceof AgentException) {
                throw (AgentException) e;
            }
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Failed to parse Responses API response: " + e.getMessage(), e);
        }
    }

    // ==================== CLAUDE MESSAGES API ====================

    /**
     * Builds a Claude message from a Message object.
     * Handles both text-only and multimodal (with images) messages.
     */
    private ClaudeRequest.ClaudeMessage buildClaudeMessage(Message msg) {
        // Tool result → user message with tool_result content block
        if (msg.isToolResult()) {
            List<ClaudeRequest.ClaudeContentBlock> blocks = List.of(
                    ClaudeRequest.ClaudeContentBlock.toolResult(msg.getToolCallId(), msg.getContent()));
            return ClaudeRequest.ClaudeMessage.builder()
                    .role("user")
                    .contentBlocks(blocks)
                    .build();
        }

        // Assistant with tool calls → assistant message with tool_use content blocks
        if (msg.isAssistant() && msg.hasToolCalls()) {
            List<ClaudeRequest.ClaudeContentBlock> blocks = new ArrayList<>();
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                blocks.add(ClaudeRequest.ClaudeContentBlock.text(msg.getContent()));
            }
            for (FunctionCall call : msg.getFunctionCalls()) {
                blocks.add(ClaudeRequest.ClaudeContentBlock.toolUse(
                        call.getId(), call.getName(), call.getArgumentsAsMap()));
            }
            return ClaudeRequest.ClaudeMessage.builder()
                    .role("assistant")
                    .contentBlocks(blocks)
                    .build();
        }

        if (msg.isMultimodal()) {
            // Build multimodal content blocks for Claude
            List<ClaudeRequest.ClaudeContentBlock> contentBlocks = new ArrayList<>();
            for (Message.ContentPart part : msg.getContentParts()) {
                switch (part.getType()) {
                    case "text":
                        contentBlocks.add(ClaudeRequest.ClaudeContentBlock.text(part.getText()));
                        break;
                    case "image_url":
                        contentBlocks.add(ClaudeRequest.ClaudeContentBlock.imageUrl(part.getImageUrl()));
                        break;
                    case "image_base64":
                        contentBlocks.add(ClaudeRequest.ClaudeContentBlock.imageBase64(
                                part.getImageBase64(), part.getMediaType()));
                        break;
                    default:
                        logger.warn("Unknown content part type for Claude: {}", part.getType());
                }
            }
            return ClaudeRequest.ClaudeMessage.builder()
                    .role(msg.getRole())
                    .contentBlocks(contentBlocks)
                    .build();
        } else {
            // Simple text content
            return ClaudeRequest.ClaudeMessage.builder()
                    .role(msg.getRole())
                    .content(msg.getContent())
                    .build();
        }
    }

    private CompletableFuture<ParsedResponse> executeClaudeRequest(
            Agent agent, String userMessage, List<Message> history, Instance instance) {

        // Build messages
        List<ClaudeRequest.ClaudeMessage> messages = new ArrayList<>();
        if (history != null) {
            for (Message msg : history) {
                if (!msg.isSystem()) { // System goes in separate field
                    messages.add(buildClaudeMessage(msg));
                }
            }
        }
        // Add current user message (null on subsequent autonomous loop iterations)
        if (userMessage != null) {
            messages.add(ClaudeRequest.ClaudeMessage.builder()
                    .role("user")
                    .content(userMessage)
                    .build());
        }

        // Get tools
        List<ClaudeRequest.ClaudeTool> tools = ToolBuilder.buildClaudeTools(agent);

        // Get result class if configured
        Class<?> resultClass = null;
        if (agent.getResultClass() != null && !agent.getResultClass().isEmpty()) {
            String fullClassName = config.resolveResultClassName(agent.getResultClass());
            if (fullClassName != null) {
                try {
                    resultClass = Class.forName(fullClassName);
                } catch (ClassNotFoundException e) {
                    logger.warn("Result class not found: {} (resolved: {})", agent.getResultClass(), fullClassName);
                }
            }
        }

        return claudeAdapter.callClaudeAsync(
                instance,
                agent.getModel(),
                agent.getInstructions(),
                messages,
                agent.getTemperature(),
                agent.getMaxTokens() != null ? agent.getMaxTokens() : 4096,
                resultClass,
                tools,
                agent.getReasoningEffort()).thenApply(this::parseClaudeResponse);
    }

    // ==================== RESPONSE HANDLING ====================

    /**
     * Deserializes a parsed response into an AgentResult.
     * If the response contains function calls, they are included in the result.
     */
    private CompletableFuture<AgentResult> deserializeResponse(Agent agent, ParsedResponse parsed) {
        try {
            String jsonResponse = parsed.getTextContent();
            List<FunctionCall> functionCalls = parsed.hasFunctionCalls() ? parsed.getFunctionCalls() : null;

            // No resultClass → return DefaultResult
            if (agent.getResultClass() == null || agent.getResultClass().isEmpty()) {
                DefaultResult defaultResult = new DefaultResult(jsonResponse);
                if (functionCalls != null) defaultResult.setFunctionCalls(functionCalls);
                return CompletableFuture.completedFuture(defaultResult);
            }

            String fullClassName = config.resolveResultClassName(agent.getResultClass());
            if (fullClassName == null) {
                logger.warn("Cannot resolve result class '{}' for agent {} - use FQCN or configure agentResultClassPackage",
                        agent.getResultClass(), agent.getId());
                DefaultResult defaultResult = new DefaultResult(jsonResponse);
                if (functionCalls != null) defaultResult.setFunctionCalls(functionCalls);
                return CompletableFuture.completedFuture(defaultResult);
            }

            // Parse text content as resultClass
            Class<?> resultClass = Class.forName(fullClassName);
            AgentResult result;
            if (jsonResponse != null && !jsonResponse.isBlank()) {
                result = (AgentResult) objectMapper.readValue(jsonResponse, resultClass);
            } else {
                result = (AgentResult) resultClass.getDeclaredConstructor().newInstance();
            }

            // Attach function calls if present (all AgentResult subclasses support this now)
            if (functionCalls != null && !functionCalls.isEmpty()) {
                result.setFunctionCalls(functionCalls);
            }

            return CompletableFuture.completedFuture(result);

        } catch (ClassNotFoundException e) {
            return CompletableFuture.failedFuture(new AgentException(
                    AgentException.ErrorCode.INVALID_CONFIGURATION,
                    "Result class not found: " + agent.getResultClass() + " (resolved: " + config.resolveResultClassName(agent.getResultClass()) + ")"));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new AgentException(
                    AgentException.ErrorCode.REQUEST_FAILED,
                    "Failed to deserialize response: " + e.getMessage(), e));
        }
    }

    /**
     * @deprecated Use {@link #deserializeResponse(Agent, ParsedResponse)} instead
     */
    @Deprecated
    private CompletableFuture<AgentResult> deserializeResponse(Agent agent, String jsonResponse) {
        return deserializeResponse(agent, ParsedResponse.ofText(jsonResponse));
    }

    // ==================== UTILITY METHODS ====================

    private InstanceLimiter getLimiterForInstance(Instance instance) {
        return instanceLimiters.computeIfAbsent(instance.getId(),
                id -> new InstanceLimiter(id, config.getMaxConcurrentStreamsPerInstance(), config.getRequestsPerSecond()));
    }

    private CompletableFuture<Void> delayAsync(long millis) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        DELAY_SCHEDULER.schedule(() -> future.complete(null), millis, TimeUnit.MILLISECONDS);
        return future;
    }

    private Throwable unwrapException(Throwable error) {
        if (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private boolean shouldRetry(Throwable e) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // Don't retry content filter or 4xx errors (except rate limits)
        if (message.contains("content_filter") || message.contains("content filter")) {
            return false;
        }
        if ((message.contains("400") || message.contains("401") || message.contains("403"))
                && !message.contains("429")) {
            return false;
        }

        return true;
    }

    private long calculateDelay(Throwable e, int attempt) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        if (message.contains("502")) {
            return config.getError502DelayMs();
        }

        return config.getRetryBaseDelayMs() * (long) Math.pow(2, attempt);
    }

    /**
     * Builds an OpenAI Responses API input message from a Message object.
     * Handles both text-only and multimodal (with images) messages.
     */
    /**
     * Builds one or more OpenAI Responses API input items from a Message.
     * Returns multiple items for assistant messages with tool calls (function_call items)
     * and for tool result messages (function_call_output items).
     * For normal text/multimodal messages, returns a single-item list.
     */
    private List<Map<String, Object>> buildOpenAIInputItems(Message msg) {
        List<Map<String, Object>> items = new ArrayList<>();

        // Tool result → function_call_output item
        if (msg.isToolResult()) {
            Map<String, Object> item = new HashMap<>();
            item.put("type", "function_call_output");
            item.put("call_id", msg.getToolCallId());
            item.put("output", msg.getContent());
            items.add(item);
            return items;
        }

        // Assistant with tool calls → function_call items
        if (msg.isAssistant() && msg.hasToolCalls()) {
            for (FunctionCall call : msg.getFunctionCalls()) {
                Map<String, Object> callItem = new HashMap<>();
                callItem.put("type", "function_call");
                callItem.put("call_id", call.getId());
                callItem.put("name", call.getName());
                callItem.put("arguments", call.getArguments());
                items.add(callItem);
            }
            return items;
        }

        // Default: single message item
        items.add(buildOpenAIInputMessage(msg));
        return items;
    }

    private Map<String, Object> buildOpenAIInputMessage(Message msg) {
        Map<String, Object> inputMsg = new HashMap<>();
        inputMsg.put("role", msg.getRole());

        if (msg.isMultimodal()) {
            // Build multimodal content array for OpenAI
            List<Map<String, Object>> contentParts = new ArrayList<>();
            for (Message.ContentPart part : msg.getContentParts()) {
                Map<String, Object> contentPart = new HashMap<>();

                switch (part.getType()) {
                    case "text":
                        contentPart.put("type", "input_text");
                        contentPart.put("text", part.getText());
                        break;
                    case "image_url":
                        contentPart.put("type", "input_image");
                        contentPart.put("image_url", part.getImageUrl());
                        break;
                    case "image_base64":
                        contentPart.put("type", "input_image");
                        // OpenAI expects data URL format for base64
                        String dataUrl = "data:" + part.getMediaType() + ";base64," + part.getImageBase64();
                        contentPart.put("image_url", dataUrl);
                        break;
                    default:
                        logger.warn("Unknown content part type: {}", part.getType());
                        continue;
                }
                contentParts.add(contentPart);
            }
            inputMsg.put("content", contentParts);
        } else {
            // Simple text content
            inputMsg.put("content", msg.getContent());
        }

        return inputMsg;
    }

    /**
     * Builds tools list for OpenAI Responses API format.
     */
    @SuppressWarnings("unchecked")
    private List<Object> buildOpenAIToolsForRequest(Agent agent) {
        if (!ToolBuilder.hasTools(agent)) {
            return null;
        }

        List<Object> tools = new ArrayList<>();

        // Web search tool
        if (Boolean.TRUE.equals(agent.getWebSearch())) {
            Map<String, Object> webSearchTool = new HashMap<>();
            webSearchTool.put("type", "web_search_preview");
            tools.add(webSearchTool);
            logger.debug("Added web_search_preview tool for agent {}", agent.getId());
        }

        // Code interpreter tool - requires container config for OpenAI
        if (Boolean.TRUE.equals(agent.getCodeInterpreter())) {
            Map<String, Object> codeInterpreterTool = new HashMap<>();
            codeInterpreterTool.put("type", "code_interpreter");
            // OpenAI Responses API requires a container specification
            Map<String, Object> container = new HashMap<>();
            container.put("type", "auto");  // Let OpenAI choose the container
            codeInterpreterTool.put("container", container);
            tools.add(codeInterpreterTool);
            logger.debug("Added code_interpreter tool for agent {}", agent.getId());
        }

        // File search tool (from retrieval flag)
        if (Boolean.TRUE.equals(agent.getRetrieval())) {
            Map<String, Object> fileSearchTool = new HashMap<>();
            fileSearchTool.put("type", "file_search");
            tools.add(fileSearchTool);
            logger.debug("Added file_search tool for agent {}", agent.getId());
        }

        // Custom functions - Responses API format (name/description/parameters at root level)
        if (agent.getFunctions() != null && !agent.getFunctions().isEmpty()) {
            String parameterClassPackage = config.getFunctionParameterClassPackage();
            for (var func : agent.getFunctions()) {
                Map<String, Object> functionTool = new HashMap<>();
                functionTool.put("type", "function");
                functionTool.put("name", func.getName());
                if (func.getDescription() != null) {
                    functionTool.put("description", func.getDescription());
                }

                // Build parameters schema using ToolBuilder (supports FQCN and simple names)
                Map<String, Object> parameters = ToolBuilder.buildFunctionSchema(func, parameterClassPackage);
                functionTool.put("parameters", parameters);

                tools.add(functionTool);
                logger.debug("Added function tool '{}' for agent {} with schema: {}", func.getName(), agent.getId(), parameters);
            }
        }

        return tools.isEmpty() ? null : tools;
    }

    // ==================== CHAT COMPLETIONS ====================

    /**
     * Executes a chat completion request (string response).
     *
     * @param model       Model name
     * @param messages    Chat messages
     * @param temperature Temperature (optional)
     * @return Response content
     */
    public CompletableFuture<String> requestChatCompletion(String model, List<ChatMessage> messages,
            Double temperature) {
        return attemptChatCompletion(model, messages, temperature, 0);
    }

    /**
     * Executes a chat completion with structured output.
     *
     * @param model       Model name
     * @param messages    Chat messages
     * @param temperature Temperature (optional)
     * @param resultClass Result class for typed response (null = DefaultResult)
     * @return Typed result
     */
    @SuppressWarnings("unchecked")
    public <T extends AgentResult> CompletableFuture<T> chatCompletion(String model,
            List<ChatMessage> messages,
            Double temperature,
            Class<T> resultClass) {
        return attemptChatCompletionStructured(model, messages, temperature, resultClass, 0);
    }

    /**
     * Executes a chat completion without structured output.
     */
    public CompletableFuture<DefaultResult> chatCompletion(String model, List<ChatMessage> messages,
            Double temperature) {
        return chatCompletion(model, messages, temperature, DefaultResult.class);
    }

    /**
     * Executes a stateless chat completion with structured output by class name.
     * Resolves the class from agentResultClassPackage + resultClassName.
     *
     * @param model                   Model name (e.g., "gpt-4o", "claude-sonnet-4-5")
     * @param messages                List of chat messages
     * @param temperature             Temperature for response generation
     * @param resultClassName         Simple class name (e.g., "WeatherResult")
     * @param agentResultClassPackage Package containing the result class
     * @return CompletableFuture with typed result
     */
    @SuppressWarnings("unchecked")
    public <T extends AgentResult> CompletableFuture<T> chatCompletion(
            String model,
            List<ChatMessage> messages,
            Double temperature,
            String resultClassName,
            String agentResultClassPackage) {

        if (agentResultClassPackage == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("agentResultClassPackage not configured"));
        }

        try {
            String fullClassName = agentResultClassPackage + "." + resultClassName;
            Class<T> resultClass = (Class<T>) Class.forName(fullClassName);
            return chatCompletion(model, messages, temperature, resultClass);
        } catch (ClassNotFoundException e) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Result class not found: " + resultClassName, e));
        }
    }

    private CompletableFuture<String> attemptChatCompletion(String model, List<ChatMessage> messages,
            Double temperature, int attemptNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIdx = instanceRouter.getNextInstanceForModel(model);
                Instance instance = instanceRouter.getInstance(instanceIdx);

                // Check if Anthropic model
                if (ProviderConfig.isAnthropicModel(model)) {
                    return executeChatCompletionClaude(model, messages, temperature, instance);
                }

                // OpenAI
                return executeChatCompletionOpenAI(model, messages, temperature, null, instance);

            } catch (Exception e) {
                return handleChatCompletionException(model, messages, temperature, attemptNumber, e);
            }
        }, httpHelper.getExecutor());
    }

    @SuppressWarnings("unchecked")
    private <T extends AgentResult> CompletableFuture<T> attemptChatCompletionStructured(
            String model, List<ChatMessage> messages, Double temperature,
            Class<T> resultClass, int attemptNumber) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIdx = instanceRouter.getNextInstanceForModel(model);
                Instance instance = instanceRouter.getInstance(instanceIdx);

                String jsonResponse;

                if (ProviderConfig.isAnthropicModel(model)) {
                    jsonResponse = executeChatCompletionClaudeStructured(model, messages, temperature,
                            instance, resultClass);
                } else {
                    ResponseFormat format = null;
                    if (resultClass != null && resultClass != DefaultResult.class) {
                        format = JsonSchemaGenerator.createResponseFormatFromClass(resultClass);
                    }
                    jsonResponse = executeChatCompletionOpenAI(model, messages, temperature, format, instance);
                }

                // Return DefaultResult if no class specified
                if (resultClass == null || resultClass == DefaultResult.class) {
                    return (T) new DefaultResult(jsonResponse);
                }

                // Fix Map fields from Claude responses
                if (ProviderConfig.isAnthropicModel(model)) {
                    jsonResponse = JsonSchemaGenerator.fixMapFieldsFromResponse(jsonResponse, resultClass);
                }

                return objectMapper.readValue(jsonResponse, resultClass);

            } catch (Exception e) {
                return handleChatCompletionStructuredException(model, messages, temperature,
                        resultClass, attemptNumber, e);
            }
        }, httpHelper.getExecutor());
    }

    private String executeChatCompletionOpenAI(String model, List<ChatMessage> messages,
            Double temperature, ResponseFormat format,
            Instance instance) {
        // LOG REQUEST START
        String messagesPreview = messages.size() + " messages";
        logger.info("-> CHAT START | Messages: {} | Temp: {} | Model: {} | Instance: {}",
                messagesPreview, temperature, model, instance.getId());

        ChatRequest.ChatRequestBuilder requestBuilder = ChatRequest.builder()
                .model(model)
                .messages(messages);

        if (temperature != null) {
            requestBuilder.temperature(temperature);
        }
        if (format != null) {
            requestBuilder.responseFormat(format);
        }

        Chat chatResponse = httpHelper.post(instance, ProviderConfig.Endpoint.CHAT_COMPLETIONS,
                model, requestBuilder.build(), Chat.class).join();

        if (chatResponse.getChoices() == null || chatResponse.getChoices().isEmpty()) {
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "No choices returned in chat completion");
        }

        String response = chatResponse.getChoices().get(0).getMessage().getContent();

        // LOG RESPONSE END
        String responsePreview = response.length() > 200 ? response.substring(0, 200) + "..." : response;
        logger.info("<- CHAT END | Response: {} | Model: {} | Instance: {}",
                responsePreview, model, instance.getId());

        return response;
    }

    private String executeChatCompletionClaude(String model, List<ChatMessage> messages,
            Double temperature, Instance instance) {
        // LOG REQUEST START
        String messagesPreview = messages.size() + " messages";
        logger.info("-> CHAT START | Messages: {} | Temp: {} | Model: {} | Instance: {}",
                messagesPreview, temperature, model, instance.getId());

        List<ClaudeRequest.ClaudeMessage> claudeMessages = claudeAdapter.convertToClaude(messages);
        String systemPrompt = claudeAdapter.extractSystemPrompt(messages);

        ClaudeResponse claudeResponse = claudeAdapter.callClaude(instance, model, systemPrompt,
                claudeMessages, temperature, 4096, null);

        String response = claudeResponse.getTextContent();

        // LOG RESPONSE END
        String responsePreview = response.length() > 200 ? response.substring(0, 200) + "..." : response;
        logger.info("<- CHAT END | Response: {} | Model: {} | Instance: {}",
                responsePreview, model, instance.getId());

        return response;
    }

    private <T extends AgentResult> String executeChatCompletionClaudeStructured(
            String model, List<ChatMessage> messages, Double temperature,
            Instance instance, Class<T> resultClass) {

        // LOG REQUEST START
        String messagesPreview = messages.size() + " messages";
        logger.info("-> CHAT STRUCTURED START | Messages: {} | Temp: {} | ResultClass: {} | Model: {} | Instance: {}",
                messagesPreview, temperature, resultClass != null ? resultClass.getSimpleName() : "null",
                model, instance.getId());

        List<ClaudeRequest.ClaudeMessage> claudeMessages = claudeAdapter.convertToClaude(messages);
        String systemPrompt = claudeAdapter.extractSystemPrompt(messages);

        ClaudeResponse claudeResponse = claudeAdapter.callClaude(instance, model, systemPrompt,
                claudeMessages, temperature, 4096, resultClass);

        String response = claudeResponse.getTextContent();

        // LOG RESPONSE END
        String responsePreview = response.length() > 200 ? response.substring(0, 200) + "..." : response;
        logger.info("<- CHAT STRUCTURED END | Response: {} | Model: {} | Instance: {}",
                responsePreview, model, instance.getId());

        return response;
    }

    private String handleChatCompletionException(String model, List<ChatMessage> messages,
            Double temperature, int attemptNumber, Exception e) {
        if (shouldRetryChatCompletion(e, attemptNumber)) {
            long delay = calculateChatRetryDelay(e, attemptNumber);
            logger.warn("Chat completion failed (attempt {}/{}), retrying in {}ms: {}",
                    attemptNumber + 1, config.getMaxRetries(), delay, e.getMessage());

            try {
                Thread.sleep(delay);
                return attemptChatCompletion(model, messages, temperature, attemptNumber + 1).join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Retry interrupted", ie);
            }
        }

        throw translateChatException(e);
    }

    @SuppressWarnings("unchecked")
    private <T extends AgentResult> T handleChatCompletionStructuredException(
            String model, List<ChatMessage> messages, Double temperature,
            Class<T> resultClass, int attemptNumber, Exception e) {

        if (shouldRetryChatCompletion(e, attemptNumber)) {
            long delay = calculateChatRetryDelay(e, attemptNumber);
            logger.warn("Structured chat completion failed (attempt {}/{}), retrying in {}ms: {}",
                    attemptNumber + 1, config.getMaxRetries(), delay, e.getMessage());

            try {
                Thread.sleep(delay);
                return attemptChatCompletionStructured(model, messages, temperature,
                        resultClass, attemptNumber + 1).join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Retry interrupted", ie);
            }
        }

        throw translateChatException(e);
    }

    private boolean shouldRetryChatCompletion(Exception e, int attemptNumber) {
        if (attemptNumber >= config.getMaxRetries()) {
            logger.error("Max retries ({}) reached, not retrying", config.getMaxRetries());
            return false;
        }

        String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // Don't retry content filter errors
        if (ContentFilterException.isContentFilterError(errorMessage)) {
            logger.error("Content filter error detected, not retrying");
            return false;
        }

        // Don't retry 4xx errors (except 429 rate limit)
        if (is4xxError(errorMessage) && !isRateLimitError(errorMessage)) {
            logger.error("Client error (4xx) detected, not retrying: {}", e.getMessage());
            return false;
        }

        // Retry rate limits, timeouts, 5xx errors
        return isRateLimitError(errorMessage) || e instanceof RequestTimeoutException ||
                is5xxError(errorMessage);
    }

    private long calculateChatRetryDelay(Exception e, int attemptNumber) {
        String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // Rate limit: use configured rate limit delay
        if (isRateLimitError(errorMessage)) {
            return config.getRateLimitDelayMs();
        }

        // Timeout: no additional delay
        if (e instanceof RequestTimeoutException) {
            return 0;
        }

        // Exponential backoff for other errors
        return config.getRetryBaseDelayMs() * (long) Math.pow(2, attemptNumber);
    }

    private boolean isRateLimitError(String errorMessage) {
        return errorMessage.contains("rate_limit") || errorMessage.contains("429") ||
                errorMessage.contains("rate limit");
    }

    private boolean is4xxError(String errorMessage) {
        return errorMessage.contains("400") || errorMessage.contains("401") ||
                errorMessage.contains("403") || errorMessage.contains("404") ||
                errorMessage.contains("422");
    }

    private boolean is5xxError(String errorMessage) {
        return errorMessage.contains("500") || errorMessage.contains("502") ||
                errorMessage.contains("503") || errorMessage.contains("504");
    }

    private RuntimeException translateChatException(Exception e) {
        if (e instanceof AgentException) {
            return (AgentException) e;
        }

        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        if (ContentFilterException.isContentFilterError(message)) {
            return new ContentFilterException(e.getMessage(), e);
        }

        if (isRateLimitError(message)) {
            return new RateLimitException("Rate limit exceeded: " + e.getMessage());
        }

        return new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                "Chat completion failed: " + e.getMessage(), e);
    }

    // ==================== EMBEDDINGS ====================

    /**
     * Generates embeddings for text.
     *
     * @param text  Text to embed
     * @param model Embedding model
     * @return Float array of embeddings
     */
    public CompletableFuture<float[]> generateEmbedding(String text, String model) {
        return attemptEmbedding(text, model, 0);
    }

    /**
     * Generates embeddings using default model (text-embedding-3-small).
     */
    public CompletableFuture<float[]> generateEmbedding(String text) {
        return generateEmbedding(text, "text-embedding-3-small");
    }

    /**
     * Alias for {@link #generateEmbedding(String, String)} for API consistency.
     * Use requestEmbedding/requestModel/requestAgent for a consistent API.
     *
     * @param text  Text to embed
     * @param model Embedding model
     * @return Float array of embeddings
     */
    public CompletableFuture<float[]> requestEmbedding(String text, String model) {
        return generateEmbedding(text, model);
    }

    /**
     * Alias for {@link #generateEmbedding(String)} for API consistency.
     */
    public CompletableFuture<float[]> requestEmbedding(String text) {
        return generateEmbedding(text);
    }

    /**
     * Generates embeddings for multiple texts in a SINGLE batch API call.
     * Much more efficient than calling generateEmbedding() multiple times.
     *
     * @param texts List of texts to embed (max 2048 per OpenAI limits)
     * @param model Embedding model
     * @return List of float arrays (same order as input texts)
     */
    public CompletableFuture<List<float[]>> generateEmbeddingsBatch(List<String> texts, String model) {
        return attemptEmbeddingsBatch(texts, model, 0);
    }

    /**
     * Generates batch embeddings using default model (text-embedding-3-small).
     */
    public CompletableFuture<List<float[]>> generateEmbeddingsBatch(List<String> texts) {
        return generateEmbeddingsBatch(texts, "text-embedding-3-small");
    }

    /**
     * Alias for {@link #generateEmbeddingsBatch(List, String)} for API consistency.
     *
     * @param texts List of texts to embed
     * @param model Embedding model
     * @return List of float arrays
     */
    public CompletableFuture<List<float[]>> requestEmbeddings(List<String> texts, String model) {
        return generateEmbeddingsBatch(texts, model);
    }

    /**
     * Alias for {@link #generateEmbeddingsBatch(List)} for API consistency.
     */
    public CompletableFuture<List<float[]>> requestEmbeddings(List<String> texts) {
        return generateEmbeddingsBatch(texts);
    }

    private CompletableFuture<float[]> attemptEmbedding(String text, String model, int attemptNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeEmbeddingRequest(text, model);
            } catch (Exception e) {
                return handleEmbeddingException(text, model, attemptNumber, e);
            }
        }, httpHelper.getExecutor());
    }

    private CompletableFuture<List<float[]>> attemptEmbeddingsBatch(List<String> texts, String model, int attemptNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeEmbeddingsBatchRequest(texts, model);
            } catch (Exception e) {
                return handleEmbeddingsBatchException(texts, model, attemptNumber, e);
            }
        }, httpHelper.getExecutor());
    }

    private float[] executeEmbeddingRequest(String text, String model) throws Exception {
        int instanceIdx = instanceRouter.getNextInstanceForModel(model);
        Instance instance = instanceRouter.getInstance(instanceIdx);

        // Truncate text for logging
        String textPreview = text.length() > 100 ? text.substring(0, 100) + "..." : text;

        // LOG REQUEST START
        logger.info("-> EMBEDDING START | Input: {} | Model: {} | Instance: {}",
                textPreview, model, instance.getId());

        EmbeddingRequest request = EmbeddingRequest.builder()
                .model(model)
                .input(text)
                .build();

        EmbeddingResponse response = httpHelper.post(instance, ProviderConfig.Endpoint.EMBEDDINGS,
                model, request, EmbeddingResponse.class).join();

        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Empty response from embeddings API");
        }

        List<Double> embedding = response.getData().get(0).getEmbedding();
        float[] result = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            result[i] = embedding.get(i).floatValue();
        }

        // LOG RESPONSE END
        logger.info("<- EMBEDDING END | Dimensions: {} | Model: {} | Instance: {}",
                result.length, model, instance.getId());

        return result;
    }

    private List<float[]> executeEmbeddingsBatchRequest(List<String> texts, String model) throws Exception {
        int instanceIdx = instanceRouter.getNextInstanceForModel(model);
        Instance instance = instanceRouter.getInstance(instanceIdx);

        // LOG REQUEST START
        logger.info("-> EMBEDDING BATCH START | Count: {} | Model: {} | Instance: {}",
                texts.size(), model, instance.getId());

        EmbeddingRequest request = EmbeddingRequest.builder()
                .model(model)
                .input(texts)  // Array of strings!
                .build();

        EmbeddingResponse response = httpHelper.post(instance, ProviderConfig.Endpoint.EMBEDDINGS,
                model, request, EmbeddingResponse.class).join();

        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Empty response from embeddings batch API");
        }

        // Convert all embeddings
        List<float[]> results = new ArrayList<>();
        for (EmbeddingResponse.EmbeddingData embeddingData : response.getData()) {
            List<Double> embedding = embeddingData.getEmbedding();
            float[] result = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                result[i] = embedding.get(i).floatValue();
            }
            results.add(result);
        }

        // LOG RESPONSE END
        logger.info("<- EMBEDDING BATCH END | Count: {} | Dimensions: {} | Model: {} | Instance: {}",
                results.size(), results.isEmpty() ? 0 : results.get(0).length, model, instance.getId());

        return results;
    }

    private float[] handleEmbeddingException(String text, String model, int attemptNumber, Exception e) {
        if (shouldRetryChatCompletion(e, attemptNumber)) {
            long delay = calculateChatRetryDelay(e, attemptNumber);
            logger.warn("Embedding generation failed (attempt {}/{}), retrying in {}ms: {}",
                    attemptNumber + 1, config.getMaxRetries(), delay, e.getMessage());

            try {
                Thread.sleep(delay);
                return attemptEmbedding(text, model, attemptNumber + 1).join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Retry interrupted", ie);
            }
        }

        throw translateChatException(e);
    }

    private List<float[]> handleEmbeddingsBatchException(List<String> texts, String model, int attemptNumber, Exception e) {
        if (shouldRetryChatCompletion(e, attemptNumber)) {
            long delay = calculateChatRetryDelay(e, attemptNumber);
            logger.warn("Batch embedding generation failed (attempt {}/{}), retrying in {}ms: {}",
                    attemptNumber + 1, config.getMaxRetries(), delay, e.getMessage());

            try {
                Thread.sleep(delay);
                return attemptEmbeddingsBatch(texts, model, attemptNumber + 1).join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Retry interrupted", ie);
            }
        }

        throw translateChatException(e);
    }

    // ==================== IMAGE GENERATION ====================

    /**
     * Generates an image using DALL-E.
     *
     * @param prompt  Image description
     * @param model   Model name (e.g., "dall-e-3")
     * @param size    Image size
     * @param quality Image quality
     * @return Base64-encoded image data
     */
    public CompletableFuture<String> generateImage(String prompt, String model, Size size, Quality quality) {
        return attemptImageGeneration(prompt, model, size, quality, 0);
    }

    /**
     * Generates an image with default settings (dall-e-3, 1024x1024, standard quality).
     */
    public CompletableFuture<String> generateImage(String prompt) {
        return generateImage(prompt, "dall-e-3", Size.X1024, Quality.STANDARD);
    }

    /**
     * Edits/transforms an existing image based on a text prompt.
     * For gpt-image-1: Uses image-to-image editing capabilities.
     * For dall-e-2: Supports masked editing with transparent areas.
     * Note: dall-e-3 does not support image editing.
     *
     * @param imageBase64 Base64-encoded input image (PNG format)
     * @param prompt      Text description of desired changes/evolution
     * @param model       Model to use (gpt-image-1 or dall-e-2)
     * @param size        Output image size
     * @param quality     Output quality (STANDARD or HD)
     * @return Base64-encoded edited image
     */
    public CompletableFuture<String> editImage(String imageBase64, String prompt, String model,
            Size size, Quality quality) {
        return attemptImageEdit(imageBase64, prompt, model, size, quality, 0);
    }

    /**
     * Edits an image with default settings (gpt-image-1, 1024x1024, standard quality).
     */
    public CompletableFuture<String> editImage(String imageBase64, String prompt) {
        return editImage(imageBase64, prompt, "gpt-image-1", Size.X1024, Quality.STANDARD);
    }

    private CompletableFuture<String> attemptImageGeneration(String prompt, String model, Size size,
            Quality quality, int attemptNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeImageGenerationRequest(prompt, model, size, quality);
            } catch (Exception e) {
                return handleImageGenerationException(prompt, model, size, quality, attemptNumber, e);
            }
        }, httpHelper.getExecutor());
    }

    private CompletableFuture<String> attemptImageEdit(String imageBase64, String prompt, String model,
            Size size, Quality quality, int attemptNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeImageEditRequest(imageBase64, prompt, model, size, quality);
            } catch (Exception e) {
                return handleImageEditException(imageBase64, prompt, model, size, quality, attemptNumber, e);
            }
        }, httpHelper.getExecutor());
    }

    private String executeImageGenerationRequest(String prompt, String model, Size size, Quality quality)
            throws Exception {
        int instanceIdx = instanceRouter.getNextInstanceForModel(model);
        Instance instance = instanceRouter.getInstance(instanceIdx);

        // Truncate prompt for logging
        String promptPreview = prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt;

        // LOG REQUEST START
        logger.info("-> IMAGE GEN START | Prompt: {} | Size: {} | Quality: {} | Model: {} | Instance: {}",
                promptPreview, size, quality, model, instance.getId());

        // Build request - responseFormat handling varies by model:
        // - DALL-E 3: supports response_format (defaults to URL without it)
        // - gpt-image-1: does NOT support response_format (always returns base64)
        ImageRequest.ImageRequestBuilder requestBuilder = ImageRequest.builder()
                .model(model)
                .prompt(prompt)
                .size(size)
                .quality(quality)
                .n(1);

        // Add response_format for DALL-E 3 (not for gpt-image-1)
        if ("dall-e-3".equals(model) || "dall-e-2".equals(model)) {
            requestBuilder.responseFormat(ImageResponseFormat.B64JSON);
        }

        ImageRequest imageRequest = requestBuilder.build();

        ImageGenerationResponse response = httpHelper.post(instance, ProviderConfig.Endpoint.IMAGES_GENERATIONS,
                model, imageRequest, ImageGenerationResponse.class).join();

        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Image generation returned empty response");
        }

        String imageData = response.getData().get(0).getB64Json();

        // LOG RESPONSE END
        logger.info("<- IMAGE GEN END | ImageDataSize: {} bytes | Model: {} | Instance: {}",
                imageData != null ? imageData.length() : 0, model, instance.getId());

        return imageData;
    }

    private String executeImageEditRequest(String imageBase64, String prompt, String model, Size size, Quality quality)
            throws Exception {
        int instanceIdx = instanceRouter.getNextInstanceForModel(model);
        Instance instance = instanceRouter.getInstance(instanceIdx);

        // Truncate prompt for logging
        String promptPreview = prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt;

        // LOG REQUEST START
        logger.info("-> IMAGE EDIT START | Prompt: {} | InputSize: {} bytes | Size: {} | Quality: {} | Model: {} | Instance: {}",
                promptPreview, imageBase64 != null ? imageBase64.length() : 0, size, quality, model, instance.getId());

        // Build form fields for multipart request
        Map<String, String> formFields = new HashMap<>();
        formFields.put("prompt", prompt);
        formFields.put("model", model);
        formFields.put("n", "1");
        formFields.put("size", size.toString()); // Now returns "1024x1024" directly

        // Add response_format for DALL-E models (gpt-image-1 doesn't support it)
        if ("dall-e-3".equals(model) || "dall-e-2".equals(model)) {
            formFields.put("response_format", "b64_json");
        }

        // Use multipart/form-data to send base64 image
        ImageGenerationResponse response = httpHelper.postMultipartBase64(
                instance,
                ProviderConfig.Endpoint.IMAGES_EDITS,
                imageBase64,
                formFields,
                ImageGenerationResponse.class
        ).join();

        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Image edit returned empty response");
        }

        String imageData = response.getData().get(0).getB64Json();

        // LOG RESPONSE END
        logger.info("<- IMAGE EDIT END | OutputSize: {} bytes | Model: {} | Instance: {}",
                imageData != null ? imageData.length() : 0, model, instance.getId());

        return imageData;
    }

    private String handleImageGenerationException(String prompt, String model, Size size,
            Quality quality, int attemptNumber, Exception e) {
        if (shouldRetryChatCompletion(e, attemptNumber)) {
            long delay = calculateChatRetryDelay(e, attemptNumber);
            logger.warn("Image generation failed (attempt {}/{}), retrying in {}ms: {}",
                    attemptNumber + 1, config.getMaxRetries(), delay, e.getMessage());

            try {
                Thread.sleep(delay);
                return attemptImageGeneration(prompt, model, size, quality, attemptNumber + 1).join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Retry interrupted", ie);
            }
        }

        throw translateChatException(e);
    }

    private String handleImageEditException(String imageBase64, String prompt, String model, Size size,
            Quality quality, int attemptNumber, Exception e) {
        if (shouldRetryChatCompletion(e, attemptNumber)) {
            long delay = calculateChatRetryDelay(e, attemptNumber);
            logger.warn("Image edit failed (attempt {}/{}), retrying in {}ms: {}",
                    attemptNumber + 1, config.getMaxRetries(), delay, e.getMessage());

            try {
                Thread.sleep(delay);
                return attemptImageEdit(imageBase64, prompt, model, size, quality, attemptNumber + 1).join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Retry interrupted", ie);
            }
        }

        throw translateChatException(e);
    }

    // ==================== RESPONSE WRAPPERS ====================

    @Data
    public static class EmbeddingResponse {
        private List<EmbeddingData> data;
        private String model;
        private EmbeddingUsage usage;

        @Data
        public static class EmbeddingData {
            private int index;
            private List<Double> embedding;
            private String object;
        }

        @Data
        public static class EmbeddingUsage {
            private int promptTokens;
            private int totalTokens;
        }
    }

    @Data
    public static class ImageGenerationResponse {
        private List<Image> data;
        private long created;
    }

}
