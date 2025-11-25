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
     * Represents a content block in the response
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Content {

        /**
         * Content type (e.g., "text")
         */
        private String type;

        /**
         * Text content (for type="text")
         */
        private String text;
    }

    /**
     * Token usage statistics
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {

        /**
         * Number of input tokens
         */
        @JsonProperty("input_tokens")
        private Integer inputTokens;

        /**
         * Number of output tokens
         */
        @JsonProperty("output_tokens")
        private Integer outputTokens;
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
