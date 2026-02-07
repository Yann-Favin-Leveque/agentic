package io.github.yannfavinleveque.agentic.integration.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Test result class for structured output testing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MathResult extends AgentResult {

    @JsonProperty("expression")
    private String expression;

    @JsonProperty("result")
    private Integer result;

    @JsonProperty("explanation")
    private String explanation;

    @Override
    public String getContent() {
        return "Expression: " + expression + " = " + result + " (" + explanation + ")";
    }
}
