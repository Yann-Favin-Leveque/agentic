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
    /**
     * Pin this call to ONE LLM instance by its config id (sticky). {@code null} = the registered
     * Agent's instance allow-list / round-robin. Used for per-session instance affinity so the
     * prefix cache of that instance stays warm (callers assign one instance per session at boot and
     * pass it on every call). Applied in {@code applyOverrides} by setting the Agent's instance
     * allow-list to this single id; the router then resolves it (falling back to round-robin only if
     * the id is unknown for the model).
     */
    String instanceId;

    public static AgentCallOverrides none() { return AgentCallOverrides.builder().build(); }
    public static AgentCallOverrides reasoning(String effort) {
        return AgentCallOverrides.builder().reasoningEffort(effort).build();
    }
    /** Pin this call to a specific LLM instance id (sticky session affinity). */
    public static AgentCallOverrides instance(String instanceId) {
        return AgentCallOverrides.builder().instanceId(instanceId).build();
    }
}
