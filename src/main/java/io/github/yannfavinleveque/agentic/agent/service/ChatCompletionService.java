package io.github.yannfavinleveque.agentic.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import io.github.yannfavinleveque.agentic.agent.model.ClaudeRequest;
import io.github.yannfavinleveque.agentic.agent.model.ClaudeResponse;
import io.github.yannfavinleveque.agentic.agent.model.DefaultResult;
import io.github.yannfavinleveque.agentic.support.HttpHelper;
import io.github.yannfavinleveque.agentic.agent.core.Instance;
import io.github.yannfavinleveque.agentic.agent.core.ProviderConfig;
import io.github.yannfavinleveque.agentic.agent.exception.AgentException;
import io.github.yannfavinleveque.agentic.agent.exception.ContentFilterException;
import io.github.yannfavinleveque.agentic.agent.exception.RateLimitException;
import io.github.yannfavinleveque.agentic.agent.exception.RequestTimeoutException;
import io.github.yannfavinleveque.agentic.domain.chat.Chat;
import io.github.yannfavinleveque.agentic.domain.chat.ChatMessage;
import io.github.yannfavinleveque.agentic.domain.chat.ChatRequest;
import io.github.yannfavinleveque.agentic.common.ResponseFormat;
import io.github.yannfavinleveque.agentic.support.JsonSchemaGenerator;
import io.github.yannfavinleveque.agentic.domain.embedding.EmbeddingRequest;
import io.github.yannfavinleveque.agentic.domain.image.Image;
import io.github.yannfavinleveque.agentic.domain.image.ImageRequest;
import io.github.yannfavinleveque.agentic.domain.image.ImageRequest.Quality;
import io.github.yannfavinleveque.agentic.domain.image.Size;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Handles chat completions, embeddings, and image generation.
 */
public class ChatCompletionService {

    private static final Logger logger = LoggerFactory.getLogger(ChatCompletionService.class);

    private final HttpHelper httpHelper;
    private final InstanceRouter instanceRouter;
    private final ClaudeAdapter claudeAdapter;
    private final ObjectMapper objectMapper;
    private final AgentServiceConfig config;

    public ChatCompletionService(HttpHelper httpHelper, InstanceRouter instanceRouter,
                                  ClaudeAdapter claudeAdapter, ObjectMapper objectMapper,
                                  AgentServiceConfig config) {
        this.httpHelper = httpHelper;
        this.instanceRouter = instanceRouter;
        this.claudeAdapter = claudeAdapter;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    // ==================== CHAT COMPLETIONS ====================

    /**
     * Executes a chat completion request (string response).
     *
     * @param model Model name
     * @param messages Chat messages
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
     * @param model Model name
     * @param messages Chat messages
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
     * @param model           Model name (e.g., "gpt-4o", "claude-sonnet-4-5")
     * @param messages        List of chat messages
     * @param temperature     Temperature for response generation
     * @param resultClassName Simple class name (e.g., "WeatherResult")
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

    // ==================== EMBEDDINGS ====================

    /**
     * Generates embeddings for text.
     *
     * @param text Text to embed
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

    // ==================== IMAGE GENERATION ====================

    /**
     * Generates an image using DALL-E.
     *
     * @param prompt Image description
     * @param model Model name (e.g., "dall-e-3")
     * @param size Image size
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

    // ==================== RETRY LOGIC ====================

    /**
     * Attempts chat completion with retry logic.
     */
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
        });
    }

    /**
     * Attempts structured chat completion with retry logic.
     */
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
        });
    }

    /**
     * Attempts embedding generation with retry logic.
     */
    private CompletableFuture<float[]> attemptEmbedding(String text, String model, int attemptNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeEmbeddingRequest(text, model);
            } catch (Exception e) {
                return handleEmbeddingException(text, model, attemptNumber, e);
            }
        });
    }

    /**
     * Attempts image generation with retry logic.
     */
    private CompletableFuture<String> attemptImageGeneration(String prompt, String model, Size size,
                                                              Quality quality, int attemptNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeImageGenerationRequest(prompt, model, size, quality);
            } catch (Exception e) {
                return handleImageGenerationException(prompt, model, size, quality, attemptNumber, e);
            }
        });
    }

    // ==================== EXECUTION METHODS ====================

    private float[] executeEmbeddingRequest(String text, String model) throws Exception {
        int instanceIdx = instanceRouter.getNextInstanceForModel(model);
        Instance instance = instanceRouter.getInstance(instanceIdx);

        // Truncate text for logging
        String textPreview = text.length() > 100 ? text.substring(0, 100) + "..." : text;

        // LOG REQUEST START
        logger.info("→ EMBEDDING START | Model: {} | Instance: {} | Input: {}",
                model, instance.getId(), textPreview);

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
        logger.info("← EMBEDDING END | Model: {} | Instance: {} | Dimensions: {}",
                model, instance.getId(), result.length);

        return result;
    }

    private String executeImageGenerationRequest(String prompt, String model, Size size, Quality quality)
            throws Exception {
        int instanceIdx = instanceRouter.getNextInstanceForModel(model);
        Instance instance = instanceRouter.getInstance(instanceIdx);

        // Truncate prompt for logging
        String promptPreview = prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt;

        // LOG REQUEST START
        logger.info("→ IMAGE GEN START | Model: {} | Instance: {} | Size: {} | Quality: {} | Prompt: {}",
                model, instance.getId(), size, quality, promptPreview);

        ImageRequest imageRequest = ImageRequest.builder()
                .model(model)
                .prompt(prompt)
                .size(size)
                .quality(quality)
                .n(1)
                .responseFormat(io.github.yannfavinleveque.agentic.domain.image.ImageResponseFormat.B64JSON)
                .build();

        ImageGenerationResponse response = httpHelper.post(instance, ProviderConfig.Endpoint.IMAGES_GENERATIONS,
                model, imageRequest, ImageGenerationResponse.class).join();

        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Image generation returned empty response");
        }

        String imageData = response.getData().get(0).getB64Json();

        // LOG RESPONSE END
        logger.info("← IMAGE GEN END | Model: {} | Instance: {} | ImageDataSize: {} bytes",
                model, instance.getId(), imageData != null ? imageData.length() : 0);

        return imageData;
    }

    // ==================== EXCEPTION HANDLERS ====================

    private String handleChatCompletionException(String model, List<ChatMessage> messages,
                                                  Double temperature, int attemptNumber, Exception e) {
        if (shouldRetry(e, attemptNumber)) {
            long delay = calculateRetryDelay(e, attemptNumber);
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

        throw translateException(e);
    }

    @SuppressWarnings("unchecked")
    private <T extends AgentResult> T handleChatCompletionStructuredException(
            String model, List<ChatMessage> messages, Double temperature,
            Class<T> resultClass, int attemptNumber, Exception e) {

        if (shouldRetry(e, attemptNumber)) {
            long delay = calculateRetryDelay(e, attemptNumber);
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

        throw translateException(e);
    }

    private float[] handleEmbeddingException(String text, String model, int attemptNumber, Exception e) {
        if (shouldRetry(e, attemptNumber)) {
            long delay = calculateRetryDelay(e, attemptNumber);
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

        throw translateException(e);
    }

    private String handleImageGenerationException(String prompt, String model, Size size,
                                                    Quality quality, int attemptNumber, Exception e) {
        if (shouldRetry(e, attemptNumber)) {
            long delay = calculateRetryDelay(e, attemptNumber);
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

        throw translateException(e);
    }

    /**
     * Determines if request should be retried based on exception type.
     */
    private boolean shouldRetry(Exception e, int attemptNumber) {
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

    /**
     * Calculates retry delay with exponential backoff.
     * Special handling for rate limits and timeouts.
     */
    private long calculateRetryDelay(Exception e, int attemptNumber) {
        String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // Rate limit: use configured rate limit delay
        if (isRateLimitError(errorMessage)) {
            long retryAfter = extractRetryAfter(errorMessage);
            return retryAfter > 0 ? retryAfter : config.getRateLimitDelayMs();
        }

        // Timeout: no additional delay (progressive timeout handled separately)
        if (e instanceof RequestTimeoutException) {
            return 0;
        }

        // Exponential backoff for other errors
        return config.getRetryBaseDelayMs() * (long) Math.pow(2, attemptNumber);
    }

    /**
     * Extracts retry-after value from error message (in milliseconds).
     * Returns 0 if not found.
     */
    private long extractRetryAfter(String errorMessage) {
        // Try to find retry-after header value in error message
        // Common formats: "retry after 60 seconds", "retry-after: 60"
        try {
            if (errorMessage.contains("retry") && errorMessage.contains("after")) {
                String[] parts = errorMessage.split("\\s+");
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].contains("after") && i + 1 < parts.length) {
                        String next = parts[i + 1].replaceAll("[^0-9]", "");
                        if (!next.isEmpty()) {
                            return Long.parseLong(next) * 1000; // Convert to ms
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

    // ==================== PRIVATE METHODS ====================

    private String executeChatCompletionOpenAI(String model, List<ChatMessage> messages,
                                                Double temperature, ResponseFormat format,
                                                Instance instance) {
        // LOG REQUEST START
        String messagesPreview = messages.size() + " messages";
        logger.info("→ CHAT START | Model: {} | Instance: {} | Messages: {} | Temp: {}",
                model, instance.getId(), messagesPreview, temperature);

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
        logger.info("← CHAT END | Model: {} | Instance: {} | Response: {}",
                model, instance.getId(), responsePreview);

        return response;
    }

    private String executeChatCompletionClaude(String model, List<ChatMessage> messages,
                                                Double temperature, Instance instance) {
        // LOG REQUEST START
        String messagesPreview = messages.size() + " messages";
        logger.info("→ CHAT START | Model: {} | Instance: {} | Messages: {} | Temp: {}",
                model, instance.getId(), messagesPreview, temperature);

        List<ClaudeRequest.ClaudeMessage> claudeMessages = claudeAdapter.convertToClaude(messages);
        String systemPrompt = claudeAdapter.extractSystemPrompt(messages);

        ClaudeResponse claudeResponse = claudeAdapter.callClaude(instance, model, systemPrompt,
                claudeMessages, temperature, 4096, null);

        String response = claudeResponse.getTextContent();

        // LOG RESPONSE END
        String responsePreview = response.length() > 200 ? response.substring(0, 200) + "..." : response;
        logger.info("← CHAT END | Model: {} | Instance: {} | Response: {}",
                model, instance.getId(), responsePreview);

        return response;
    }

    private <T extends AgentResult> String executeChatCompletionClaudeStructured(
            String model, List<ChatMessage> messages, Double temperature,
            Instance instance, Class<T> resultClass) {

        // LOG REQUEST START
        String messagesPreview = messages.size() + " messages";
        logger.info("→ CHAT STRUCTURED START | Model: {} | Instance: {} | Messages: {} | Temp: {} | ResultClass: {}",
                model, instance.getId(), messagesPreview, temperature,
                resultClass != null ? resultClass.getSimpleName() : "null");

        List<ClaudeRequest.ClaudeMessage> claudeMessages = claudeAdapter.convertToClaude(messages);
        String systemPrompt = claudeAdapter.extractSystemPrompt(messages);

        ClaudeResponse claudeResponse = claudeAdapter.callClaude(instance, model, systemPrompt,
                claudeMessages, temperature, 4096, resultClass);

        String response = claudeResponse.getTextContent();

        // LOG RESPONSE END
        String responsePreview = response.length() > 200 ? response.substring(0, 200) + "..." : response;
        logger.info("← CHAT STRUCTURED END | Model: {} | Instance: {} | Response: {}",
                model, instance.getId(), responsePreview);

        return response;
    }

    private RuntimeException translateException(Exception e) {
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
