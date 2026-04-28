package io.github.yannfavinleveque.agentic.agent.custom;

import io.github.yannfavinleveque.agentic.agent.exception.UnsupportedFeatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Validates that the features required by a request are supported by a
 * {@link CustomProviderSpec}, applying the spec's {@link LenientMode}.
 *
 * <p>Returns a sanitized {@link EnumSet} of features that should actually be
 * applied to the request. In {@link LenientMode#THROW} mode the first
 * unsupported feature triggers an {@link UnsupportedFeatureException}; in
 * {@link LenientMode#WARN} or {@link LenientMode#IGNORE} the feature is
 * dropped from the returned set.</p>
 */
public final class FeatureValidator {

    private static final Logger logger = LoggerFactory.getLogger(FeatureValidator.class);

    private FeatureValidator() {
    }

    /**
     * Validates the requested features against the spec.
     *
     * @param instanceId         instance id (for error messages and logs)
     * @param spec               the custom provider spec (must not be null)
     * @param requestedFeatures  features the agent / request needs
     * @return the subset of {@code requestedFeatures} that should actually be applied
     * @throws UnsupportedFeatureException if any requested feature is unsupported
     *         and {@link LenientMode#THROW} is active
     */
    public static EnumSet<Feature> validate(
            String instanceId,
            CustomProviderSpec spec,
            Set<Feature> requestedFeatures) {

        if (requestedFeatures == null || requestedFeatures.isEmpty()) {
            return EnumSet.noneOf(Feature.class);
        }
        if (spec == null) {
            throw new IllegalArgumentException(
                    "CustomProviderSpec is null for instance: " + instanceId);
        }

        EnumSet<Feature> allowed = EnumSet.noneOf(Feature.class);
        EnumSet<Feature> supported = supportedFeatures(spec);
        LenientMode mode = spec.getLenientMode();

        for (Feature f : requestedFeatures) {
            if (spec.supports(f)) {
                allowed.add(f);
                continue;
            }
            switch (mode) {
                case THROW:
                    throw new UnsupportedFeatureException(instanceId, f, supported);
                case WARN:
                    logger.warn(
                            "Custom provider '{}' does not support feature '{}'. Dropping it (lenient=WARN). Supported: {}",
                            instanceId, f, supported);
                    break;
                case IGNORE:
                    // silent drop
                    break;
                default:
                    throw new IllegalStateException("Unhandled LenientMode: " + mode);
            }
        }
        return allowed;
    }

    /** Returns the set of features the spec declares as supported. */
    public static EnumSet<Feature> supportedFeatures(CustomProviderSpec spec) {
        EnumSet<Feature> out = EnumSet.noneOf(Feature.class);
        if (spec == null) return out;
        for (Feature f : Feature.values()) {
            if (spec.supports(f)) {
                out.add(f);
            }
        }
        return out;
    }
}
