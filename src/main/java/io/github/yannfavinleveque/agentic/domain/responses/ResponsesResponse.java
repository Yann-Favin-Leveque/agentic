package io.github.yannfavinleveque.agentic.domain.responses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Response object from OpenAI Responses API.
 * <p>
 * Example response:
 * </p>
 * 
 * <pre>{@code
 * {
 *   "id": "resp_abc123",
 *   "object": "response",
 *   "created_at": 1699564901,
 *   "model": "gpt-4.1",
 *   "output": [
 *     {
 *       "type": "message",
 *       "id": "msg_abc123",
 *       "role": "assistant",
 *       "content": [
 *         {"type": "output_text", "text": "The weather in Paris is..."}
 *       ]
 *     }
 *   ],
 *   "usage": {
 *     "input_tokens": 100,
 *     "output_tokens": 50,
 *     "total_tokens": 150
 *   },
 *   "status": "completed"
 * }
 * }</pre>
 *
 * @see ResponsesRequest
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsesResponse {

    /**
     * Response ID.
     */
    @JsonProperty("id")
    private String id;

    /**
     * Object type (always "response").
     */
    @JsonProperty("object")
    private String object;

    /**
     * Unix timestamp of creation.
     */
    @JsonProperty("created_at")
    private Long createdAt;

    /**
     * Model used for the response.
     */
    @JsonProperty("model")
    private String model;

    /**
     * Output items (messages, tool calls, etc.).
     */
    @JsonProperty("output")
    private List<OutputItem> output;

    /**
     * Token usage statistics.
     */
    @JsonProperty("usage")
    private Usage usage;

    /**
     * Response status: "completed", "failed", "incomplete".
     */
    @JsonProperty("status")
    private String status;

    /**
     * Error information if status is "failed".
     */
    @JsonProperty("error")
    private Error error;

    // ==================== CONVENIENCE METHODS ====================

    /**
     * Gets the text content from the first message output.
     *
     * @return Text content or empty string if not found
     */
    public String getTextContent() {
        if (output == null || output.isEmpty()) {
            return "";
        }

        for (OutputItem item : output) {
            if ("message".equals(item.getType()) && item.getContent() != null) {
                for (ContentItem content : item.getContent()) {
                    if ("output_text".equals(content.getType()) && content.getText() != null) {
                        return content.getText();
                    }
                    // Also handle "text" type for compatibility
                    if ("text".equals(content.getType()) && content.getText() != null) {
                        return content.getText();
                    }
                }
            }
        }
        return "";
    }

    /**
     * Checks if the response completed successfully.
     *
     * @return true if status is "completed"
     */
    public boolean isCompleted() {
        return "completed".equals(status);
    }

    /**
     * Checks if there are any tool calls in the output.
     *
     * @return true if tool calls exist
     */
    public boolean hasToolCalls() {
        if (output == null)
            return false;
        return output.stream().anyMatch(item -> "tool_call".equals(item.getType()));
    }

    /**
     * Gets all tool calls from the output.
     *
     * @return List of tool call output items
     */
    public List<OutputItem> getToolCalls() {
        if (output == null)
            return List.of();
        return output.stream()
                .filter(item -> "tool_call".equals(item.getType()))
                .collect(Collectors.toList());
    }

    // ==================== NESTED CLASSES ====================

    /**
     * Output item (message or tool call).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OutputItem {

        /**
         * Item type: "message" or "tool_call".
         */
        @JsonProperty("type")
        private String type;

        /**
         * Item ID.
         */
        @JsonProperty("id")
        private String id;

        /**
         * Message role (for message type).
         */
        @JsonProperty("role")
        private String role;

        /**
         * Message content (for message type).
         */
        @JsonProperty("content")
        private List<ContentItem> content;

        /**
         * Tool call name (for tool_call type).
         */
        @JsonProperty("name")
        private String name;

        /**
         * Tool call arguments as JSON string (for tool_call type).
         */
        @JsonProperty("arguments")
        private String arguments;

        /**
         * Tool call ID (for tool_call type).
         */
        @JsonProperty("call_id")
        private String callId;

    }

    /**
     * Content item within a message.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentItem {

        /**
         * Content type: "output_text", "text", "refusal", etc.
         */
        @JsonProperty("type")
        private String type;

        /**
         * Text content.
         */
        @JsonProperty("text")
        private String text;

        /**
         * Refusal reason (if model refused).
         */
        @JsonProperty("refusal")
        private String refusal;

        /**
         * Annotations (citations, file references, etc.).
         */
        @JsonProperty("annotations")
        private List<Object> annotations;

    }

    /**
     * Token usage statistics.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {

        /**
         * Input tokens used.
         */
        @JsonProperty("input_tokens")
        private Integer inputTokens;

        /**
         * Output tokens generated.
         */
        @JsonProperty("output_tokens")
        private Integer outputTokens;

        /**
         * Total tokens (input + output).
         */
        @JsonProperty("total_tokens")
        private Integer totalTokens;

    }

    /**
     * Error information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Error {

        /**
         * Error code.
         */
        @JsonProperty("code")
        private String code;

        /**
         * Error message.
         */
        @JsonProperty("message")
        private String message;

    }

}
