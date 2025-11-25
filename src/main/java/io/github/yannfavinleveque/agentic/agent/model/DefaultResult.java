package io.github.yannfavinleveque.agentic.agent.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Default result class for agents without a configured resultClass.
 * Contains the raw string response from the agent.
 *
 * <p>This is used as a fallback when an agent doesn't have structured output configured.
 * The response is simply wrapped in this object for API consistency.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // Agent without resultClass returns DefaultResult
 * DefaultResult result = agentService.requestAgent("100", "Hello", null).join();
 * String response = result.getResult();
 * }</pre>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class DefaultResult implements AgentResult {

    /**
     * The raw response string from the agent.
     */
    private String result;

}
