package io.github.yannfavinleveque.agentic.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Request object for Anthropic Claude API. Used for direct API calls to Azure Anthropic instances.
 * <p>
 * Example request:
 * </p>
 * 
 * <pre>{@code
 * {
 *   "model": "claude-sonnet-4-5",
 *   "max_tokens": 4096,
 *   "system": "You are a helpful assistant.",
 *   "messages": [
 *     {"role": "user", "content": "Hello!"}
 *   ],
 *   "temperature": 0.7
 * }
 * }</pre>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClaudeRequest {

    /**
     * Model name (e.g., "claude-sonnet-4-5", "claude-haiku-4-5")
     */
    private String model;

    /**
     * Maximum tokens for response (required for Claude)
     */
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /**
     * System prompt as a plain string (optional). For prompt caching support, use {@link #systemBlocks}
     * instead — Anthropic only honors {@code cache_control} when the system field is an array of
     * content blocks. Lombok-generated {@code getSystem()} is hidden from JSON via {@link JsonIgnore};
     * serialization goes through {@link #getSystemForSerialization()}.
     */
    @JsonIgnore
    private String system;

    /**
     * System prompt as an array of content blocks (optional, takes precedence over {@link #system} when
     * set). Required form for prompt caching: each block can carry a {@code cache_control: {type:
     * "ephemeral"}} marker.
     */
    @JsonIgnore
    private List<ClaudeContentBlock> systemBlocks;

    /**
     * Picks systemBlocks if set, otherwise falls back to the string {@link #system}. Anthropic accepts
     * either form on the {@code system} field.
     */
    @JsonProperty("system")
    public Object getSystemForSerialization() {
        if (systemBlocks != null && !systemBlocks.isEmpty()) {
            return systemBlocks;
        }
        return system;
    }

    /**
     * List of messages in the conversation
     */
    private List<ClaudeMessage> messages;

    /**
     * Temperature for response generation (0.0 to 1.0)
     */
    private Double temperature;

    /**
     * Output format for structured output (optional) Claude uses "output_format" with schema directly
     * (not nested in json_schema) Format: {"type": "json_schema", "schema": {...}}
     */
    @JsonProperty("output_format")
    private Map<String, Object> outputFormat;

    /**
     * Tools available to the model (optional). Can include web_search_20250305 or custom functions.
     * <p>
     * Example tools:
     * </p>
     * 
     * <pre>{@code
     * [
     *   {"type": "web_search_20250305", "name": "web_search", "max_uses": 5},
     *   {"name": "get_weather", "description": "...", "input_schema": {...}}
     * ]
     * }</pre>
     */
    private List<ClaudeTool> tools;

    /**
     * How the model should use tools (optional). Can be "auto", "any", "none", or a specific tool name.
     */
    @JsonProperty("tool_choice")
    private Object toolChoice;

    /**
     * Extended thinking configuration (optional). When enabled, Claude will use internal reasoning
     * before responding. Format: {"type": "enabled", "budget_tokens": 1024}
     */
    private Map<String, Object> thinking;

    /**
     * Represents a single message in Claude conversation. Content can be a string (text-only) or a list
     * of content blocks (multimodal).
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClaudeMessage {

        /**
         * Role: "user" or "assistant"
         */
        private String role;

        /**
         * Text content of the message (for simple text-only messages). Use contentBlocks for multimodal
         * messages.
         */
        private String content;

        /**
         * Multimodal content blocks (for messages with images). When serialized, replaces 'content' with
         * array of blocks.
         */
        @JsonProperty("content")
        private List<ClaudeContentBlock> contentBlocks;

        /**
         * Custom getter to return contentBlocks if set, otherwise content string. Jackson will serialize
         * this as the 'content' field.
         */
        @JsonProperty("content")
        public Object getContentForSerialization() {
            if (contentBlocks != null && !contentBlocks.isEmpty()) {
                return contentBlocks;
            }
            return content;
        }

    }

    /**
     * Represents a content block in a Claude message. Can be text, image, or other types.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClaudeContentBlock {

        /**
         * Block type: "text", "image", "tool_use", or "tool_result"
         */
        private String type;

        /**
         * Text content (when type is "text")
         */
        private String text;

        /**
         * Image source (when type is "image")
         */
        private ClaudeImageSource source;

        /**
         * Tool use ID (for tool_use blocks: the ID of this call; for tool_result blocks: the ID being
         * responded to).
         */
        @JsonProperty("tool_use_id")
        private String toolUseId;

        /**
         * Unique ID for tool_use blocks.
         */
        private String id;

        /**
         * Tool/function name (for tool_use blocks).
         */
        private String name;

        /**
         * Tool input arguments (for tool_use blocks). Serialized as JSON object.
         */
        private Object input;

        /**
         * Content for tool_result blocks (the string result of tool execution).
         */
        private String content;

        /**
         * Optional prompt-caching marker. When set to {@code {"type": "ephemeral"}}, Anthropic caches
         * everything up to and including this block for ~5 minutes; subsequent requests with an identical
         * prefix get a 90% input-cost discount. Only honored by direct Anthropic — Azure Anthropic ignores
         * it.
         */
        @JsonProperty("cache_control")
        private Map<String, Object> cacheControl;

        /**
         * Creates a text content block.
         */
        public static ClaudeContentBlock text(String text) {
            return ClaudeContentBlock.builder()
                    .type("text")
                    .text(text)
                    .build();
        }

        /**
         * Creates a text content block flagged for prompt caching ({@code cache_control: {type:
         * "ephemeral"}}). Use on the system prompt or the last reusable message to bound a cache prefix.
         */
        public static ClaudeContentBlock textCached(String text) {
            return ClaudeContentBlock.builder()
                    .type("text")
                    .text(text)
                    .cacheControl(Map.of("type", "ephemeral"))
                    .build();
        }

        /**
         * Creates an image content block from URL.
         */
        public static ClaudeContentBlock imageUrl(String url) {
            return ClaudeContentBlock.builder()
                    .type("image")
                    .source(ClaudeImageSource.builder()
                            .type("url")
                            .url(url)
                            .build())
                    .build();
        }

        /**
         * Creates an image content block from base64 data.
         */
        public static ClaudeContentBlock imageBase64(String base64Data, String mediaType) {
            return ClaudeContentBlock.builder()
                    .type("image")
                    .source(ClaudeImageSource.builder()
                            .type("base64")
                            .mediaType(mediaType)
                            .data(base64Data)
                            .build())
                    .build();
        }

        /**
         * Creates a tool_result content block for sending function results back to Claude.
         *
         * @param toolUseId ID of the tool_use call this responds to
         * @param result    String result of tool execution
         */
        public static ClaudeContentBlock toolResult(String toolUseId, String result) {
            return ClaudeContentBlock.builder()
                    .type("tool_result")
                    .toolUseId(toolUseId)
                    .content(result)
                    .build();
        }

        /**
         * Creates a tool_use content block for replaying assistant tool calls in conversation history.
         *
         * @param id    Unique ID of this tool use call
         * @param name  Function/tool name
         * @param input Input arguments (as Map or Object)
         */
        public static ClaudeContentBlock toolUse(String id, String name, Object input) {
            return ClaudeContentBlock.builder()
                    .type("tool_use")
                    .id(id)
                    .name(name)
                    .input(input)
                    .build();
        }

    }

    /**
     * Image source for Claude image content blocks.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClaudeImageSource {

        /**
         * Source type: "url" or "base64"
         */
        private String type;

        /**
         * Image URL (when type is "url")
         */
        private String url;

        /**
         * Media type (when type is "base64"), e.g., "image/png", "image/jpeg"
         */
        @JsonProperty("media_type")
        private String mediaType;

        /**
         * Base64-encoded image data (when type is "base64")
         */
        private String data;

    }

    /**
     * Represents a tool available to Claude.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClaudeTool {

        /**
         * Tool type (optional for custom functions). Use "web_search_20250305" for web search.
         */
        private String type;

        /**
         * Tool name.
         */
        private String name;

        /**
         * Tool description (for custom functions).
         */
        private String description;

        /**
         * Input schema for the tool (for custom functions). JSON Schema format.
         */
        @JsonProperty("input_schema")
        private Map<String, Object> inputSchema;

        /**
         * Maximum uses for this tool (for web_search).
         */
        @JsonProperty("max_uses")
        private Integer maxUses;

        /**
         * Optional prompt-caching marker. Set on the LAST tool of the array to mark all tools as cacheable
         * as one block. Only honored by direct Anthropic — Azure ignores it.
         */
        @JsonProperty("cache_control")
        private Map<String, Object> cacheControl;

        // ==================== FACTORY METHODS ====================

        /**
         * Creates a web search tool for Claude.
         *
         * @param maxUses Maximum number of searches (default 5)
         * @return Web search tool
         */
        public static ClaudeTool webSearch(Integer maxUses) {
            return ClaudeTool.builder()
                    .type("web_search_20250305")
                    .name("web_search")
                    .maxUses(maxUses != null ? maxUses : 5)
                    .build();
        }

        /**
         * Creates a web search tool with default max uses.
         *
         * @return Web search tool
         */
        public static ClaudeTool webSearch() {
            return webSearch(5);
        }

        /**
         * Creates a custom function tool for Claude.
         *
         * @param name        Function name
         * @param description Function description
         * @param inputSchema JSON schema for input parameters
         * @return Function tool
         */
        public static ClaudeTool function(String name, String description, Map<String, Object> inputSchema) {
            return ClaudeTool.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(inputSchema)
                    .build();
        }

    }

}
