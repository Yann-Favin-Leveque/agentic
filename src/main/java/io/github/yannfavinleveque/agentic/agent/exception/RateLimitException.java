package io.github.yannfavinleveque.agentic.agent.exception;

/**
 * Thrown when a rate limit is exceeded.
 * Contains information about retry timing.
 */
public class RateLimitException extends AgentException {

    private final long retryAfterMs;
    private final String provider;

    public RateLimitException(String message) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED, message);
        this.retryAfterMs = -1;
        this.provider = null;
    }

    public RateLimitException(String message, long retryAfterMs) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED, message);
        this.retryAfterMs = retryAfterMs;
        this.provider = null;
    }

    public RateLimitException(String message, long retryAfterMs, String provider) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED, message);
        this.retryAfterMs = retryAfterMs;
        this.provider = provider;
    }

    /**
     * Gets the suggested retry delay in milliseconds.
     * Returns -1 if not specified.
     */
    public long getRetryAfterMs() {
        return retryAfterMs;
    }

    /**
     * Gets the provider that returned the rate limit error.
     */
    public String getProvider() {
        return provider;
    }

    /**
     * Checks if a retry delay was suggested.
     */
    public boolean hasRetryAfter() {
        return retryAfterMs > 0;
    }
}
