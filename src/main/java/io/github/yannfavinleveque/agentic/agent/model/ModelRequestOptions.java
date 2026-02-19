package io.github.yannfavinleveque.agentic.agent.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Options for direct model requests via {@code requestModel()}.
 * <p>
 * This class provides a flexible way to configure model requests with optional
 * structured output, tools, and generation parameters.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 *
 * <pre>{@code
 * // Simple request with structured output
 * ModelRequestOptions options = ModelRequestOptions.builder()
 *         .resultClass(WeatherResult.class)
 *         .temperature(0.7)
 *         .build();
 *
 * // Request with web search enabled
 * ModelRequestOptions options = ModelRequestOptions.builder()
 *         .webSearch(true)
 *         .build();
 *
 * // Request with JSON schema directly
 * Map<String, Object> schema = Map.of(
 *         "type", "object",
 *         "properties", Map.of("answer", Map.of("type", "string")));
 * ModelRequestOptions options = ModelRequestOptions.builder()
 *         .resultSchema(schema)
 *         .build();
 * }</pre>
 *
 * @see io.github.yannfavinleveque.agentic.agent.service.AgentService#requestModel
 */
@Data
@Builder
public class ModelRequestOptions {

    /**
     * Result class for structured output. The response will be parsed into this class.
     * The class must extend {@link AgentResult}.
     */
    private Class<? extends AgentResult> resultClass;

    /**
     * JSON schema for structured output (alternative to resultClass).
     * Use this when you don't have a Java class for the result.
     * The schema should follow JSON Schema format.
     */
    private Map<String, Object> resultSchema;

    /**
     * Name for the structured output schema (used with resultSchema).
     * Defaults to "response" if not specified.
     */
    private String schemaName;

    /**
     * Enable web search tool. When true, the model can search the web
     * to answer questions.
     */
    @Builder.Default
    private boolean webSearch = false;

    /**
     * Enable code interpreter tool. When true, the model can execute
     * Python code to solve problems.
     */
    @Builder.Default
    private boolean codeInterpreter = false;

    /**
     * System instructions/prompt for the model.
     */
    private String instructions;

    /**
     * Temperature for response generation (0.0 to 2.0).
     * Lower values make output more deterministic.
     */
    private Double temperature;

    /**
     * Maximum tokens in the response.
     */
    private Integer maxTokens;

    /**
     * Single image as base64 (PNG assumed). For vision requests.
     */
    private String image;

    /**
     * Multiple images as base64 (PNG assumed). For vision requests.
     */
    private java.util.List<String> images;

    /**
     * Conversation history (previous messages).
     * Use this for manual history management. For automatic management,
     * use {@link #conversationId} instead.
     */
    private java.util.List<Message> history;

    /**
     * Reasoning effort level: null/"none" = disabled, "low"/"medium"/"high", "enabled" = default level.
     */
    private String reasoningEffort;

    /**
     * Conversation ID for automatic history management.
     * When set, the history is automatically retrieved from ConversationManager
     * and updated after each request. Use {@link io.github.yannfavinleveque.agentic.agent.service.AgentService#createConversation()}
     * to create a conversation.
     */
    private String conversationId;

    /**
     * Creates default options (no structured output, no tools).
     */
    public static ModelRequestOptions defaults() {
        return ModelRequestOptions.builder().build();
    }

    /**
     * Creates options with structured output class.
     */
    public static ModelRequestOptions withResultClass(Class<? extends AgentResult> resultClass) {
        return ModelRequestOptions.builder().resultClass(resultClass).build();
    }

    /**
     * Creates options with web search enabled.
     */
    public static ModelRequestOptions withWebSearch() {
        return ModelRequestOptions.builder().webSearch(true).build();
    }

    /**
     * Creates options with code interpreter enabled.
     */
    public static ModelRequestOptions withCodeInterpreter() {
        return ModelRequestOptions.builder().codeInterpreter(true).build();
    }
}
