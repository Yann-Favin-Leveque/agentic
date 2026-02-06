package io.github.yannfavinleveque.agentic.domain.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Request object for OpenAI Responses API (stateless endpoint). This replaces the Assistants API
 * for a unified, stateless architecture.
 * <p>
 * API Endpoint: POST /v1/responses
 * </p>
 * <p>
 * Example request:
 * </p>
 * 
 * <pre>{@code
 * {
 *   "model": "gpt-4.1",
 *   "input": [
 *     {"role": "user", "content": "What's the weather in Paris?"}
 *   ],
 *   "instructions": "You are a helpful assistant.",
 *   "tools": [
 *     {"type": "web_search_preview"},
 *     {"type": "code_interpreter"},
 *     {"type": "function", "name": "get_weather", "parameters": {...}}
 *   ],
 *   "temperature": 0.7,
 *   "max_output_tokens": 4096
 * }
 * }</pre>
 *
 * @see ResponsesResponse
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesRequest {

    /**
     * Model ID to use (e.g., "gpt-4.1", "gpt-4o").
     */
    @JsonProperty("model")
    private String model;

    /**
     * Input messages (conversation history). Each message has "role" and "content" fields.
     */
    @JsonProperty("input")
    private List<InputMessage> input;

    /**
     * System instructions for the model. Equivalent to system message in Chat Completions.
     */
    @JsonProperty("instructions")
    private String instructions;

    /**
     * Tools available to the model. Can include web_search_preview, code_interpreter, file_search, or
     * custom functions.
     */
    @JsonProperty("tools")
    private List<Tool> tools;

    /**
     * Temperature for response generation (0.0 to 2.0).
     */
    @JsonProperty("temperature")
    private Double temperature;

    /**
     * Maximum tokens in the response.
     */
    @JsonProperty("max_output_tokens")
    private Integer maxOutputTokens;

    /**
     * Response format for structured outputs.
     */
    @JsonProperty("text")
    private TextConfig text;

    /**
     * Whether to include tool usage information.
     */
    @JsonProperty("include")
    private List<String> include;

    // ==================== NESTED CLASSES ====================

    /**
     * Input message in the conversation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InputMessage {

        /**
         * Message role: "user", "assistant", or "system".
         */
        @JsonProperty("role")
        private String role;

        /**
         * Message content.
         */
        @JsonProperty("content")
        private String content;

    }

    /**
     * Tool configuration.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Tool {

        /**
         * Tool type: "web_search_preview", "code_interpreter", "file_search", or "function".
         */
        @JsonProperty("type")
        private String type;

        /**
         * Function name (for function tools).
         */
        @JsonProperty("name")
        private String name;

        /**
         * Function description (for function tools).
         */
        @JsonProperty("description")
        private String description;

        /**
         * Function parameters schema (for function tools).
         */
        @JsonProperty("parameters")
        private Map<String, Object> parameters;

        /**
         * Whether the function is strict (for function tools).
         */
        @JsonProperty("strict")
        private Boolean strict;

        // ==================== FACTORY METHODS ====================

        /**
         * Creates a web search tool.
         */
        public static Tool webSearch() {
            return Tool.builder()
                    .type("web_search_preview")
                    .build();
        }

        /**
         * Creates a code interpreter tool.
         */
        public static Tool codeInterpreter() {
            return Tool.builder()
                    .type("code_interpreter")
                    .build();
        }

        /**
         * Creates a file search tool.
         */
        public static Tool fileSearch() {
            return Tool.builder()
                    .type("file_search")
                    .build();
        }

        /**
         * Creates a function tool.
         *
         * @param name        Function name
         * @param description Function description
         * @param parameters  JSON schema for parameters
         * @return Function tool
         */
        public static Tool function(String name, String description, Map<String, Object> parameters) {
            return Tool.builder()
                    .type("function")
                    .name(name)
                    .description(description)
                    .parameters(parameters)
                    .strict(true)
                    .build();
        }

    }

    /**
     * Text/output configuration for structured outputs.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TextConfig {

        /**
         * Format type: "text" or "json_schema".
         */
        @JsonProperty("format")
        private Format format;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class Format {

            /**
             * Format type.
             */
            @JsonProperty("type")
            private String type;

            /**
             * Schema name (for json_schema type).
             */
            @JsonProperty("name")
            private String name;

            /**
             * JSON schema (for json_schema type).
             */
            @JsonProperty("schema")
            private Map<String, Object> schema;

            /**
             * Whether to use strict mode (for json_schema type).
             */
            @JsonProperty("strict")
            private Boolean strict;

        }

    }

}
