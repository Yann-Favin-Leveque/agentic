package io.github.yannfavinleveque.agentic.agent.exception;

/**
 * Thrown when content is blocked by a provider's content filter.
 * Common with Azure OpenAI's responsible AI policies.
 */
public class ContentFilterException extends AgentException {

    private final String provider;
    private final String filterType;

    public ContentFilterException(String message) {
        super(ErrorCode.CONTENT_FILTER_VIOLATION, message);
        this.provider = null;
        this.filterType = null;
    }

    public ContentFilterException(String message, String provider) {
        super(ErrorCode.CONTENT_FILTER_VIOLATION, message);
        this.provider = provider;
        this.filterType = null;
    }

    public ContentFilterException(String message, String provider, String filterType) {
        super(ErrorCode.CONTENT_FILTER_VIOLATION, message);
        this.provider = provider;
        this.filterType = filterType;
    }

    public ContentFilterException(String message, Throwable cause) {
        super(ErrorCode.CONTENT_FILTER_VIOLATION, message, cause);
        this.provider = null;
        this.filterType = null;
    }

    /**
     * Gets the provider that blocked the content.
     */
    public String getProvider() {
        return provider;
    }

    /**
     * Gets the type of filter that was triggered (if available).
     */
    public String getFilterType() {
        return filterType;
    }

    /**
     * Checks if the error message indicates a content filter violation.
     */
    public static boolean isContentFilterError(String errorMessage) {
        if (errorMessage == null) return false;
        String lower = errorMessage.toLowerCase();
        return lower.contains("content_filter") ||
               lower.contains("content_policy_violation") ||
               lower.contains("responsibleaipolicyviolation");
    }

    /**
     * Creates a ContentFilterException from an error message if it's a content filter error.
     * Returns null if it's not a content filter error.
     */
    public static ContentFilterException fromErrorMessage(String errorMessage, String provider) {
        if (isContentFilterError(errorMessage)) {
            return new ContentFilterException(errorMessage, provider);
        }
        return null;
    }
}
