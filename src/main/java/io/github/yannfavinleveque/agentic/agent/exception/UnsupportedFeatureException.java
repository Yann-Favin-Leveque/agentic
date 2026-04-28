package io.github.yannfavinleveque.agentic.agent.exception;

import io.github.yannfavinleveque.agentic.agent.custom.Feature;

import java.util.Set;

/**
 * Thrown when an agent requests a feature that the (custom) provider has not
 * declared as supported, and the provider's lenient mode is {@code THROW}.
 *
 * <p>Carries enough context to let the caller fix the configuration:
 * which instance, which feature, what the provider does support.</p>
 */
public class UnsupportedFeatureException extends AgentException {

    private final String instanceId;
    private final Feature feature;
    private final Set<Feature> supportedFeatures;

    public UnsupportedFeatureException(String instanceId, Feature feature, Set<Feature> supportedFeatures) {
        super(ErrorCode.UNSUPPORTED_FEATURE,
                buildMessage(instanceId, feature, supportedFeatures));
        this.instanceId = instanceId;
        this.feature = feature;
        this.supportedFeatures = supportedFeatures;
    }

    private static String buildMessage(String instanceId, Feature feature, Set<Feature> supportedFeatures) {
        return String.format(
                "Custom provider '%s' does not support feature '%s'. Supported features: %s. "
                        + "Either remove this feature from the agent definition, switch the agent to a "
                        + "provider that supports it, or set the provider's onUnsupportedFeature to 'warn'/'ignore'.",
                instanceId, feature, supportedFeatures);
    }

    public String getInstanceId() {
        return instanceId;
    }

    public Feature getFeature() {
        return feature;
    }

    public Set<Feature> getSupportedFeatures() {
        return supportedFeatures;
    }
}
