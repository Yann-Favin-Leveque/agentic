package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;

/**
 * Per-iteration callback fired by {@link AutonomousAgentRunner} immediately after each
 * LLM call inside the autonomous loop completes (before tools execute). One invocation
 * per iteration. Useful for observability — persisting per-iteration tokens/duration,
 * tracing, etc.
 *
 * <p>The listener runs on the iteration's thread and is best-effort: any thrown exception
 * is caught and logged so it cannot break the agent loop.
 */
@FunctionalInterface
public interface AutonomousIterationListener {

    /**
     * Called after the LLM responds for an iteration of the autonomous loop.
     *
     * @param event details of the completed iteration
     */
    void onIteration(IterationEvent event);

    final class IterationEvent {
        private final Agent originalAgent;
        private final String virtualAgentId;
        private final String conversationId;
        private final int iteration;
        private final long durationMs;
        private final AgentResult result;

        public IterationEvent(Agent originalAgent, String virtualAgentId, String conversationId,
                              int iteration, long durationMs, AgentResult result) {
            this.originalAgent = originalAgent;
            this.virtualAgentId = virtualAgentId;
            this.conversationId = conversationId;
            this.iteration = iteration;
            this.durationMs = durationMs;
            this.result = result;
        }

        public Agent getOriginalAgent() { return originalAgent; }
        public String getVirtualAgentId() { return virtualAgentId; }
        public String getConversationId() { return conversationId; }
        public int getIteration() { return iteration; }
        public long getDurationMs() { return durationMs; }
        public AgentResult getResult() { return result; }
    }
}
