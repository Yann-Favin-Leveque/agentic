package io.github.yannfavinleveque.agentic.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Unified message representation for conversation history. Used in the stateless architecture to
 * pass conversation context between requests.
 * <p>
 * This class provides a provider-agnostic message format that can be converted to either OpenAI or
 * Claude message formats. Supports text-only, multimodal (text + images), tool result, and
 * assistant-with-tool-calls content.
 * </p>
 * <p>
 * Example usage (text only):
 * </p>
 *
 * <pre>{@code
 *
 * List<Message> history = List.of(
 *         Message.user("What's the weather?"),
 *         Message.assistant("The weather is sunny."),
 *         Message.user("What about tomorrow?"));
 * }</pre>
 *
 * <p>
 * Example usage (tool results in autonomous mode):
 * </p>
 *
 * <pre>{@code
 * Message toolResult = Message.toolResult("call_123", "get_weather", "{\"temp\": 22}");
 * Message assistantWithCalls = Message.assistantWithToolCalls("Let me check...", functionCalls);
 * }</pre>
 *
 * @see ClaudeRequest.ClaudeMessage
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@NoArgsConstructor
public class Message {

    /**
     * Message role: "user", "assistant", "system", or "tool".
     */
    @JsonProperty("role")
    private String role;

    /**
     * Text content of the message (for simple text-only messages).
     */
    @JsonProperty("content")
    private String textContent;

    /**
     * Multimodal content parts (for messages with images).
     * When set, textContent should be null.
     */
    @JsonProperty("contentParts")
    private List<ContentPart> contentParts;

    /**
     * Tool call ID this message responds to (for tool result messages, role="tool").
     */
    @JsonProperty("toolCallId")
    private String toolCallId;

    /**
     * Tool name (for tool result messages).
     */
    @JsonProperty("toolName")
    private String toolName;

    /**
     * Function calls made by the assistant (for replaying assistant tool-use turns in conversation history).
     */
    @JsonProperty("functionCalls")
    private List<FunctionCall> functionCalls;

    /**
     * Full constructor for backward compatibility.
     */
    public Message(String role, String textContent, List<ContentPart> contentParts) {
        this.role = role;
        this.textContent = textContent;
        this.contentParts = contentParts;
    }

    // ==================== CONTENT PART ====================

    /**
     * A part of multimodal message content. Can be text or image.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContentPart {

        /**
         * Content type: "text", "image_url", or "image_base64".
         */
        @JsonProperty("type")
        private String type;

        /**
         * Text content (when type is "text").
         */
        @JsonProperty("text")
        private String text;

        /**
         * Image URL (when type is "image_url").
         */
        @JsonProperty("image_url")
        private String imageUrl;

        /**
         * Base64-encoded image data (when type is "image_base64").
         */
        @JsonProperty("image_base64")
        private String imageBase64;

        /**
         * Media type for base64 images (e.g., "image/png", "image/jpeg").
         */
        @JsonProperty("media_type")
        private String mediaType;

        /**
         * Creates a text content part.
         */
        public static ContentPart text(String text) {
            return ContentPart.builder()
                    .type("text")
                    .text(text)
                    .build();
        }

        /**
         * Creates an image URL content part.
         */
        public static ContentPart imageUrl(String url) {
            return ContentPart.builder()
                    .type("image_url")
                    .imageUrl(url)
                    .build();
        }

        /**
         * Creates a base64 image content part.
         *
         * @param base64Data Base64-encoded image data
         * @param mediaType  Media type (e.g., "image/png", "image/jpeg")
         */
        public static ContentPart imageBase64(String base64Data, String mediaType) {
            return ContentPart.builder()
                    .type("image_base64")
                    .imageBase64(base64Data)
                    .mediaType(mediaType)
                    .build();
        }

        /**
         * Creates a PNG base64 image content part.
         */
        public static ContentPart pngBase64(String base64Data) {
            return imageBase64(base64Data, "image/png");
        }

        /**
         * Creates a JPEG base64 image content part.
         */
        public static ContentPart jpegBase64(String base64Data) {
            return imageBase64(base64Data, "image/jpeg");
        }

        /**
         * Creates a GIF base64 image content part.
         */
        public static ContentPart gifBase64(String base64Data) {
            return imageBase64(base64Data, "image/gif");
        }

        /**
         * Creates a WebP base64 image content part.
         */
        public static ContentPart webpBase64(String base64Data) {
            return imageBase64(base64Data, "image/webp");
        }
    }

    // ==================== BUILDER ====================

    /**
     * Custom builder to support both text and multimodal content.
     */
    public static MessageBuilder builder() {
        return new MessageBuilder();
    }

    public static class MessageBuilder {
        private String role;
        private String textContent;
        private List<ContentPart> contentParts;

        public MessageBuilder role(String role) {
            this.role = role;
            return this;
        }

        /**
         * Sets text content (for simple text-only messages).
         */
        public MessageBuilder content(String content) {
            this.textContent = content;
            this.contentParts = null;
            return this;
        }

        /**
         * Sets multimodal content parts (for messages with images).
         */
        public MessageBuilder content(List<ContentPart> parts) {
            this.contentParts = parts;
            this.textContent = null;
            return this;
        }

        public Message build() {
            return new Message(role, textContent, contentParts);
        }
    }

    // ==================== STATIC FACTORY METHODS ====================

    /**
     * Creates a user message.
     *
     * @param content Message content
     * @return Message with role "user"
     */
    public static Message user(String content) {
        return Message.builder()
                .role("user")
                .content(content)
                .build();
    }

    /**
     * Creates an assistant message.
     *
     * @param content Message content
     * @return Message with role "assistant"
     */
    public static Message assistant(String content) {
        return Message.builder()
                .role("assistant")
                .content(content)
                .build();
    }

    /**
     * Creates a system message.
     *
     * @param content Message content
     * @return Message with role "system"
     */
    public static Message system(String content) {
        return Message.builder()
                .role("system")
                .content(content)
                .build();
    }

    /**
     * Creates a tool result message for sending function execution results back to the LLM.
     * Used in autonomous mode to properly format tool results for the API.
     *
     * @param toolCallId ID of the function call this responds to
     * @param toolName   Name of the tool
     * @param result     String result of tool execution
     * @return Message with role "tool"
     */
    public static Message toolResult(String toolCallId, String toolName, String result) {
        Message msg = new Message();
        msg.setRole("tool");
        msg.setTextContent(result);
        msg.setToolCallId(toolCallId);
        msg.setToolName(toolName);
        return msg;
    }

    /**
     * Creates an assistant message that contains function call information.
     * This is needed to properly replay tool-use turns in conversation history,
     * so that the API receives the correct message format (assistant with tool_use blocks).
     *
     * @param textContent   Text content from the assistant (can be null)
     * @param functionCalls List of function calls made by the assistant
     * @return Message with role "assistant" and function calls
     */
    public static Message assistantWithToolCalls(String textContent, List<FunctionCall> functionCalls) {
        Message msg = new Message();
        msg.setRole("assistant");
        msg.setTextContent(textContent);
        msg.setFunctionCalls(functionCalls);
        return msg;
    }

    // ==================== CONTENT ACCESS ====================

    /**
     * Gets the content as a string. For multimodal messages, extracts text parts only.
     *
     * @return Text content
     */
    public String getContent() {
        if (textContent != null) {
            return textContent;
        }
        if (contentParts != null) {
            StringBuilder sb = new StringBuilder();
            for (ContentPart part : contentParts) {
                if ("text".equals(part.getType()) && part.getText() != null) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(part.getText());
                }
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * Checks if this message has multimodal content (images).
     *
     * @return true if message contains images
     */
    public boolean isMultimodal() {
        return contentParts != null && !contentParts.isEmpty();
    }

    // ==================== CONVERSION METHODS ====================

    /**
     * Converts to Claude message format.
     *
     * @return ClaudeMessage equivalent
     */
    public ClaudeRequest.ClaudeMessage toClaudeMessage() {
        return ClaudeRequest.ClaudeMessage.builder()
                .role(this.role)
                .content(this.getContent())
                .build();
    }

    /**
     * Creates a Message from Claude message format.
     *
     * @param claudeMessage Claude message
     * @return Unified Message
     */
    public static Message fromClaudeMessage(ClaudeRequest.ClaudeMessage claudeMessage) {
        return Message.builder()
                .role(claudeMessage.getRole())
                .content(claudeMessage.getContent())
                .build();
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Checks if this is a user message.
     *
     * @return true if role is "user"
     */
    public boolean isUser() {
        return "user".equals(role);
    }

    /**
     * Checks if this is an assistant message.
     *
     * @return true if role is "assistant"
     */
    public boolean isAssistant() {
        return "assistant".equals(role);
    }

    /**
     * Checks if this is a system message.
     *
     * @return true if role is "system"
     */
    public boolean isSystem() {
        return "system".equals(role);
    }

    /**
     * Checks if this is a tool result message.
     *
     * @return true if role is "tool"
     */
    public boolean isToolResult() {
        return "tool".equals(role);
    }

    /**
     * Checks if this assistant message contains function/tool calls.
     *
     * @return true if this message has function calls
     */
    public boolean hasToolCalls() {
        return functionCalls != null && !functionCalls.isEmpty();
    }

}
