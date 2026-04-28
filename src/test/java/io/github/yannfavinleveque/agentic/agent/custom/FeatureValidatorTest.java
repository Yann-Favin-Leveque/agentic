package io.github.yannfavinleveque.agentic.agent.custom;

import io.github.yannfavinleveque.agentic.agent.exception.UnsupportedFeatureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FeatureValidator}.
 */
class FeatureValidatorTest {

    private CustomProviderSpec specWith(Map<String, Boolean> features, String onUnsupported) {
        return CustomProviderSpec.builder()
                .apiFormat("openai-chat")
                .features(features)
                .onUnsupportedFeature(onUnsupported)
                .build();
    }

    @Test
    @DisplayName("validate returns requested features that are supported (THROW mode)")
    void validate_allSupported_returnsRequested() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("vision", true);
        features.put("function_calling", true);
        CustomProviderSpec spec = specWith(features, "throw");

        EnumSet<Feature> result = FeatureValidator.validate(
                "my-instance", spec, EnumSet.of(Feature.VISION));

        assertEquals(EnumSet.of(Feature.VISION), result);
    }

    @Test
    @DisplayName("validate throws UnsupportedFeatureException in THROW mode for unsupported feature")
    void validate_throwMode_unsupported_throws() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("vision", true);
        CustomProviderSpec spec = specWith(features, "throw");

        UnsupportedFeatureException ex = assertThrows(UnsupportedFeatureException.class,
                () -> FeatureValidator.validate(
                        "my-instance", spec, EnumSet.of(Feature.VISION, Feature.WEB_SEARCH)));

        assertTrue(ex.getMessage().contains("WEB_SEARCH"),
                "message should contain WEB_SEARCH, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("my-instance"),
                "message should contain instance id, was: " + ex.getMessage());
    }

    @Test
    @DisplayName("validate WARN mode drops unsupported features without throwing")
    void validate_warnMode_dropsUnsupported() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("vision", true);
        CustomProviderSpec spec = specWith(features, "warn");

        EnumSet<Feature> result = FeatureValidator.validate(
                "my-instance", spec, EnumSet.of(Feature.VISION, Feature.WEB_SEARCH));

        assertEquals(EnumSet.of(Feature.VISION), result);
    }

    @Test
    @DisplayName("validate IGNORE mode drops silently - empty result and no exception")
    void validate_ignoreMode_dropsSilently() {
        Map<String, Boolean> features = new HashMap<>();
        // No supported features
        CustomProviderSpec spec = specWith(features, "ignore");

        EnumSet<Feature> result = FeatureValidator.validate(
                "my-instance", spec, EnumSet.of(Feature.WEB_SEARCH));

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("validate throws IllegalArgumentException when spec is null")
    void validate_nullSpec_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> FeatureValidator.validate("my-instance", null,
                        EnumSet.of(Feature.VISION)));
    }

    @Test
    @DisplayName("validate with null requestedFeatures returns empty EnumSet")
    void validate_nullRequested_returnsEmpty() {
        CustomProviderSpec spec = specWith(new HashMap<>(), "throw");
        EnumSet<Feature> result = FeatureValidator.validate("my-instance", spec, null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("validate with empty requestedFeatures returns empty EnumSet")
    void validate_emptyRequested_returnsEmpty() {
        CustomProviderSpec spec = specWith(new HashMap<>(), "throw");
        EnumSet<Feature> result = FeatureValidator.validate(
                "my-instance", spec, EnumSet.noneOf(Feature.class));
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("supportedFeatures returns the EnumSet of all features flagged true")
    void supportedFeatures_returnsTrueOnes() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("vision", true);
        features.put("function_calling", true);
        features.put("web_search", false);
        CustomProviderSpec spec = specWith(features, "throw");

        EnumSet<Feature> supported = FeatureValidator.supportedFeatures(spec);

        assertEquals(EnumSet.of(Feature.VISION, Feature.FUNCTION_CALLING), supported);
    }
}
