package io.github.yannfavinleveque.agentic.agent.exception;

/**
 * Thrown when a request times out.
 */
public class RequestTimeoutException extends AgentException {

    private final long timeoutSeconds;

    public RequestTimeoutException(long timeoutSeconds) {
        super(ErrorCode.REQUEST_TIMEOUT,
              "Request timeout after " + timeoutSeconds + " seconds");
        this.timeoutSeconds = timeoutSeconds;
    }

    public RequestTimeoutException(String message, long timeoutSeconds) {
        super(ErrorCode.REQUEST_TIMEOUT, message);
        this.timeoutSeconds = timeoutSeconds;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
