package io.github.yannfavinleveque.agentic.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static pricing table for LLM models. Prices are per 1M tokens in USD.
 * Supports prefix-based matching (e.g. {@code "gpt-4o-2024-08-06"} matches {@code "gpt-4o"}).
 *
 * <p>Pricing is identical across providers (OpenAI direct = Azure OpenAI,
 * Anthropic direct = Azure Anthropic), so only model name matters.</p>
 *
 * <p>Each model has up to 4 rates per 1M tokens: {@code input}, {@code output},
 * {@code cacheCreate} (tokens written into the prompt cache), {@code cacheRead}
 * (tokens served from the prompt cache). Providers that do not support prompt
 * caching have both cache rates at 0 (cache statistics, if ever passed in, are
 * priced at zero and effectively ignored).</p>
 *
 * <p>Anthropic cache pricing (Sonnet/Opus/Haiku 4.x): cacheCreate = 1.25 × input,
 * cacheRead = 0.10 × input. Source: https://www.anthropic.com/pricing#api</p>
 *
 * <p>OpenAI cache pricing (GPT-4.x / GPT-5.x / o-series): cacheCreate is not
 * billed separately (kept at 0); cacheRead = 0.10 × input.
 * Source: https://openai.com/api/pricing/</p>
 *
 * <p>Call {@link #calculate(String, Integer, Integer)} to get a {@link TokenUsage}
 * with estimated cost. Cost is {@code null} when the model is not in the table.</p>
 */
public final class ModelPricing {

    private ModelPricing() {
    }

    private static final Map<String, double[]> PRICING = new LinkedHashMap<>();

    static {
        // Entries are sorted by key length descending so longer prefixes match first.
        // Each value is {input, output, cacheCreate, cacheRead} per 1M tokens.
        // ---- OpenAI GPT-5.5 family ----
        // Source: https://developers.openai.com/api/docs/pricing (verified 2026-04)
        // Note: As of 2026-04 only gpt-5.5 and gpt-5.5-pro variants exist; no mini/nano.
        // OpenAI cacheRead = 0.10 × input; cacheCreate not billed separately.
        put("gpt-5.5-pro", 30.00, 180.00, 0.00, 3.00);
        put("gpt-5.5", 5.00, 30.00, 0.00, 0.50);
        // ---- OpenAI GPT-5.4 family ----
        put("gpt-5.4-mini", 0.75, 4.50, 0.00, 0.075);
        put("gpt-5.4-nano", 0.15, 0.90, 0.00, 0.015);
        put("gpt-5.4", 2.50, 15.00, 0.00, 0.25);
        // ---- OpenAI GPT-5.2 family ----
        put("gpt-5.2-pro", 21.00, 168.00, 0.00, 2.10);
        put("gpt-5.2-codex", 1.75, 14.00, 0.00, 0.175);
        put("gpt-5.2-chat", 1.75, 14.00, 0.00, 0.175);
        put("gpt-5.2", 1.75, 14.00, 0.00, 0.175);
        // ---- OpenAI GPT-5.1 family ----
        put("gpt-5.1-codex-mini", 0.25, 2.00, 0.00, 0.025);
        put("gpt-5.1-codex-max", 1.25, 10.00, 0.00, 0.125);
        put("gpt-5.1-codex", 1.25, 10.00, 0.00, 0.125);
        put("gpt-5.1-chat", 1.25, 10.00, 0.00, 0.125);
        put("gpt-5.1", 1.25, 10.00, 0.00, 0.125);
        // ---- OpenAI GPT-5 family ----
        put("gpt-5-nano", 0.05, 0.40, 0.00, 0.005);
        put("gpt-5-mini", 0.25, 2.00, 0.00, 0.025);
        put("gpt-5-codex", 1.25, 10.00, 0.00, 0.125);
        put("gpt-5-chat", 1.25, 10.00, 0.00, 0.125);
        put("gpt-5-pro", 15.00, 120.00, 0.00, 1.50);
        put("gpt-5", 1.25, 10.00, 0.00, 0.125);
        // ---- OpenAI GPT-4.1 family ----
        put("gpt-4.1-nano", 0.10, 0.40, 0.00, 0.01);
        put("gpt-4.1-mini", 0.40, 1.60, 0.00, 0.04);
        put("gpt-4.1", 2.00, 8.00, 0.00, 0.20);
        // ---- OpenAI GPT-4o family ----
        put("gpt-4o-mini", 0.15, 0.60, 0.00, 0.015);
        put("gpt-4o", 2.50, 10.00, 0.00, 0.25);
        // ---- OpenAI o-series ----
        put("o4-mini", 1.10, 4.40, 0.00, 0.11);
        put("o3-mini", 1.10, 4.40, 0.00, 0.11);
        put("o3", 2.00, 8.00, 0.00, 0.20);
        put("o1-mini", 3.00, 12.00, 0.00, 0.30);
        put("o1", 15.00, 60.00, 0.00, 1.50);
        // ---- Anthropic Claude 4.7 ----
        // Anthropic: cacheCreate = 1.25 × input; cacheRead = 0.10 × input.
        put("claude-opus-4-7", 5.00, 25.00, 6.25, 0.50);
        put("claude-sonnet-4-7", 3.00, 15.00, 3.75, 0.30);
        put("claude-haiku-4-7", 1.00, 5.00, 1.25, 0.10);
        // ---- Anthropic Claude 4.6 ----
        put("claude-opus-4-6", 5.00, 25.00, 6.25, 0.50);
        put("claude-sonnet-4-6", 3.00, 15.00, 3.75, 0.30);
        put("claude-haiku-4-6", 1.00, 5.00, 1.25, 0.10);
        // ---- Anthropic Claude 4.5 ----
        put("claude-opus-4-5", 5.00, 25.00, 6.25, 0.50);
        put("claude-sonnet-4-5", 3.00, 15.00, 3.75, 0.30);
        put("claude-haiku-4-5", 1.00, 5.00, 1.25, 0.10);
        // ---- Anthropic Claude 4.x ----
        put("claude-opus-4-1", 15.00, 75.00, 18.75, 1.50);
        put("claude-opus-4", 15.00, 75.00, 18.75, 1.50);
        put("claude-sonnet-4", 3.00, 15.00, 3.75, 0.30);
        // ---- Anthropic Claude 3.x ----
        put("claude-3-5-sonnet", 3.00, 15.00, 3.75, 0.30);
        put("claude-3-5-haiku", 1.00, 5.00, 1.25, 0.10);
        put("claude-3-haiku", 0.25, 1.25, 0.3125, 0.025);
        // ---- Mistral AI ----
        // Source: https://mistral.ai/pricing (verified 2026-04)
        // Mistral does not support prompt caching in this lib — cache rates = 0.
        put("mistral-large-latest", 2.00, 6.00);
        put("mistral-large", 2.00, 6.00);
        put("mistral-medium-latest", 0.40, 2.00);
        put("mistral-medium", 0.40, 2.00);
        put("mistral-small-latest", 0.20, 0.60);
        put("mistral-small", 0.20, 0.60);
        put("ministral-8b-latest", 0.10, 0.10);
        put("ministral-3b-latest", 0.04, 0.04);
        put("pixtral-large-latest", 2.00, 6.00);
        put("pixtral-large", 2.00, 6.00);
        put("pixtral-12b", 0.15, 0.15);
        put("codestral-latest", 0.30, 0.90);
        put("codestral", 0.30, 0.90);
        put("magistral-medium-latest", 2.00, 5.00);
        put("magistral-small-latest", 0.50, 1.50);
        put("mistral-embed", 0.10, 0.00);
        // ---- xAI Grok ----
        // Source: https://docs.x.ai/developers/models (verified 2026-04)
        // grok-4.20 is the current flagship (released 2026-03-31).
        // Grok caching not supported in this lib — cache rates = 0.
        put("grok-4.20", 2.00, 6.00);
        put("grok-4.1-fast", 0.20, 0.50);
        put("grok-4-fast-reasoning", 0.20, 0.50);
        put("grok-4-fast-non-reasoning", 0.20, 0.50);
        put("grok-4-fast", 0.20, 0.50);
        put("grok-4", 3.00, 15.00);
        put("grok-3-mini", 0.30, 0.50);
        put("grok-3", 3.00, 15.00);
        put("grok-2-vision-1212", 2.00, 10.00);
        put("grok-2-image-1212", 0.07, 0.00);  // image generation, "input" = tokens, "output" = n/a
        put("grok-2-1212", 2.00, 10.00);
        put("grok-code-fast-1", 0.20, 1.50);
        // ---- DeepSeek ----
        // Source: https://api-docs.deepseek.com/quick_start/pricing (verified 2026-04, cache miss prices)
        // Note: As of 2025-09-29 deepseek-chat and deepseek-reasoner have unified pricing.
        // DeepSeek does have a cache-hit rate (~$0.028/M) but it is not surfaced via the
        // OpenAI-compat shim this lib uses — cache rates left at 0 until that lands.
        put("deepseek-chat", 0.28, 0.42);
        put("deepseek-reasoner", 0.28, 0.42);
        put("deepseek-coder", 0.28, 0.42);
        put("deepseek-v4-flash", 0.14, 0.28);
        put("deepseek-v4-pro", 1.74, 3.48);
        // ---- Google Gemini ----
        // Source: https://ai.google.dev/gemini-api/docs/pricing (verified 2026-04)
        // For >200K context, prices double approximately. We list flat pricing for the standard tier.
        // Gemini caching not surfaced via OpenAI-compat shim — cache rates = 0.
        put("gemini-2.5-flash-lite", 0.10, 0.40);
        put("gemini-2.5-flash", 0.30, 2.50);
        put("gemini-2.5-pro", 1.25, 10.00);
        put("gemini-2.0-flash-thinking-exp", 0.10, 0.40);
        put("gemini-2.0-flash-lite", 0.075, 0.30);
        put("gemini-2.0-flash", 0.10, 0.40);
        put("gemini-1.5-pro", 1.25, 5.00);
        put("gemini-1.5-flash", 0.075, 0.30);
        put("gemini-embedding-001", 0.025, 0.00);
        put("text-embedding-004", 0.025, 0.00);
        // ---- Embeddings ----
        put("text-embedding-3-small", 0.02, 0.00);
        put("text-embedding-3-large", 0.13, 0.00);
        put("text-embedding-ada", 0.10, 0.00);
    }

    /**
     * Two-rate entry: providers that don't support prompt caching in this lib.
     * cacheCreate and cacheRead default to 0 (cache tokens, if any, are priced at zero).
     */
    private static void put(String prefix, double inputPrice, double outputPrice) {
        put(prefix, inputPrice, outputPrice, 0.0, 0.0);
    }

    /**
     * Four-rate entry: providers with prompt caching.
     * Rates are per 1M tokens, in USD.
     */
    private static void put(String prefix, double inputPrice, double outputPrice,
            double cacheCreatePrice, double cacheReadPrice) {
        PRICING.put(prefix, new double[] { inputPrice, outputPrice, cacheCreatePrice, cacheReadPrice });
    }

    /**
     * Builds a {@link TokenUsage} with estimated cost. Bridge to the new
     * {@link #calculate(String, Integer, Integer, Integer, Integer)} overload with
     * {@code cacheCreate} and {@code cacheRead} set to {@code null} (no cache info).
     *
     * @param model        Model name (e.g. {@code "gpt-4o-2024-08-06"}, {@code "claude-sonnet-4-6"})
     * @param inputTokens  Number of input tokens (nullable)
     * @param outputTokens Number of output tokens (nullable)
     * @return TokenUsage with cost, or with {@code estimatedCostUsd=null} if model is unknown
     */
    public static TokenUsage calculate(String model, Integer inputTokens, Integer outputTokens) {
        return calculate(model, inputTokens, outputTokens, null, null);
    }

    /**
     * Builds a {@link TokenUsage} with estimated cost, accounting for prompt cache tokens.
     *
     * @param model               Model name
     * @param inputTokens         Number of uncached input tokens (nullable)
     * @param outputTokens        Number of output tokens (nullable)
     * @param cacheCreationTokens Tokens written into the cache (nullable; treated as 0 for pricing)
     * @param cacheReadTokens     Tokens served from the cache (nullable; treated as 0 for pricing)
     * @return TokenUsage with cost, or with {@code estimatedCostUsd=null} if model is unknown
     */
    public static TokenUsage calculate(String model, Integer inputTokens, Integer outputTokens,
            Integer cacheCreationTokens, Integer cacheReadTokens) {
        int in = inputTokens != null ? inputTokens : 0;
        int out = outputTokens != null ? outputTokens : 0;
        int cc = cacheCreationTokens != null ? cacheCreationTokens : 0;
        int cr = cacheReadTokens != null ? cacheReadTokens : 0;

        Double cost = null;
        if (model != null) {
            String lower = model.toLowerCase();
            for (Map.Entry<String, double[]> entry : PRICING.entrySet()) {
                if (lower.startsWith(entry.getKey())) {
                    double[] prices = entry.getValue();
                    cost = (in * prices[0] + out * prices[1]
                            + cc * prices[2] + cr * prices[3]) / 1_000_000.0;
                    break;
                }
            }
        }

        return TokenUsage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cacheCreationTokens(cacheCreationTokens)
                .cacheReadTokens(cacheReadTokens)
                .model(model)
                .estimatedCostUsd(cost)
                .build();
    }

    /**
     * Like {@link #calculate(String, Integer, Integer)} but consults a fallback
     * pricing map (typically from {@code CustomProviderSpec#getModelPricing()})
     * when the static table has no match. Bridge to the cache-aware overload
     * with {@code cacheCreate} and {@code cacheRead} set to {@code null}.
     *
     * <p>Lookup order: static table (longest-prefix match) -&gt; fallback map
     * (longest-prefix match) -&gt; {@code cost=null}.</p>
     */
    public static TokenUsage calculate(String model, Integer inputTokens, Integer outputTokens,
            Map<String, PriceEntry> fallback) {
        return calculate(model, inputTokens, outputTokens, null, null, fallback);
    }

    /**
     * Cache-aware version of {@link #calculate(String, Integer, Integer, Map)}.
     * Consults the static table first, then the fallback map. The fallback map
     * only carries input/output rates, so {@code cacheCreate}/{@code cacheRead}
     * tokens are priced at zero when the static table has no match.
     *
     * @param model               Model name
     * @param inputTokens         Number of uncached input tokens (nullable)
     * @param outputTokens        Number of output tokens (nullable)
     * @param cacheCreationTokens Tokens written into the cache (nullable)
     * @param cacheReadTokens     Tokens served from the cache (nullable)
     * @param fallback            Per-model pricing map (can be null or empty)
     */
    public static TokenUsage calculate(String model, Integer inputTokens, Integer outputTokens,
            Integer cacheCreationTokens, Integer cacheReadTokens,
            Map<String, PriceEntry> fallback) {

        int in = inputTokens != null ? inputTokens : 0;
        int out = outputTokens != null ? outputTokens : 0;
        int cc = cacheCreationTokens != null ? cacheCreationTokens : 0;
        int cr = cacheReadTokens != null ? cacheReadTokens : 0;

        Double cost = null;
        if (model != null) {
            String lower = model.toLowerCase();

            // 1) Static table (longest-prefix match).
            for (Map.Entry<String, double[]> entry : PRICING.entrySet()) {
                if (lower.startsWith(entry.getKey())) {
                    double[] p = entry.getValue();
                    cost = (in * p[0] + out * p[1] + cc * p[2] + cr * p[3]) / 1_000_000.0;
                    break;
                }
            }

            // 2) Fallback map (longest-prefix match). Cache tokens priced at zero
            // because PriceEntry only carries input/output rates.
            if (cost == null && fallback != null && !fallback.isEmpty()) {
                String bestKey = null;
                for (String key : fallback.keySet()) {
                    if (key == null) {
                        continue;
                    }
                    if (lower.startsWith(key.toLowerCase())) {
                        if (bestKey == null || key.length() > bestKey.length()) {
                            bestKey = key;
                        }
                    }
                }
                if (bestKey != null) {
                    PriceEntry p = fallback.get(bestKey);
                    if (p != null) {
                        cost = (in * p.getInput() + out * p.getOutput()) / 1_000_000.0;
                    }
                }
            }
        }

        return TokenUsage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cacheCreationTokens(cacheCreationTokens)
                .cacheReadTokens(cacheReadTokens)
                .model(model)
                .estimatedCostUsd(cost)
                .build();
    }

    /**
     * Input/output pricing for a single model, in USD per 1M tokens.
     * Used as the value type in {@code CustomProviderSpec.getModelPricing()}.
     */
    public static class PriceEntry {

        @com.fasterxml.jackson.annotation.JsonProperty("input")
        private double input;

        @com.fasterxml.jackson.annotation.JsonProperty("output")
        private double output;

        public PriceEntry() {}

        public PriceEntry(double input, double output) {
            this.input = input;
            this.output = output;
        }

        public double getInput() { return input; }
        public double getOutput() { return output; }
        public void setInput(double input) { this.input = input; }
        public void setOutput(double output) { this.output = output; }
    }

    /**
     * Formats a {@link TokenUsage} for logging.
     *
     * <p>When the usage carries cache token counts, they are appended in parentheses:
     * {@code "Tokens: 50->10 (cc=200 cr=500) | Cost: $0.001234"}. The cache segment
     * is omitted entirely when both {@code cacheCreationTokens} and {@code cacheReadTokens}
     * are {@code null} or zero, to keep log lines short for non-caching providers.</p>
     *
     * @return e.g. {@code "Tokens: 150->50 | Cost: $0.001250"} or
     *         {@code "Tokens: 150->50 (cc=200 cr=500) | Cost: $0.001250"} or
     *         {@code "Tokens: 150->50 | Cost: N/A"}
     */
    public static String formatForLog(TokenUsage usage) {
        if (usage == null) {
            return "";
        }
        StringBuilder tokens = new StringBuilder("Tokens: ")
                .append(usage.getInputTokens() != null ? usage.getInputTokens() : "?")
                .append("->")
                .append(usage.getOutputTokens() != null ? usage.getOutputTokens() : "?");
        Integer cc = usage.getCacheCreationTokens();
        Integer cr = usage.getCacheReadTokens();
        boolean hasCacheInfo = (cc != null && cc > 0) || (cr != null && cr > 0);
        if (hasCacheInfo) {
            tokens.append(" (cc=").append(cc != null ? cc : 0)
                    .append(" cr=").append(cr != null ? cr : 0)
                    .append(')');
        }
        String cost = usage.getEstimatedCostUsd() != null
                ? String.format("Cost: $%.6f", usage.getEstimatedCostUsd())
                : "Cost: N/A";
        return tokens + " | " + cost;
    }
}
