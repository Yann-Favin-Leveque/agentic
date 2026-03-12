package io.github.yannfavinleveque.agentic.agent.core;

import io.github.yannfavinleveque.agentic.agent.model.FunctionConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents an OpenAI Assistant Agent with configuration and runtime state. This class serves as a
 * runtime wrapper around OpenAI Assistants API, providing a simplified interface for agent
 * management.
 * <p>
 * Agents can be configured to use either standard OpenAI API or Azure OpenAI, with support for
 * multi-instance Azure deployments for load balancing.
 * </p>
 * see AgentService see AgentDefinition
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Agent {

    /**
     * Unique identifier for this agent (application-level ID).
     */
    private String id;

    /**
     * Human-readable name for the agent.
     */
    private String name;

    /**
     * Assistant IDs for each configured instance. Index corresponds to instance index in AgentService.
     * For single OpenAI instance: List with one ID. For multi-instance (OpenAI + Azure or multiple
     * Azure): List with IDs for each instance. Example: ["asst_openai_123", "asst_azure1_456",
     * "asst_azure2_789"]
     */
    private List<String> assistantIds;

    /**
     * Model name (e.g., "gpt-4o", "gpt-4o-mini").
     */
    private String model;

    /**
     * System instructions for the agent.
     */
    private String instructions;

    /**
     * Fully qualified class name for structured output mapping. If null, returns raw string response.
     * The class should implement {link AgentResult} interface.
     */
    private String resultClass;

    /**
     * Temperature for response generation (0.0 to 2.0). Lower values make output more focused and
     * deterministic.
     */
    private Double temperature;

    /**
     * Current agent status (e.g., "OK", "ERROR").
     */
    private String status;

    /**
     * Current thread ID if using persistent thread.
     */
    private String threadId;

    /**
     * Response timeout in milliseconds. Default: 120000ms (2 minutes).
     */
    @Builder.Default
    private Long responseTimeout = 120000L;

    /**
     * Whether to enable file search/retrieval capabilities (RAG).
     */
    @Builder.Default
    private Boolean retrieval = false;

    /**
     * Agent type for categorization (e.g., "interrogation", "generation").
     */
    private String agentType;

    /**
     * Whether to create this agent on application startup.
     *
     * @deprecated Use stateless API (requestAgentV2) instead of Assistants API.
     */
    @Deprecated
    @Builder.Default
    private Boolean createOnAppStart = false;

    /**
     * Whether this agent uses OpenAI Assistants API. - true: Uses OpenAI Assistants (requires assistant
     * creation) - false: Direct API calls (e.g., Claude/Anthropic - no assistant) Default: true
     * (backward compatibility)
     *
     * @deprecated Provider is now auto-detected from model name. Use
     *             {@link ProviderConfig#isAnthropicModel(String)}.
     */
    @Deprecated
    @Builder.Default
    private Boolean isOpenAI = true;

    /**
     * Maximum tokens for response generation. Used primarily for Anthropic/Claude models. For OpenAI,
     * this is typically controlled by the model's context window.
     */
    private Integer maxTokens;

    // ==================== NEW FIELDS FOR STATELESS API ====================

    /**
     * Whether to enable web search capability. - OpenAI: Uses "web_search_preview" tool - Claude: Uses
     * "web_search_20250305" tool
     */
    @Builder.Default
    private Boolean webSearch = false;

    /**
     * Whether to enable code interpreter capability. Currently only supported by OpenAI models.
     */
    @Builder.Default
    private Boolean codeInterpreter = false;

    /**
     * Custom functions that can be called by the agent. Each function maps to a Java class that
     * implements the function logic.
     */
    private List<FunctionConfig> functions;

    /**
     * Optional description for documentation purposes.
     */
    private String description;

    /**
     * Whether this agent runs in autonomous mode (tool loop handled internally).
     * When true, the library automatically manages the tool call loop: request agent,
     * execute tools via ToolExecutor, send results back, repeat until task_over is called.
     */
    @Builder.Default
    private Boolean autonomous = false;

    /**
     * Maximum number of iterations for autonomous mode loop.
     * Safety limit to prevent infinite loops. Default: 25.
     */
    @Builder.Default
    private Integer maxIterations = 25;

    /**
     * Maximum token count for tool output in autonomous mode.
     * When set, tool execution results are trimmed to this token limit before being
     * added to the conversation history. Tokens are estimated at ~4 characters per token.
     * If null (default), no trimming is applied.
     */
    private Integer maxToolTokenOutput;

    /**
     * When set, tool result contents from previous iterations are cleared from
     * the conversation history starting at this iteration number.
     * The tool call structure (name + arguments) is preserved so the agent can see
     * what it already searched, but the bulky response data is removed.
     * This prevents context from growing quadratically in multi-turn tool loops.
     * If null (default), no compaction is applied.
     */
    private Integer compactToolResultsAfterIteration;

    /**
     * Number of most recent iterations whose tool results are kept intact
     * when compaction is active. Default: 1 (keep only the current iteration's results).
     * Handles parallel tool calls correctly — all tool results from one iteration are
     * kept together regardless of how many there are.
     */
    @Builder.Default
    private Integer compactKeepLastNIterations = 1;

    /**
     * Reasoning effort level for the agent.
     * null or "none" = no reasoning. "low"/"medium"/"high" = reasoning with effort level.
     * "enabled" = reasoning with default effort (medium for OpenAI, enabled for Claude).
     */
    private String reasoningEffort;

}
