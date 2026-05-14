package io.github.yannfavinleveque.agentic.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token usage and estimated cost for an API request.
 * Returned on {@link io.github.yannfavinleveque.agentic.agent.model.AgentResult#getUsage()}.
 *
 * <p>{@code estimatedCostUsd} is {@code null} when the model is not in the pricing table.</p>
 *
 * <p>{@code cacheCreationTokens} / {@code cacheReadTokens} are populated only for providers
 * that report cache statistics (currently Anthropic via {@code cache_creation_input_tokens} /
 * {@code cache_read_input_tokens}, and OpenAI via {@code prompt_tokens_details.cached_tokens}
 * — OpenAI only reports reads). They are {@code null} (not 0) when the API does not return
 * the field, so callers can distinguish "no cache info" from "zero cached tokens".</p>
 *
 * <p>For Anthropic, {@code inputTokens} is the <em>uncached</em> portion only. For OpenAI,
 * {@code inputTokens} as stored here is also the uncached portion: the
 * {@code UnifiedRequestService} subtracts {@code cachedTokens} from {@code prompt_tokens}
 * before building the {@code TokenUsage} so that input/cacheRead never double-count.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsage {

    private Integer inputTokens;
    private Integer outputTokens;
    private String model;

    /**
     * Estimated cost in USD, or {@code null} if pricing is not available for this model.
     */
    private Double estimatedCostUsd;

    /**
     * Tokens written into the prompt cache on this request. {@code null} when the
     * provider does not report cache statistics.
     */
    private Integer cacheCreationTokens;

    /**
     * Tokens served from the prompt cache on this request. {@code null} when the
     * provider does not report cache statistics.
     */
    private Integer cacheReadTokens;

    /**
     * Accumulates another TokenUsage into this one (mutates in place).
     * Sums inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens, and estimatedCostUsd.
     * Model is kept from this instance (or set from other if this is null).
     *
     * @param other the usage to add; null is a no-op
     * @return this instance for chaining
     */
    public TokenUsage accumulate(TokenUsage other) {
        if (other == null) return this;
        this.inputTokens = safeAdd(this.inputTokens, other.inputTokens);
        this.outputTokens = safeAdd(this.outputTokens, other.outputTokens);
        this.cacheCreationTokens = safeAdd(this.cacheCreationTokens, other.cacheCreationTokens);
        this.cacheReadTokens = safeAdd(this.cacheReadTokens, other.cacheReadTokens);
        this.estimatedCostUsd = safeAddDouble(this.estimatedCostUsd, other.estimatedCostUsd);
        if (this.model == null) this.model = other.model;
        return this;
    }

    private static Integer safeAdd(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + b;
    }

    private static Double safeAddDouble(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + b;
    }
}
