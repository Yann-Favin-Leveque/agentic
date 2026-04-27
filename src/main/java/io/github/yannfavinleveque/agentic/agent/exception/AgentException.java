package io.github.yannfavinleveque.agentic.agent.exception;

/**
 * Base exception for all AgentService-related errors.
 * Provides structured error handling with error codes.
 */
public class AgentException extends RuntimeException {

    private final ErrorCode errorCode;

    public AgentException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AgentException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * Error codes for categorizing exceptions.
     */
    public enum ErrorCode {
        // Configuration errors
        NO_INSTANCE_AVAILABLE("No AI instance available for this operation"),
        INVALID_CONFIGURATION("Invalid service configuration"),
        MISSING_PROMPT_VARIABLE("Prompt template references a variable that was not provided"),

        // Agent errors
        AGENT_NOT_FOUND("Agent not found"),
        AGENT_CREATION_FAILED("Failed to create agent"),

        // Thread errors
        THREAD_NOT_FOUND("Thread not found"),
        THREAD_CREATION_FAILED("Failed to create thread"),

        // Request errors
        RATE_LIMIT_EXCEEDED("Rate limit exceeded"),
        CONTENT_FILTER_VIOLATION("Content filter violation"),
        REQUEST_TIMEOUT("Request timeout"),
        REQUEST_FAILED("Request failed"),
        MAX_TOKENS_EXCEEDED("LLM output truncated due to max tokens limit"),
        DESERIALIZATION_FAILED("Failed to deserialize LLM response into result class"),
        MAX_ITERATIONS_EXCEEDED("Autonomous agent exceeded max iterations"),

        // Provider errors
        PROVIDER_ERROR("Provider API error"),
        CLAUDE_API_ERROR("Claude API error"),

        // File/Vector store errors
        FILE_UPLOAD_FAILED("File upload failed"),
        VECTOR_STORE_ERROR("Vector store operation failed");

        private final String defaultMessage;

        ErrorCode(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }

        public String getDefaultMessage() {
            return defaultMessage;
        }
    }

    @Override
    public String toString() {
        return String.format("[%s] %s", errorCode.name(), getMessage());
    }
}
