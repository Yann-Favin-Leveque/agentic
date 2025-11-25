package io.github.yannfavinleveque.agentic.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Request object for Anthropic Claude API.
 * Used for direct API calls to Azure Anthropic instances.
 *
 * <p>Example request:</p>
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
     * System prompt (optional)
     */
    private String system;

    /**
     * List of messages in the conversation
     */
    private List<ClaudeMessage> messages;

    /**
     * Temperature for response generation (0.0 to 1.0)
     */
    private Double temperature;

    /**
     * Output format for structured output (optional)
     * Claude uses "output_format" with schema directly (not nested in json_schema)
     * Format: {"type": "json_schema", "schema": {...}}
     */
    @JsonProperty("output_format")
    private Map<String, Object> outputFormat;

    /**
     * Represents a single message in Claude conversation
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
         * Text content of the message
         */
        private String content;
    }
}
