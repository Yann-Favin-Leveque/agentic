package io.github.yannfavinleveque.agentic.agent.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.yannfavinleveque.agentic.agent.config.RetryConfig;
import io.github.yannfavinleveque.agentic.agent.core.ProviderConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * JSON-deserializable configuration for agent definitions. This class is used to load agent
 * configurations from JSON files and create {@link Agent} instances.
 * <p>
 * Example JSON file format:
 * </p>
 * 
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
     * Fully qualified class name for structured output mapping. The class should implement
     * {@link AgentResult} interface.
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
     *
     * @deprecated Use stateless API (requestAgentV2) instead of Assistants API.
     */
    @Deprecated
    @JsonProperty("createOnAppStart")
    private Boolean createOnAppStart;

    /**
     * Assistant IDs for each configured instance. Index corresponds to instance index in AgentService.
     * This field is persisted after agent creation. Example: ["asst_openai_123", "asst_azure1_456",
     * "asst_azure2_789"]
     *
     * @deprecated Use stateless API (requestAgentV2) instead of Assistants API.
     */
    @Deprecated
    @JsonProperty("assistantIds")
    private List<String> assistantIds;

    /**
     * Whether this agent uses OpenAI Assistants API. - true: Uses OpenAI Assistants (requires assistant
     * creation) - false: Direct API calls (e.g., Claude/Anthropic - no assistant) Default: true
     * (backward compatibility)
     *
     * @deprecated Provider is now auto-detected from model name. Use
     *             {@link ProviderConfig#isAnthropicModel(String)}.
     */
    @Deprecated
    @JsonProperty("isOpenAI")
    private Boolean isOpenAI;

    /**
     * Maximum tokens for response generation. Used primarily for Anthropic/Claude models.
     */
    @JsonProperty("maxTokens")
    private Integer maxTokens;

    // ==================== NEW FIELDS FOR STATELESS API ====================

    /**
     * Whether to enable web search capability. - OpenAI: Uses "web_search_preview" tool - Claude: Uses
     * "web_search_20250305" tool
     */
    @JsonProperty("webSearch")
    private Boolean webSearch;

    /**
     * Whether to enable code interpreter capability. Currently only supported by OpenAI models.
     */
    @JsonProperty("codeInterpreter")
    private Boolean codeInterpreter;

    /**
     * Custom functions that can be called by the agent. Each function maps to a Java class that
     * implements the function logic.
     */
    @JsonProperty("functions")
    private List<FunctionConfig> functions;

    /**
     * Optional description for documentation purposes.
     */
    @JsonProperty("description")
    private String description;

    /**
     * Whether this agent runs in autonomous mode (tool loop handled internally).
     * When true, requestAgent() with a ToolExecutor will automatically manage the tool call loop.
     */
    @JsonProperty("autonomous")
    private Boolean autonomous;

    /**
     * Maximum number of iterations for autonomous mode loop. Default: 25.
     */
    @JsonProperty("maxIterations")
    @JsonAlias("max_iterations")
    private Integer maxIterations;

    /**
     * Maximum token count for tool output in autonomous mode.
     * Tool results exceeding this limit are trimmed before being added to conversation history.
     * If null, no trimming is applied.
     */
    @JsonProperty("maxToolTokenOutput")
    @JsonAlias("max_tool_token_output")
    private Integer maxToolTokenOutput;

    /**
     * Reasoning effort level for the agent.
     * Controls whether the model uses reasoning/thinking before responding.
     * <ul>
     *   <li>null → default to "none" (no reasoning, backward compatible)</li>
     *   <li>"none" → explicitly disable reasoning</li>
     *   <li>"low", "medium", "high" → OpenAI: sent as reasoning.effort; Claude: enables thinking</li>
     *   <li>"enabled" → OpenAI: maps to "medium"; Claude: enables thinking</li>
     * </ul>
     */
    @JsonProperty("reasoningEffort")
    @JsonAlias("reasoning_effort")
    private String reasoningEffort;

    /**
     * Per-agent retry configuration for different error types.
     * If null, uses the global default from AgentServiceConfig.
     */
    @JsonProperty("retryConfig")
    @JsonAlias("retry_config")
    private RetryConfig retryConfig;

    /**
     * Optional allow-list of instance IDs this agent is restricted to.
     * When present and non-empty, the {@code InstanceRouter} only picks instances
     * whose {@code id} is in this list AND that expose the requested model.
     * <p>
     * Format on disk: JSON array of strings under the field name {@code "instances"}.
     * The legacy field {@code "instanceId"} (single string) is also supported for
     * backward compat — it is mapped to a singleton list at parse time via
     * {@link #setInstanceId(String)}.
     * <p>
     * When absent/empty: the router falls back to round-robin over every
     * enabled instance exposing the model (legacy behavior).
     */
    @JsonProperty("instances")
    private List<String> instances;

    /**
     * Backward-compat setter for the legacy {@code "instanceId"} JSON field.
     * Maps a single id to a singleton {@link #instances} list, but only if
     * {@code instances} has not already been populated (the new field wins
     * during a migration where both are present).
     *
     * @param instanceId Legacy single-instance id, or {@code null} to skip.
     */
    @JsonProperty("instanceId")
    public void setInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return;
        }
        if (this.instances == null || this.instances.isEmpty()) {
            this.instances = java.util.Collections.singletonList(instanceId);
        }
    }

}
