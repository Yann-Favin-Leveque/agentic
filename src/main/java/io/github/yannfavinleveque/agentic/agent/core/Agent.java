package io.github.yannfavinleveque.agentic.agent.core;

import io.github.yannfavinleveque.agentic.agent.config.RetryConfig;
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
@Builder(toBuilder = true)
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
     * Optional tool-choice hint sent to the model alongside the tools list.
     * <p>
     * Accepted values:
     * <ul>
     *   <li>{@code null} or {@code "auto"} → model decides (Anthropic/OpenAI default).</li>
     *   <li>{@code "any"} → model MUST call SOME tool (any from the list).
     *       Useful for verifier/dispatcher agents that should never reply in plain text.
     *       Without this, some models (notably Claude Haiku 4.5 via Azure Anthropic) tend
     *       to inline the tool-call as JSON/XML in the text response when the system prompt
     *       is verification-shaped — see agentwm Stop-hook for a real-world repro.</li>
     *   <li>{@code "none"} → tools are exposed in context but model must NOT call any.</li>
     *   <li>{@code "tool:<name>"} → forces a specific tool, e.g. {@code "tool:submit_verdict"}.</li>
     * </ul>
     * Mapped per-provider in the adapters:
     *   Claude → {"type": "auto"|"any"|"none"} or {"type": "tool", "name": "..."}.
     *   OpenAI → "auto"|"required"|"none" or {"type":"function","function":{"name":"..."}}.
     *   Mistral / others → currently ignored (no-op).
     */
    private String toolChoice;

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
     * <p>
     * Ignored when {@link #maxIterationsUnlimited} is {@code true}.
     */
    @Builder.Default
    private Integer maxIterations = 25;

    /**
     * When {@code true}, the autonomous loop never checks {@link #maxIterations}
     * and runs until it is cancelled, errors out, or — if task_over is still
     * enabled — the LLM calls {@code task_over}. The {@link #maxIterations}
     * field is still readable but effectively ignored.
     * <p>
     * Useful for long-running "agent in an environment" setups (e.g. NPCs in
     * a simulation) that must keep reacting to injected perception updates
     * indefinitely.
     * <p>
     * Default: {@code false} — legacy behaviour (25-iteration safety limit).
     */
    @Builder.Default
    private Boolean maxIterationsUnlimited = false;

    /**
     * DEPRECATED — kept for backwards compatibility with agents registered before v1.18.
     * Equivalent to {@link #infiniteLoop}{@code =true}. When either flag is true, the
     * library does NOT auto-inject {@code task_over} and the autonomous loop never
     * completes on its own (ends only on cancel, error, or {@link #maxIterations}).
     * <p>
     * Use {@link #infiniteLoop} in new code.
     */
    @Deprecated
    @Builder.Default
    private Boolean disableTaskOver = false;

    /**
     * When {@code true}, the library does NOT auto-inject any end-of-turn tool.
     * The autonomous loop runs indefinitely — it ends only on external cancellation,
     * error, or when {@link #maxIterations} is reached (unless
     * {@link #maxIterationsUnlimited} is also {@code true}).
     * <p>
     * Use for immortal observer-style agents fed externally via
     * {@link io.github.yannfavinleveque.agentic.agent.service.AgentService#insertMessage}.
     * <p>
     * Default: {@code false}.
     */
    @Builder.Default
    private Boolean infiniteLoop = false;

    /**
     * When {@code true}, a plain-text LLM response (no function calls, no structured
     * result parsable) ENDS the autonomous turn instead of being "nudged" into another
     * iteration. Use for conversational agents where the natural-language reply IS the
     * end of the turn.
     * <p>
     * Combine with {@link FunctionConfig#endsTurn}-flagged tools (like {@code ask_user},
     * {@code task_complete}) to get a conversational agent that loops over tools but
     * stops cleanly on a plain reply to the user.
     * <p>
     * Default: {@code false} — legacy behaviour (nudge on empty text).
     */
    @Builder.Default
    private Boolean endTurnOnPlainReply = false;

    /**
     * Set of tool-group names currently ENABLED for this agent. Functions with {@code group}
     * set are exposed to the LLM only if their group is in this set (or if the group is
     * {@code null} / {@code "default"}).
     * <p>
     * Use this for large toolboxes where only a subset is needed per task. Set the core tools'
     * group to {@code null} so they're always visible; tag situational tools (shell, web,
     * fs_write…) and toggle groups at runtime by rebuilding the agent.
     * <p>
     * When this is {@code null} (default), all functions are exposed regardless of group —
     * that is the legacy behavior, fully backwards-compatible.
     */
    private java.util.Set<String> enabledToolGroups;

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
     * When set to a positive value, the autonomous loop truncates the
     * conversation to this many estimated tokens BEFORE each iteration's
     * LLM call, via {@code ConversationManager.truncateByTokenBudget}. This
     * caps the steady-state conversation size inside long-running loops
     * (e.g. immortal NPC loops fed by external insertMessage calls) and
     * prevents runaway input cost.
     *
     * <p>Interacts with {@link #compactToolResultsAfterIteration}: that
     * compaction runs first (it clears bulky tool-result bodies); if the
     * conversation is still over {@code maxConversationTokens} after
     * compaction, the oldest whole messages are dropped until the budget
     * fits. A null value disables the feature (legacy behaviour).
     */
    private Integer maxConversationTokens;

    /**
     * When set to a positive value, the autonomous loop sleeps before each
     * iteration so that at least this many milliseconds have elapsed since
     * the START of the previous iteration. Caps the throughput of
     * long-running immortal loops that would otherwise burn through the LLM
     * rate limiter at 10-30 iterations per second doing low-value tool calls
     * (think, wait, pings).
     *
     * <p>The first iteration is never delayed. The delay is enforced using
     * {@code Thread.sleep} on the runner's own worker thread — it does not
     * hold any permit or conversation lock during the wait, so other agents
     * and external producers (e.g. {@code insertMessage} callers) are not
     * blocked.
     *
     * <p>A null or non-positive value disables the feature (legacy behaviour:
     * the loop runs as fast as the LLM API responds).
     */
    private Integer minIterationIntervalMs;

    /**
     * Reasoning effort level for the agent.
     * null or "none" = no reasoning. "low"/"medium"/"high" = reasoning with effort level.
     * "enabled" = reasoning with default effort (medium for OpenAI, enabled for Claude).
     */
    private String reasoningEffort;

    /**
     * Per-agent retry configuration for different error types.
     * If null, uses the global default from AgentServiceConfig.
     */
    private RetryConfig retryConfig;

    /**
     * Optional allow-list of instance IDs this agent is restricted to.
     * When non-null and non-empty, the InstanceRouter only picks instances
     * whose {@code id} is in this list AND that expose the requested model.
     * When null/empty, the router falls back to legacy round-robin over every
     * enabled instance exposing the model.
     * <p>
     * Loaded from {@link io.github.yannfavinleveque.agentic.agent.model.AgentDefinition#getInstances()}
     * (or from the legacy {@code instanceId} JSON field which is mapped to a singleton list).
     */
    private List<String> instances;

}
