package io.github.yannfavinleveque.agentic.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ModelPricing}.
 *
 * <p>Covers the new GPT-5.5 / Claude 4.7 entries, the new {@link ModelPricing.PriceEntry}
 * fallback overload, longest-prefix matching in the fallback, and the precedence rule
 * (static table beats fallback).</p>
 */
class ModelPricingTest {

    private static final double EPS = 1e-9;

    // ---------------- New static-table entries ----------------

    @Test
    @DisplayName("calculate('gpt-5.5', 1000, 500) returns non-null cost")
    void calculate_gpt55_nonNull() {
        TokenUsage usage = ModelPricing.calculate("gpt-5.5", 1000, 500);
        assertNotNull(usage.getEstimatedCostUsd());
        // 2.00 * 1000/1e6 + 12.00 * 500/1e6 = 0.002 + 0.006 = 0.008
        assertEquals(0.008, usage.getEstimatedCostUsd(), EPS);
    }

    @Test
    @DisplayName("calculate('gpt-5.5-mini', 1000, 500) returns non-null cost")
    void calculate_gpt55_mini_nonNull() {
        TokenUsage usage = ModelPricing.calculate("gpt-5.5-mini", 1000, 500);
        assertNotNull(usage.getEstimatedCostUsd());
        // 0.50 * 1000/1e6 + 3.00 * 500/1e6 = 0.0005 + 0.0015 = 0.002
        assertEquals(0.002, usage.getEstimatedCostUsd(), EPS);
    }

    @Test
    @DisplayName("calculate('claude-opus-4-7', 1000, 500) returns non-null cost")
    void calculate_claudeOpus47_nonNull() {
        TokenUsage usage = ModelPricing.calculate("claude-opus-4-7", 1000, 500);
        assertNotNull(usage.getEstimatedCostUsd());
        // 5.00 * 1000/1e6 + 25.00 * 500/1e6 = 0.005 + 0.0125 = 0.0175
        assertEquals(0.0175, usage.getEstimatedCostUsd(), EPS);
    }

    @Test
    @DisplayName("calculate('claude-sonnet-4-7', 1000, 500) returns non-null cost")
    void calculate_claudeSonnet47_nonNull() {
        TokenUsage usage = ModelPricing.calculate("claude-sonnet-4-7", 1000, 500);
        assertNotNull(usage.getEstimatedCostUsd());
        // 3.00 * 1000/1e6 + 15.00 * 500/1e6 = 0.003 + 0.0075 = 0.0105
        assertEquals(0.0105, usage.getEstimatedCostUsd(), EPS);
    }

    @Test
    @DisplayName("calculate('claude-haiku-4-7', 1000, 500) returns non-null cost")
    void calculate_claudeHaiku47_nonNull() {
        TokenUsage usage = ModelPricing.calculate("claude-haiku-4-7", 1000, 500);
        assertNotNull(usage.getEstimatedCostUsd());
        // 1.00 * 1000/1e6 + 5.00 * 500/1e6 = 0.001 + 0.0025 = 0.0035
        assertEquals(0.0035, usage.getEstimatedCostUsd(), EPS);
    }

    @Test
    @DisplayName("calculate('claude-haiku-4-6', 1000, 500) returns non-null cost")
    void calculate_claudeHaiku46_nonNull() {
        TokenUsage usage = ModelPricing.calculate("claude-haiku-4-6", 1000, 500);
        assertNotNull(usage.getEstimatedCostUsd());
        assertEquals(0.0035, usage.getEstimatedCostUsd(), EPS);
    }

    // ---------------- Fallback overload ----------------

    @Test
    @DisplayName("calculate(unknown model, ..., null fallback) returns null cost")
    void calculate_unknownModel_nullFallback_returnsNullCost() {
        TokenUsage usage = ModelPricing.calculate("unknown-model", 1000, 500, null);
        assertNull(usage.getEstimatedCostUsd());
    }

    @Test
    @DisplayName("calculate(unknown model, ..., empty fallback) returns null cost")
    void calculate_unknownModel_emptyFallback_returnsNullCost() {
        TokenUsage usage = ModelPricing.calculate("unknown-model", 1000, 500, Collections.emptyMap());
        assertNull(usage.getEstimatedCostUsd());
    }

    @Test
    @DisplayName("calculate consults fallback map when static table has no match")
    void calculate_unknownModel_inFallback_returnsCost() {
        Map<String, ModelPricing.PriceEntry> fallback = new HashMap<>();
        fallback.put("my-private-llm", new ModelPricing.PriceEntry(1.0, 5.0));
        TokenUsage usage = ModelPricing.calculate("my-private-llm", 1000, 500, fallback);
        assertNotNull(usage.getEstimatedCostUsd());
        // 1.0 * 1000/1e6 + 5.0 * 500/1e6 = 0.001 + 0.0025 = 0.0035
        assertEquals(0.0035, usage.getEstimatedCostUsd(), EPS);
    }

    @Test
    @DisplayName("fallback prefix-match: 'my-private-llm-v2' matches 'my-private-llm'")
    void calculate_fallbackPrefixMatch() {
        Map<String, ModelPricing.PriceEntry> fallback = new HashMap<>();
        fallback.put("my-private-llm", new ModelPricing.PriceEntry(1.0, 5.0));
        TokenUsage usage = ModelPricing.calculate("my-private-llm-v2", 1000, 500, fallback);
        assertNotNull(usage.getEstimatedCostUsd());
        assertEquals(0.0035, usage.getEstimatedCostUsd(), EPS);
    }

    @Test
    @DisplayName("fallback longest-prefix wins over shorter prefix")
    void calculate_fallbackLongestPrefixWins() {
        // Use LinkedHashMap so the shorter key is iterated first — ensures the longest-key
        // selection logic (not iteration order) is what picks the winner.
        Map<String, ModelPricing.PriceEntry> fallback = new LinkedHashMap<>();
        fallback.put("my-private-llm", new ModelPricing.PriceEntry(1.0, 5.0));
        fallback.put("my-private-llm-v2", new ModelPricing.PriceEntry(2.0, 10.0));
        TokenUsage usage = ModelPricing.calculate("my-private-llm-v2", 1000, 500, fallback);
        assertNotNull(usage.getEstimatedCostUsd());
        // longest prefix is "my-private-llm-v2" -> 2.0/10.0 -> 0.002 + 0.005 = 0.007
        assertEquals(0.007, usage.getEstimatedCostUsd(), EPS);
    }

    @Test
    @DisplayName("static table takes precedence over fallback")
    void calculate_staticTableBeatsFallback() {
        Map<String, ModelPricing.PriceEntry> fallback = new HashMap<>();
        // Bogus huge fallback for an already-known model — must be ignored.
        fallback.put("gpt-5.5", new ModelPricing.PriceEntry(99.0, 99.0));
        TokenUsage usage = ModelPricing.calculate("gpt-5.5", 1000, 500, fallback);
        assertNotNull(usage.getEstimatedCostUsd());
        // Must come from the static table (2.00/12.00), not the fallback.
        assertEquals(0.008, usage.getEstimatedCostUsd(), EPS);
        // Sanity: must NOT match the bogus fallback price (which would be ~0.0495).
        assertTrue(usage.getEstimatedCostUsd() < 0.01);
    }

    // ---------------- PriceEntry POJO ----------------

    @Test
    @DisplayName("PriceEntry getters / setters round-trip")
    void priceEntry_gettersSetters() {
        ModelPricing.PriceEntry p = new ModelPricing.PriceEntry();
        p.setInput(1.5);
        p.setOutput(7.5);
        assertEquals(1.5, p.getInput(), EPS);
        assertEquals(7.5, p.getOutput(), EPS);

        ModelPricing.PriceEntry q = new ModelPricing.PriceEntry(2.0, 8.0);
        assertEquals(2.0, q.getInput(), EPS);
        assertEquals(8.0, q.getOutput(), EPS);
    }
}
