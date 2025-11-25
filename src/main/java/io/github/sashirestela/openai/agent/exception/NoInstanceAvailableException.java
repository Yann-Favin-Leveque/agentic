package io.github.sashirestela.openai.agent.exception;

import java.util.List;

/**
 * Thrown when no AI instance is available for the requested operation.
 * This can happen when:
 * - No instances are configured (degraded mode)
 * - No instance has the requested model deployed
 */
public class NoInstanceAvailableException extends AgentException {

    private final String requestedModel;
    private final List<String> availableModels;

    public NoInstanceAvailableException(String message) {
        super(ErrorCode.NO_INSTANCE_AVAILABLE, message);
        this.requestedModel = null;
        this.availableModels = List.of();
    }

    public NoInstanceAvailableException(String requestedModel, List<String> availableModels) {
        super(ErrorCode.NO_INSTANCE_AVAILABLE,
                String.format("No instance configured with model '%s'. Available models: %s",
                        requestedModel, availableModels));
        this.requestedModel = requestedModel;
        this.availableModels = availableModels;
    }

    public String getRequestedModel() {
        return requestedModel;
    }

    public List<String> getAvailableModels() {
        return availableModels;
    }

    /**
     * Creates exception for degraded mode (no instances configured).
     */
    public static NoInstanceAvailableException degradedMode() {
        return new NoInstanceAvailableException(
                "No AI instances configured. Set OPENAI_INSTANCES environment variable to enable AI features.");
    }
}
