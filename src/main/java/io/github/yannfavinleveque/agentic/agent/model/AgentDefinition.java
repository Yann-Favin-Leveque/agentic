package io.github.yannfavinleveque.agentic.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * JSON-deserializable configuration for agent definitions.
 * This class is used to load agent configurations from JSON files
 * and create {@link Agent} instances.
 *
 * <p>Example JSON file format:</p>
 * <pre>{@code
 * {
 *   "id": "101",
 *   "name": "Code Assistant",
 *   "model": "gpt-4o",
 *   "instructions": "You are a helpful coding assistant...",
 *   "resultClass": "CodeResult",
 *   "temperature": 0.7,
 *   "retrieval": false,
 *   "responseTimeout": 120000,
 *   "assistantIds": ["asst_openai_123", "asst_azure1_456", "asst_azure2_789"]
 * }
 * }</pre>
 *
 * @see Agent
 * @see AgentService # loadAgentDefinition(String)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDefinition {

    /**
     * Unique identifier for this agent.
     */
    @JsonProperty("id")
    private String id;

    /**
     * Human-readable name for the agent.
     */
    @JsonProperty("name")
    private String name;

    /**
     * Model name (e.g., "gpt-4o", "gpt-4o-mini").
     */
    @JsonProperty("model")
    private String model;

    /**
     * System instructions for the agent.
     */
    @JsonProperty("instructions")
    private String instructions;

    /**
     * Fully qualified class name for structured output mapping.
     * The class should implement {@link AgentResult} interface.
     */
    @JsonProperty("resultClass")
    @JsonAlias("result_class")
    private String resultClass;

    /**
     * Temperature for response generation (0.0 to 2.0).
     */
    @JsonProperty("temperature")
    private Double temperature;

    /**
     * Whether to enable file search/retrieval capabilities.
     */
    @JsonProperty("retrieval")
    private Boolean retrieval;

    /**
     * Response timeout in milliseconds.
     */
    @JsonProperty("responseTimeout")
    @JsonAlias("response_timeout")
    private Integer responseTimeout;

    /**
     * Agent type for categorization.
     */
    @JsonProperty("agentType")
    private String agentType;

    /**
     * Whether to create this agent on application startup.
     */
    @JsonProperty("createOnAppStart")
    private Boolean createOnAppStart;

    /**
     * Assistant IDs for each configured instance.
     * Index corresponds to instance index in AgentService.
     * This field is persisted after agent creation.
     * Example: ["asst_openai_123", "asst_azure1_456", "asst_azure2_789"]
     */
    @JsonProperty("assistantIds")
    private List<String> assistantIds;

    /**
     * Whether this agent uses OpenAI Assistants API.
     * - true: Uses OpenAI Assistants (requires assistant creation)
     * - false: Direct API calls (e.g., Claude/Anthropic - no assistant)
     * Default: true (backward compatibility)
     */
    @JsonProperty("isOpenAI")
    private Boolean isOpenAI;

    /**
     * Maximum tokens for response generation.
     * Used primarily for Anthropic/Claude models.
     */
    @JsonProperty("maxTokens")
    private Integer maxTokens;

}
