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
        // 5.00 * 1000/1e6 + 30.00 * 500/1e6 = 0.005 + 0.015 = 0.020
        assertEquals(0.020, usage.getEstimatedCostUsd(), EPS);
    }

    @Test
    @DisplayName("calculate('gpt-5.5-pro', 1000, 500) returns non-null cost")
    void calculate_gpt55_pro_nonNull() {
        TokenUsage usage = ModelPricing.calculate("gpt-5.5-pro", 1000, 500);
        assertNotNull(usage.getEstimatedCostUsd());
        // 30.00 * 1000/1e6 + 180.00 * 500/1e6 = 0.030 + 0.090 = 0.120
        assertEquals(0.120, usage.getEstimatedCostUsd(), EPS);
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
        // Must come from the static table (5.00/30.00), not the fallback.
        assertEquals(0.020, usage.getEstimatedCostUsd(), EPS);
        // Sanity: must NOT match the bogus fallback price (which would be ~0.0495).
        assertTrue(usage.getEstimatedCostUsd() < 0.025);
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

    // ---------------- Cache-aware pricing ----------------

    @Test
    @DisplayName("calculate('claude-sonnet-4-5', in=100, out=50, cc=1000, cr=500) prices all four buckets")
    void calculate_anthropicWithCache() {
        TokenUsage usage = ModelPricing.calculate("claude-sonnet-4-5", 100, 50, 1000, 500);
        assertNotNull(usage.getEstimatedCostUsd());
        // Sonnet 4.5: in=3.00, out=15.00, cc=3.75 (1.25x), cr=0.30 (0.10x).
        // = (100*3.00 + 50*15.00 + 1000*3.75 + 500*0.30) / 1e6
        // = (300 + 750 + 3750 + 150) / 1e6 = 4950 / 1e6 = 0.00495
        assertEquals(0.00495, usage.getEstimatedCostUsd(), EPS);
        assertEquals(100, usage.getInputTokens());
        assertEquals(50, usage.getOutputTokens());
        assertEquals(1000, usage.getCacheCreationTokens());
        assertEquals(500, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("calculate('claude-opus-4-7', cache only) reflects 0.50/M cache-read rate")
    void calculate_anthropicOpusCacheReadRate() {
        TokenUsage usage = ModelPricing.calculate("claude-opus-4-7", 0, 0, 0, 1_000_000);
        // Opus 4.7 cacheRead = 0.50 -> 0.50 USD for 1M cache-read tokens.
        assertEquals(0.50, usage.getEstimatedCostUsd(), EPS);
    }

    @Test
    @DisplayName("calculate('gpt-5.4', in=100, out=50, cc=0, cr=500): cacheCreate not billed, cacheRead at 0.10x input")
    void calculate_openaiWithCache() {
        TokenUsage usage = ModelPricing.calculate("gpt-5.4", 100, 50, 0, 500);
        assertNotNull(usage.getEstimatedCostUsd());
        // gpt-5.4: in=2.50, out=15.00, cc=0, cr=0.25 (0.10x of 2.50).
        // = (100*2.50 + 50*15.00 + 0 + 500*0.25) / 1e6
        // = (250 + 750 + 125) / 1e6 = 1125 / 1e6 = 0.001125
        assertEquals(0.001125, usage.getEstimatedCostUsd(), EPS);
        assertEquals(0, usage.getCacheCreationTokens());
        assertEquals(500, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("calculate(... null cache args) yields identical cost to legacy 3-arg API (backwards-compat)")
    void calculate_nullCacheArgs_matchesLegacyApi() {
        TokenUsage legacy = ModelPricing.calculate("claude-sonnet-4-5", 1000, 500);
        TokenUsage newApi = ModelPricing.calculate("claude-sonnet-4-5", 1000, 500, null, null);
        assertEquals(legacy.getEstimatedCostUsd(), newApi.getEstimatedCostUsd(), EPS);
        // Legacy API leaves cache fields null on the resulting TokenUsage.
        assertNull(legacy.getCacheCreationTokens());
        assertNull(legacy.getCacheReadTokens());
    }

    @Test
    @DisplayName("calculate(mistral-large, with cache tokens) ignores cache (rates=0), prices only in/out")
    void calculate_mistralIgnoresCache() {
        TokenUsage usage = ModelPricing.calculate("mistral-large", 100, 50, 200, 100);
        assertNotNull(usage.getEstimatedCostUsd());
        // mistral-large: in=2.00, out=6.00, cc=0, cr=0.
        // = (100*2.00 + 50*6.00) / 1e6 = (200 + 300) / 1e6 = 500 / 1e6 = 0.0005
        assertEquals(0.0005, usage.getEstimatedCostUsd(), EPS);
        // Cache token counts ARE still preserved on the TokenUsage for observability.
        assertEquals(200, usage.getCacheCreationTokens());
        assertEquals(100, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("calculate(... cache args, fallback) prices in/out only when fallback hits (PriceEntry has no cache rates)")
    void calculate_fallbackIgnoresCacheTokens() {
        Map<String, ModelPricing.PriceEntry> fallback = new HashMap<>();
        fallback.put("my-private-llm", new ModelPricing.PriceEntry(1.0, 5.0));
        TokenUsage usage = ModelPricing.calculate("my-private-llm", 1000, 500, 999, 999, fallback);
        assertNotNull(usage.getEstimatedCostUsd());
        // Only input/output priced via fallback: 1.0 * 1000/1e6 + 5.0 * 500/1e6 = 0.0035
        assertEquals(0.0035, usage.getEstimatedCostUsd(), EPS);
        assertEquals(999, usage.getCacheCreationTokens());
        assertEquals(999, usage.getCacheReadTokens());
    }

    // ---------------- formatForLog ----------------

    @Test
    @DisplayName("formatForLog without cache info omits the cache segment")
    void formatForLog_withoutCache() {
        TokenUsage usage = ModelPricing.calculate("claude-sonnet-4-5", 150, 50);
        String s = ModelPricing.formatForLog(usage);
        assertTrue(s.contains("Tokens: 150->50"), s);
        assertTrue(s.contains("Cost: $"), s);
        assertTrue(!s.contains("(cc="), s);
    }

    @Test
    @DisplayName("formatForLog with cache tokens appends (cc=... cr=...)")
    void formatForLog_withCache() {
        TokenUsage usage = ModelPricing.calculate("claude-sonnet-4-5", 100, 50, 200, 500);
        String s = ModelPricing.formatForLog(usage);
        assertTrue(s.contains("Tokens: 100->50"), s);
        assertTrue(s.contains("(cc=200 cr=500)"), s);
    }

    @Test
    @DisplayName("formatForLog hides cache segment when both counts are 0")
    void formatForLog_zeroCacheHidden() {
        TokenUsage usage = ModelPricing.calculate("gpt-5.4", 100, 50, 0, 0);
        String s = ModelPricing.formatForLog(usage);
        assertTrue(!s.contains("(cc="), s);
    }

    @Test
    @DisplayName("TokenUsage.accumulate sums cache counts")
    void tokenUsage_accumulate_sumsCacheCounts() {
        TokenUsage a = TokenUsage.builder()
                .inputTokens(10).outputTokens(20)
                .cacheCreationTokens(100).cacheReadTokens(200)
                .build();
        TokenUsage b = TokenUsage.builder()
                .inputTokens(5).outputTokens(7)
                .cacheCreationTokens(50).cacheReadTokens(75)
                .build();
        a.accumulate(b);
        assertEquals(15, a.getInputTokens());
        assertEquals(27, a.getOutputTokens());
        assertEquals(150, a.getCacheCreationTokens());
        assertEquals(275, a.getCacheReadTokens());
    }
}
