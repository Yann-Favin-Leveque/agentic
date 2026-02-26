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
}
