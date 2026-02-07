package io.github.yannfavinleveque.agentic.agent.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Collections;
import java.util.List;

/**
 * Default result class for agents without a configured resultClass. Contains the raw string
 * response from the agent and any function calls requested by the model.
 * <p>
 * This is used as a fallback when an agent doesn't have structured output configured.
 * </p>
 *
 * @see FunctionCall
 */
@Getter
@Setter
@NoArgsConstructor
public class DefaultResult extends AgentResult {

    /**
     * The raw response string from the agent.
     */
    private String result;

    /**
     * Creates a DefaultResult with only text content.
     */
    public DefaultResult(String result) {
        this.result = result;
    }

    /**
     * Creates a DefaultResult with text content and function calls.
     */
    public DefaultResult(String result, List<FunctionCall> functionCalls) {
        this.result = result;
        setFunctionCalls(functionCalls != null ? functionCalls : Collections.emptyList());
    }

    @Override
    public String getContent() {
        return result;
    }

    @Override
    public String toString() {
        if (hasFunctionCalls()) {
            StringBuilder sb = new StringBuilder();
            if (result != null && !result.isEmpty()) {
                sb.append(result).append("\n");
            }
            sb.append("Function calls: ");
            for (FunctionCall call : getFunctionCalls()) {
                sb.append(call.toString()).append(" ");
            }
            return sb.toString().trim();
        }
        return result != null ? result : "";
    }
}
