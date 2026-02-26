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
     * Accumulates another TokenUsage into this one (mutates in place).
     * Sums inputTokens, outputTokens, and estimatedCostUsd.
     * Model is kept from this instance (or set from other if this is null).
     *
     * @param other the usage to add; null is a no-op
     * @return this instance for chaining
     */
    public TokenUsage accumulate(TokenUsage other) {
        if (other == null) return this;
        this.inputTokens = safeAdd(this.inputTokens, other.inputTokens);
        this.outputTokens = safeAdd(this.outputTokens, other.outputTokens);
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
