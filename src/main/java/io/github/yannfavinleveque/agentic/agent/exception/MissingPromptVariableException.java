package io.github.yannfavinleveque.agentic.agent.exception;

import java.util.Collections;
import java.util.Set;

/**
 * Thrown when an agent's instructions template references a Mustache variable
 * (e.g. {@code {{name}}}) that was not provided in the {@code promptVars} map at
 * request time.
 * <p>
 * The library scans only the {@code instructions} field of the agent for
 * variable placeholders. {@code userMessage} and {@code history} are passed
 * through untouched, so {@code {{...}}} patterns appearing in user-supplied
 * content will never trigger this exception.
 */
public class MissingPromptVariableException extends AgentException {

    private final String agentId;
    private final String variableName;
    private final Set<String> providedKeys;

    public MissingPromptVariableException(String agentId, String variableName, Set<String> providedKeys) {
        super(ErrorCode.MISSING_PROMPT_VARIABLE, String.format(
                "Agent '%s' references prompt variable '{{%s}}' but it was not provided. Provided keys: %s",
                agentId, variableName, providedKeys == null ? Collections.emptySet() : providedKeys
        ));
        this.agentId = agentId;
        this.variableName = variableName;
        this.providedKeys = providedKeys == null ? Collections.emptySet() : Collections.unmodifiableSet(providedKeys);
    }

    /** Identifier of the agent whose instructions contain the unresolved variable. */
    public String getAgentId() {
        return agentId;
    }

    /** Name of the missing variable (without the surrounding {@code {{ }}}). */
    public String getVariableName() {
        return variableName;
    }

    /** Snapshot of the keys the caller actually provided in {@code promptVars}. */
    public Set<String> getProvidedKeys() {
        return providedKeys;
    }
}
