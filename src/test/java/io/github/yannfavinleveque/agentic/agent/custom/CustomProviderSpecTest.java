package io.github.yannfavinleveque.agentic.agent.custom;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yannfavinleveque.agentic.common.ModelPricing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Tests for {@link CustomProviderSpec}.
 */
class CustomProviderSpecTest {

    private CustomProviderSpec validSpec() {
        Map<String, String> endpoints = new HashMap<>();
        endpoints.put("chat_completions", "/v1/chat/completions");
        return CustomProviderSpec.builder()
                .apiFormat("openai-chat")
                .auth(AuthSpec.builder().header("Authorization").format("Bearer {key}").build())
                .endpoints(endpoints)
                .build();
    }

    // ---------------- supports(Feature) ----------------

    @Test
    @DisplayName("supports(VISION) returns true when features contains 'vision' (lowercase)")
    void supports_vision_lowercase_true() {
        CustomProviderSpec spec = CustomProviderSpec.builder()
                .features(Collections.singletonMap("vision", true))
                .build();
        assertTrue(spec.supports(Feature.VISION));
    }

    @Test
    @DisplayName("supports(VISION) returns false when features key is uppercase 'VISION' (lookup is lowercase)")
    void supports_vision_uppercase_false() {
        CustomProviderSpec spec = CustomProviderSpec.builder()
                .features(Collections.singletonMap("VISION", true))
                .build();
        assertFalse(spec.supports(Feature.VISION));
    }

    @Test
    @DisplayName("supports(FUNCTION_CALLING) true with snake_case key 'function_calling'")
    void supports_functionCalling_snakeCase_true() {
        CustomProviderSpec spec = CustomProviderSpec.builder()
                .features(Collections.singletonMap("function_calling", true))
                .build();
        assertTrue(spec.supports(Feature.FUNCTION_CALLING));
    }

    @Test
    @DisplayName("supports(FUNCTION_CALLING) true with camelCase key 'functionCalling'")
    void supports_functionCalling_camelCase_true() {
        CustomProviderSpec spec = CustomProviderSpec.builder()
                .features(Collections.singletonMap("functionCalling", true))
                .build();
        assertTrue(spec.supports(Feature.FUNCTION_CALLING));
    }

    @Test
    @DisplayName("supports(WEB_SEARCH) returns false when features map empty")
    void supports_webSearch_emptyFeatures_false() {
        CustomProviderSpec spec = CustomProviderSpec.builder()
                .features(new HashMap<>())
                .build();
        assertFalse(spec.supports(Feature.WEB_SEARCH));
    }

    @Test
    @DisplayName("supports(WEB_SEARCH) returns false when explicitly set to false")
    void supports_webSearch_explicitFalse_returnsFalse() {
        CustomProviderSpec spec = CustomProviderSpec.builder()
                .features(Collections.singletonMap("web_search", false))
                .build();
        assertFalse(spec.supports(Feature.WEB_SEARCH));
    }

    // ---------------- getLenientMode() ----------------

    @Test
    @DisplayName("getLenientMode default (null) returns THROW")
    void getLenientMode_null_returnsThrow() {
        CustomProviderSpec spec = CustomProviderSpec.builder().build();
        assertEquals(LenientMode.THROW, spec.getLenientMode());
    }

    @Test
    @DisplayName("getLenientMode 'warn' returns WARN")
    void getLenientMode_warn_returnsWarn() {
        CustomProviderSpec spec = CustomProviderSpec.builder()
                .onUnsupportedFeature("warn")
                .build();
        assertEquals(LenientMode.WARN, spec.getLenientMode());
    }

    @Test
    @DisplayName("getLenientMode 'IGNORE' (uppercase) returns IGNORE")
    void getLenientMode_uppercaseIgnore_returnsIgnore() {
        CustomProviderSpec spec = CustomProviderSpec.builder()
                .onUnsupportedFeature("IGNORE")
                .build();
        assertEquals(LenientMode.IGNORE, spec.getLenientMode());
    }

    @Test
    @DisplayName("getLenientMode of unknown value returns THROW (fallback)")
    void getLenientMode_unknown_returnsThrow() {
        CustomProviderSpec spec = CustomProviderSpec.builder()
                .onUnsupportedFeature("random")
                .build();
        assertEquals(LenientMode.THROW, spec.getLenientMode());
    }

    // ---------------- validate() ----------------

    @Test
    @DisplayName("validate throws when apiFormat is null")
    void validate_nullApiFormat_throws() {
        CustomProviderSpec spec = validSpec();
        spec.setApiFormat(null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> spec.validate("inst"));
        assertTrue(ex.getMessage().contains("inst"));
        assertTrue(ex.getMessage().toLowerCase().contains("apiformat"));
    }

    @Test
    @DisplayName("validate throws when apiFormat is invalid")
    void validate_invalidApiFormat_throws() {
        CustomProviderSpec spec = validSpec();
        spec.setApiFormat("not-a-format");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> spec.validate("inst"));
        assertTrue(ex.getMessage().contains("inst"));
        assertTrue(ex.getMessage().contains("not-a-format"));
    }

    @Test
    @DisplayName("validate throws when auth is null")
    void validate_nullAuth_throws() {
        CustomProviderSpec spec = validSpec();
        spec.setAuth(null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> spec.validate("inst"));
        assertTrue(ex.getMessage().contains("inst"));
        assertTrue(ex.getMessage().toLowerCase().contains("auth"));
    }

    @Test
    @DisplayName("validate throws when auth.header is empty")
    void validate_emptyAuthHeader_throws() {
        CustomProviderSpec spec = validSpec();
        spec.setAuth(AuthSpec.builder().header("").format("Bearer {key}").build());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> spec.validate("inst"));
        assertTrue(ex.getMessage().toLowerCase().contains("auth.header"));
    }

    @Test
    @DisplayName("validate throws when endpoints map is empty")
    void validate_emptyEndpoints_throws() {
        CustomProviderSpec spec = validSpec();
        spec.setEndpoints(new HashMap<>());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> spec.validate("inst"));
        assertTrue(ex.getMessage().toLowerCase().contains("endpoints"));
    }

    @Test
    @DisplayName("validate passes for fully populated spec")
    void validate_validSpec_doesNotThrow() {
        CustomProviderSpec spec = validSpec();
        assertDoesNotThrow(() -> spec.validate("inst"));
    }

    // ---------------- accessor views ----------------

    @Test
    @DisplayName("getEndpointPath returns declared path; null for unknown")
    void getEndpointPath_returnsValueOrNull() {
        CustomProviderSpec spec = validSpec();
        assertEquals("/v1/chat/completions", spec.getEndpointPath("chat_completions"));
        assertEquals(null, spec.getEndpointPath("unknown_endpoint"));
    }

    // ---------------- modelPricing ----------------

    @Test
    @DisplayName("modelPricing builder accepts a non-empty map")
    void modelPricing_nonEmpty_returnsPopulatedMap() {
        Map<String, ModelPricing.PriceEntry> pricing = new HashMap<>();
        pricing.put("my-private-llm", new ModelPricing.PriceEntry(1.0, 5.0));
        pricing.put("my-private-llm-mini", new ModelPricing.PriceEntry(0.2, 0.8));

        CustomProviderSpec spec = CustomProviderSpec.builder()
                .modelPricing(pricing)
                .build();

        assertTrue(spec.getModelPricing().size() > 0);
        assertEquals(2, spec.getModelPricing().size());
    }

    @Test
    @DisplayName("default modelPricing is empty map (not null) when not declared")
    void modelPricing_default_emptyMapNotNull() {
        CustomProviderSpec spec = CustomProviderSpec.builder().build();
        assertNotNull(spec.getModelPricing());
        assertTrue(spec.getModelPricing().isEmpty());
    }

    @Test
    @DisplayName("getModelPricingView returns unmodifiable map")
    void getModelPricingView_isUnmodifiable() {
        Map<String, ModelPricing.PriceEntry> pricing = new HashMap<>();
        pricing.put("my-llm", new ModelPricing.PriceEntry(1.0, 5.0));

        CustomProviderSpec spec = CustomProviderSpec.builder()
                .modelPricing(pricing)
                .build();

        Map<String, ModelPricing.PriceEntry> view = spec.getModelPricingView();
        assertNotNull(view);
        assertEquals(1, view.size());
        assertThrows(UnsupportedOperationException.class,
                () -> view.put("evil", new ModelPricing.PriceEntry(99, 99)));
    }

    @Test
    @DisplayName("modelPricing deserializes from JSON")
    void modelPricing_jsonRoundTrip() throws Exception {
        String json = "{"
                + "\"apiFormat\":\"openai-chat\","
                + "\"modelPricing\":{"
                + "  \"my-private-llm\": { \"input\": 1.5, \"output\": 5.0 },"
                + "  \"my-private-llm-mini\": { \"input\": 0.2, \"output\": 0.8 }"
                + "}}";
        ObjectMapper mapper = new ObjectMapper();
        CustomProviderSpec spec = mapper.readValue(json, CustomProviderSpec.class);

        assertNotNull(spec.getModelPricing());
        assertEquals(2, spec.getModelPricing().size());
        ModelPricing.PriceEntry pe = spec.getModelPricing().get("my-private-llm");
        assertNotNull(pe);
        assertEquals(1.5, pe.getInput(), 1e-9);
        assertEquals(5.0, pe.getOutput(), 1e-9);
    }

    @Test
    @DisplayName("spec without modelPricing JSON deserializes to empty map")
    void modelPricing_missingInJson_emptyMap() throws Exception {
        String json = "{\"apiFormat\":\"openai-chat\"}";
        ObjectMapper mapper = new ObjectMapper();
        CustomProviderSpec spec = mapper.readValue(json, CustomProviderSpec.class);

        assertNotNull(spec.getModelPricing());
        assertTrue(spec.getModelPricing().isEmpty());
    }
}
