package io.github.yannfavinleveque.agentic.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Response object from Anthropic Claude API.
 *
 * <p>Example response:</p>
 * <pre>{@code
 * {
 *   "id": "msg_123abc",
 *   "type": "message",
 *   "role": "assistant",
 *   "content": [
 *     {
 *       "type": "text",
 *       "text": "Hello! How can I help you today?"
 *     }
 *   ],
 *   "model": "claude-sonnet-4-5",
 *   "stop_reason": "end_turn",
 *   "usage": {
 *     "input_tokens": 10,
 *     "output_tokens": 25
 *   }
 * }
 * }</pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClaudeResponse {

    /**
     * Unique message ID
     */
    private String id;

    /**
     * Response type (typically "message")
     */
    private String type;

    /**
     * Role (typically "assistant")
     */
    private String role;

    /**
     * Content blocks (text, images, etc.)
     */
    private List<Content> content;

    /**
     * Model used for generation
     */
    private String model;

    /**
     * Reason for stopping generation
     */
    @JsonProperty("stop_reason")
    private String stopReason;

    /**
     * Token usage statistics
     */
    private Usage usage;

    /**
     * Represents a content block in the response.
     * Can be text content or tool_use (function call request).
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Content {

        /**
         * Content type: "text" or "tool_use"
         */
        private String type;

        /**
         * Text content (for type="text")
         */
        private String text;

        /**
         * Tool use ID (for type="tool_use")
         */
        private String id;

        /**
         * Tool/function name (for type="tool_use")
         */
        private String name;

        /**
         * Tool input/arguments as JSON object (for type="tool_use")
         */
        private Object input;
    }

    /**
     * Token usage statistics.
     *
     * <p>Anthropic reports cached prompt tokens separately from {@code input_tokens}.
     * {@code input_tokens} contains ONLY the uncached portion of the prompt; cache hits
     * are surfaced via {@code cache_read_input_tokens} and writes via
     * {@code cache_creation_input_tokens}. Both fields are absent (and parsed as
     * {@code null}) on responses for models / providers that do not support prompt
     * caching.</p>
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {

        /**
         * Number of uncached input tokens (Anthropic semantics).
         */
        @JsonProperty("input_tokens")
        private Integer inputTokens;

        /**
         * Number of output tokens
         */
        @JsonProperty("output_tokens")
        private Integer outputTokens;

        /**
         * Tokens written into the prompt cache on this request (Anthropic).
         * {@code null} when the field is absent from the API response.
         */
        @JsonProperty("cache_creation_input_tokens")
        private Integer cacheCreationInputTokens;

        /**
         * Tokens served from the prompt cache on this request (Anthropic).
         * {@code null} when the field is absent from the API response.
         */
        @JsonProperty("cache_read_input_tokens")
        private Integer cacheReadInputTokens;
    }

    /**
     * Extract text content from the first content block.
     * @return Text content or empty string if not available
     */
    public String getTextContent() {
        if (content != null && !content.isEmpty()) {
            for (Content c : content) {
                if ("text".equals(c.getType()) && c.getText() != null) {
                    return c.getText();
                }
            }
        }
        return "";
    }
}
