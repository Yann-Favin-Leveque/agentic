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
        // ---- Anthropic Claude 4.6 ----
        put("claude-opus-4-6", 5.00, 25.00);
        put("claude-sonnet-4-6", 3.00, 15.00);
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
        // Source: https://mistral.ai/products/la-plateforme#pricing (verifier au moment de l'integration)
        // TODO: verify pricing
        put("mistral-large-latest", 2.00, 6.00);
        put("mistral-large", 2.00, 6.00);
        put("mistral-medium-latest", 2.70, 8.10);
        put("mistral-medium", 2.70, 8.10);
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
        // ---- Google Gemini ----
        // Source: https://ai.google.dev/gemini-api/docs/pricing  (verify; tiered by context length)
        // For >200K context, prices double approximately. We list flat pricing for the standard tier.
        // TODO: verify pricing
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
