package io.github.yannfavinleveque.agentic.common;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static pricing table for LLM models. Prices are per 1M tokens in USD.
 * Supports prefix-based matching (e.g. {@code "gpt-4o-2024-08-06"} matches {@code "gpt-4o"}).
 *
 * <p>Pricing is identical across providers (OpenAI direct = Azure OpenAI,
 * Anthropic direct = Azure Anthropic), so only model name matters.</p>
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
        // Each value is {inputPricePerMTok, outputPricePerMTok}.
        // ---- OpenAI GPT-5.5 family ----
        // Source: https://developers.openai.com/api/docs/pricing (verified 2026-04)
        // Note: As of 2026-04 only gpt-5.5 and gpt-5.5-pro variants exist; no mini/nano.
        put("gpt-5.5-pro", 30.00, 180.00);
        put("gpt-5.5", 5.00, 30.00);
        // ---- OpenAI GPT-5.4 family ----
        put("gpt-5.4-mini", 0.75, 4.50);
        put("gpt-5.4-nano", 0.15, 0.90);
        put("gpt-5.4", 2.50, 15.00);
        // ---- OpenAI GPT-5.2 family ----
        put("gpt-5.2-pro", 21.00, 168.00);
        put("gpt-5.2-codex", 1.75, 14.00);
        put("gpt-5.2-chat", 1.75, 14.00);
        put("gpt-5.2", 1.75, 14.00);
        // ---- OpenAI GPT-5.1 family ----
        put("gpt-5.1-codex-mini", 0.25, 2.00);
        put("gpt-5.1-codex-max", 1.25, 10.00);
        put("gpt-5.1-codex", 1.25, 10.00);
        put("gpt-5.1-chat", 1.25, 10.00);
        put("gpt-5.1", 1.25, 10.00);
        // ---- OpenAI GPT-5 family ----
        put("gpt-5-nano", 0.05, 0.40);
        put("gpt-5-mini", 0.25, 2.00);
        put("gpt-5-codex", 1.25, 10.00);
        put("gpt-5-chat", 1.25, 10.00);
        put("gpt-5-pro", 15.00, 120.00);
        put("gpt-5", 1.25, 10.00);
        // ---- OpenAI GPT-4.1 family ----
        put("gpt-4.1-nano", 0.10, 0.40);
        put("gpt-4.1-mini", 0.40, 1.60);
        put("gpt-4.1", 2.00, 8.00);
        // ---- OpenAI GPT-4o family ----
        put("gpt-4o-mini", 0.15, 0.60);
        put("gpt-4o", 2.50, 10.00);
        // ---- OpenAI o-series ----
        put("o4-mini", 1.10, 4.40);
        put("o3-mini", 1.10, 4.40);
        put("o3", 2.00, 8.00);
        put("o1-mini", 3.00, 12.00);
        put("o1", 15.00, 60.00);
        // ---- Anthropic Claude 4.7 ----
        put("claude-opus-4-7", 5.00, 25.00);
        put("claude-sonnet-4-7", 3.00, 15.00);
        put("claude-haiku-4-7", 1.00, 5.00);
        // ---- Anthropic Claude 4.6 ----
        put("claude-opus-4-6", 5.00, 25.00);
        put("claude-sonnet-4-6", 3.00, 15.00);
        put("claude-haiku-4-6", 1.00, 5.00);
        // ---- Anthropic Claude 4.5 ----
        put("claude-opus-4-5", 5.00, 25.00);
        put("claude-sonnet-4-5", 3.00, 15.00);
        put("claude-haiku-4-5", 1.00, 5.00);
        // ---- Anthropic Claude 4.x ----
        put("claude-opus-4-1", 15.00, 75.00);
        put("claude-opus-4", 15.00, 75.00);
        put("claude-sonnet-4", 3.00, 15.00);
        // ---- Anthropic Claude 3.x ----
        put("claude-3-5-sonnet", 3.00, 15.00);
        put("claude-3-5-haiku", 1.00, 5.00);
        put("claude-3-haiku", 0.25, 1.25);
        // ---- Mistral AI ----
        // Source: https://mistral.ai/pricing (verified 2026-04)
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
        // Cache hits are billed at $0.028/M input (10x cheaper). The newer deepseek-v4-flash
        // and deepseek-v4-pro are also available; legacy aliases scheduled for deprecation 2026-07-24.
        put("deepseek-chat", 0.28, 0.42);
        put("deepseek-reasoner", 0.28, 0.42);
        put("deepseek-coder", 0.28, 0.42);
        put("deepseek-v4-flash", 0.14, 0.28);
        put("deepseek-v4-pro", 1.74, 3.48);
        // ---- Google Gemini ----
        // Source: https://ai.google.dev/gemini-api/docs/pricing (verified 2026-04)
        // For >200K context, prices double approximately. We list flat pricing for the standard tier.
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

    private static void put(String prefix, double inputPrice, double outputPrice) {
        PRICING.put(prefix, new double[] { inputPrice, outputPrice });
    }

    /**
     * Builds a {@link TokenUsage} with estimated cost.
     *
     * @param model        Model name (e.g. {@code "gpt-4o-2024-08-06"}, {@code "claude-sonnet-4-6"})
     * @param inputTokens  Number of input tokens (nullable)
     * @param outputTokens Number of output tokens (nullable)
     * @return TokenUsage with cost, or with {@code estimatedCostUsd=null} if model is unknown
     */
    public static TokenUsage calculate(String model, Integer inputTokens, Integer outputTokens) {
        int in = inputTokens != null ? inputTokens : 0;
        int out = outputTokens != null ? outputTokens : 0;

        Double cost = null;
        if (model != null) {
            String lower = model.toLowerCase();
            for (Map.Entry<String, double[]> entry : PRICING.entrySet()) {
                if (lower.startsWith(entry.getKey())) {
                    double[] prices = entry.getValue();
                    cost = (in * prices[0] + out * prices[1]) / 1_000_000.0;
                    break;
                }
            }
        }

        return TokenUsage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .model(model)
                .estimatedCostUsd(cost)
                .build();
    }

    /**
     * Like {@link #calculate(String, Integer, Integer)} but consults a fallback
     * pricing map (typically from {@code CustomProviderSpec#getModelPricing()})
     * when the static table has no match. Useful for {@code Provider.CUSTOM}
     * instances whose models the library doesn't know about.
     *
     * <p>Lookup order: static table (longest-prefix match) -&gt; fallback map
     * (longest-prefix match) -&gt; {@code cost=null}.</p>
     *
     * @param model         Model name
     * @param inputTokens   Input token count (nullable)
     * @param outputTokens  Output token count (nullable)
     * @param fallback      Per-model pricing map, can be {@code null} or empty
     * @return TokenUsage with cost, or {@code estimatedCostUsd=null} if neither
     *         the static table nor the fallback map matches
     */
    public static TokenUsage calculate(String model, Integer inputTokens, Integer outputTokens,
            Map<String, PriceEntry> fallback) {

        int in = inputTokens != null ? inputTokens : 0;
        int out = outputTokens != null ? outputTokens : 0;

        Double cost = null;
        if (model != null) {
            String lower = model.toLowerCase();

            // 1) Static table (longest-prefix match).
            for (Map.Entry<String, double[]> entry : PRICING.entrySet()) {
                if (lower.startsWith(entry.getKey())) {
                    double[] p = entry.getValue();
                    cost = (in * p[0] + out * p[1]) / 1_000_000.0;
                    break;
                }
            }

            // 2) Fallback map (longest-prefix match).
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
     * @return e.g. {@code "Tokens: 150->50 | Cost: $0.001250"} or {@code "Tokens: 150->50 | Cost: N/A"}
     */
    public static String formatForLog(TokenUsage usage) {
        if (usage == null) {
            return "";
        }
        String tokens = "Tokens: " + (usage.getInputTokens() != null ? usage.getInputTokens() : "?")
                + "->" + (usage.getOutputTokens() != null ? usage.getOutputTokens() : "?");
        String cost = usage.getEstimatedCostUsd() != null
                ? String.format("Cost: $%.6f", usage.getEstimatedCostUsd())
                : "Cost: N/A";
        return tokens + " | " + cost;
    }
}
