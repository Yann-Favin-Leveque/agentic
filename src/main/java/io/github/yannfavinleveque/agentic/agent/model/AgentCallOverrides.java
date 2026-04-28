package io.github.yannfavinleveque.agentic.agent.model;

import lombok.Builder;
import lombok.Value;

/**
 * Per-call overrides for {@link io.github.yannfavinleveque.agentic.agent.service.AgentService#requestAgent}.
 * <p>
 * Each field is nullable; {@code null} means "inherit from the registered Agent". A non-null
 * value temporarily overrides the corresponding field for this single call without re-registering
 * the Agent. Used e.g. for one-shot reasoning bursts driven by the caller (heuristics, mid-prompt
 * keyword triggers, agent-side enable_thinking tools).
 * </p>
 */
@Value
@Builder
public class AgentCallOverrides {
    String reasoningEffort;
    Double temperature;
    Integer maxIterations;

    public static AgentCallOverrides none() { return AgentCallOverrides.builder().build(); }
    public static AgentCallOverrides reasoning(String effort) {
        return AgentCallOverrides.builder().reasoningEffort(effort).build();
    }
}
