package io.github.yannfavinleveque.agentic.agent.exception;

/**
 * Thrown when a requested agent is not found.
 */
public class AgentNotFoundException extends AgentException {

    private final String agentId;

    public AgentNotFoundException(String agentId) {
        super(ErrorCode.AGENT_NOT_FOUND, "Agent not found: " + agentId);
        this.agentId = agentId;
    }

    public String getAgentId() {
        return agentId;
    }
}
