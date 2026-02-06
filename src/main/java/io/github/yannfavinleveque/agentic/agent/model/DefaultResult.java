package io.github.yannfavinleveque.agentic.agent.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Default result class for agents without a configured resultClass. Contains the raw string
 * response from the agent and any function calls requested by the model.
 * <p>
 * This is used as a fallback when an agent doesn't have structured output configured. The response
 * is simply wrapped in this object for API consistency.
 * </p>
 * <p>
 * Usage:
 * </p>
 *
 * <pre>{@code
 * // Agent without resultClass returns DefaultResult
 * AgentResult result = agentService.requestAgent("100", "Hello", null).join();
 *
 * // Check for function calls
 * if (result.hasFunctionCalls()) {
 *     for (FunctionCall call : result.getFunctionCalls()) {
 *         String name = call.getName();
 *         Map<String, Object> args = call.getArgumentsAsMap();
 *         // Execute function...
 *     }
 * } else {
 *     String response = result.getContent();
 * }
 * }</pre>
 *
 * @see FunctionCall
 */
@Getter
@Setter
@NoArgsConstructor
public class DefaultResult implements AgentResult {

    /**
     * The raw response string from the agent.
     */
    private String result;

    /**
     * Function calls requested by the model.
     */
    private List<FunctionCall> functionCalls = new ArrayList<>();

    /**
     * Creates a DefaultResult with only text content.
     *
     * @param result Text content
     */
    public DefaultResult(String result) {
        this.result = result;
        this.functionCalls = Collections.emptyList();
    }

    /**
     * Creates a DefaultResult with text content and function calls.
     *
     * @param result        Text content
     * @param functionCalls List of function calls
     */
    public DefaultResult(String result, List<FunctionCall> functionCalls) {
        this.result = result;
        this.functionCalls = functionCalls != null ? functionCalls : Collections.emptyList();
    }

    /**
     * Gets the content of this result. For DefaultResult, this returns the raw result string.
     *
     * @return The result string
     */
    @Override
    public String getContent() {
        return result;
    }

    @Override
    public List<FunctionCall> getFunctionCalls() {
        return functionCalls != null ? functionCalls : Collections.emptyList();
    }

    @Override
    public boolean hasFunctionCalls() {
        return functionCalls != null && !functionCalls.isEmpty();
    }

    @Override
    public String toString() {
        if (hasFunctionCalls()) {
            StringBuilder sb = new StringBuilder();
            if (result != null && !result.isEmpty()) {
                sb.append(result).append("\n");
            }
            sb.append("Function calls: ");
            for (FunctionCall call : functionCalls) {
                sb.append(call.toString()).append(" ");
            }
            return sb.toString().trim();
        }
        return result != null ? result : "";
    }
}
