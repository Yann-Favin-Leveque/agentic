package io.github.sashirestela.openai.agent.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sashirestela.openai.agent.AgentResult;
import io.github.sashirestela.openai.agent.ClaudeRequest;
import io.github.sashirestela.openai.agent.ClaudeResponse;
import io.github.sashirestela.openai.agent.DefaultResult;
import io.github.sashirestela.openai.agent.HttpHelper;
import io.github.sashirestela.openai.agent.Instance;
import io.github.sashirestela.openai.agent.ProviderConfig;
import io.github.sashirestela.openai.agent.exception.AgentException;
import io.github.sashirestela.openai.agent.exception.ContentFilterException;
import io.github.sashirestela.openai.agent.exception.RateLimitException;
import io.github.sashirestela.openai.domain.chat.Chat;
import io.github.sashirestela.openai.domain.chat.ChatMessage;
import io.github.sashirestela.openai.domain.chat.ChatRequest;
import io.github.sashirestela.openai.common.ResponseFormat;
import io.github.sashirestela.openai.support.JsonSchemaGenerator;
import io.github.sashirestela.openai.domain.embedding.EmbeddingRequest;
import io.github.sashirestela.openai.domain.image.Image;
import io.github.sashirestela.openai.domain.image.ImageRequest;
import io.github.sashirestela.openai.domain.image.ImageRequest.Quality;
import io.github.sashirestela.openai.domain.image.Size;
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

    public ChatCompletionService(HttpHelper httpHelper, InstanceRouter instanceRouter,
                                  ClaudeAdapter claudeAdapter, ObjectMapper objectMapper) {
        this.httpHelper = httpHelper;
        this.instanceRouter = instanceRouter;
        this.claudeAdapter = claudeAdapter;
        this.objectMapper = objectMapper;
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
                throw translateException(e);
            }
        });
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
                throw translateException(e);
            }
        });
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
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIdx = instanceRouter.getNextInstanceForModel(model);
                Instance instance = instanceRouter.getInstance(instanceIdx);

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

                logger.debug("Embedding generated ({} dimensions)", result.length);
                return result;

            } catch (Exception e) {
                throw translateException(e);
            }
        });
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
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIdx = instanceRouter.getNextInstanceForModel(model);
                Instance instance = instanceRouter.getInstance(instanceIdx);

                ImageRequest imageRequest = ImageRequest.builder()
                        .model(model)
                        .prompt(prompt)
                        .size(size)
                        .quality(quality)
                        .n(1)
                        .responseFormat(io.github.sashirestela.openai.domain.image.ImageResponseFormat.B64JSON)
                        .build();

                logger.debug("Generating image with model: {}, size: {}, quality: {}", model, size, quality);

                ImageGenerationResponse response = httpHelper.post(instance, ProviderConfig.Endpoint.IMAGES_GENERATIONS,
                        model, imageRequest, ImageGenerationResponse.class).join();

                if (response == null || response.getData() == null || response.getData().isEmpty()) {
                    throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                            "Image generation returned empty response");
                }

                return response.getData().get(0).getB64Json();

            } catch (Exception e) {
                throw translateException(e);
            }
        });
    }

    /**
     * Generates an image with default settings (dall-e-3, 1024x1024, standard quality).
     */
    public CompletableFuture<String> generateImage(String prompt) {
        return generateImage(prompt, "dall-e-3", Size.X1024, Quality.STANDARD);
    }

    // ==================== PRIVATE METHODS ====================

    private String executeChatCompletionOpenAI(String model, List<ChatMessage> messages,
                                                Double temperature, ResponseFormat format,
                                                Instance instance) {
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

        return chatResponse.getChoices().get(0).getMessage().getContent();
    }

    private String executeChatCompletionClaude(String model, List<ChatMessage> messages,
                                                Double temperature, Instance instance) {
        List<ClaudeRequest.ClaudeMessage> claudeMessages = claudeAdapter.convertToClaude(messages);
        String systemPrompt = claudeAdapter.extractSystemPrompt(messages);

        ClaudeResponse response = claudeAdapter.callClaude(instance, model, systemPrompt,
                claudeMessages, temperature, 4096, null);

        return response.getTextContent();
    }

    private <T extends AgentResult> String executeChatCompletionClaudeStructured(
            String model, List<ChatMessage> messages, Double temperature,
            Instance instance, Class<T> resultClass) {

        List<ClaudeRequest.ClaudeMessage> claudeMessages = claudeAdapter.convertToClaude(messages);
        String systemPrompt = claudeAdapter.extractSystemPrompt(messages);

        ClaudeResponse response = claudeAdapter.callClaude(instance, model, systemPrompt,
                claudeMessages, temperature, 4096, resultClass);

        return response.getTextContent();
    }

    private RuntimeException translateException(Exception e) {
        if (e instanceof AgentException) {
            return (AgentException) e;
        }

        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        if (ContentFilterException.isContentFilterError(message)) {
            return new ContentFilterException(e.getMessage(), e);
        }

        if (message.contains("rate_limit") || message.contains("429")) {
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
