package io.github.yannfavinleveque.agentic.integration.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Test result class for autonomous agent integration testing.
 * The agent collects data using tools and returns a structured research summary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResearchResult extends AgentResult {

    @JsonProperty("topic")
    private String topic;

    @JsonProperty("findings")
    private List<String> findings;

    @JsonProperty("conclusion")
    private String conclusion;

    @Override
    public String getContent() {
        return "Topic: " + topic + ", Findings: " + findings + ", Conclusion: " + conclusion;
    }
}
