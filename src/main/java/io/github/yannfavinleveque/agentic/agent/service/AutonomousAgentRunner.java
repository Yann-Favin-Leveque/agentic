package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.config.RetryConfig;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.exception.AgentException;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import io.github.yannfavinleveque.agentic.agent.model.DefaultResult;
import io.github.yannfavinleveque.agentic.agent.model.FunctionCall;
import io.github.yannfavinleveque.agentic.agent.model.FunctionConfig;
import io.github.yannfavinleveque.agentic.agent.model.Message;
import io.github.yannfavinleveque.agentic.agent.model.ToolExecutor;
import io.github.yannfavinleveque.agentic.common.TokenUsage;
import io.github.yannfavinleveque.agentic.support.JsonSchemaGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handles the autonomous agent loop: request agent, execute tools, send results back, repeat.
 * <p>
 * This runner is a pure orchestrator. Each iteration calls the real
 * {@link AgentService#requestAgent(String, String, String)} which goes through the full
 * permit/retry/rate-limiting pipeline. The runner only manages the conversation and tool
 * execution between iterations.
 * </p>
 *
 * @see ToolExecutor
 * @see ToolBuilder#TASK_OVER_FUNCTION_NAME
 */
public class AutonomousAgentRunner {

    private static final Logger logger = LoggerFactory.getLogger(AutonomousAgentRunner.class);

    private final AgentServiceConfig config;
    private final AgentManager agentManager;
    private final ConversationManager conversationManager;
    private AgentService agentService;

    public AutonomousAgentRunner(AgentServiceConfig config,
                                 AgentManager agentManager,
                                 ConversationManager conversationManager) {
        this.config = config;
        this.agentManager = agentManager;
        this.conversationManager = conversationManager;
    }

    /**
     * Sets the AgentService reference (called after AgentService construction to resolve circular dependency).
     */
    void setAgentService(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * Runs the autonomous agent loop.
     *
     * @param agent          Original agent configuration (with autonomous=true)
     * @param userMessage    Initial user message
     * @param conversationId External conversation ID (null to create an internal one)
     * @param toolExecutor   User-provided tool execution logic (nullable; config-based executors used as fallback)
     * @return CompletableFuture with the final result (from task_over or last response)
     */
    public CompletableFuture<AgentResult> run(Agent agent, String userMessage,
                                              String conversationId, ToolExecutor toolExecutor) {
        boolean internalConversation = (conversationId == null);
        String convId = internalConversation
                ? conversationManager.createConversation()
                : conversationId;

        int maxIterations = agent.getMaxIterations() != null ? agent.getMaxIterations() : 25;
        boolean unlimited = Boolean.TRUE.equals(agent.getMaxIterationsUnlimited());

        // Build composite executor: lambda (priority) + config-based executors (fallback)
        ToolExecutor effectiveExecutor = buildCompositeExecutor(agent, toolExecutor);

        // Build and register the virtual agent (autonomous=false, resultClass=null, task_over injected
        // unless disableTaskOver is set on the original agent)
        Agent virtualAgent = buildVirtualAgent(agent);
        agentManager.registerAgent(virtualAgent);

        // Resolve max iteration retries
        RetryConfig agentRetryConfig = agent.getRetryConfig() != null ? agent.getRetryConfig() : new RetryConfig();
        int maxIterationRetries = agentRetryConfig.resolveMaxIterationRetries(config.getDefaultRetryConfig());

        logger.info("Starting autonomous loop for agent '{}' (virtualId={}, maxIterations={}, maxIterationRetries={}, disableTaskOver={}, conversation={})",
                agent.getId(), virtualAgent.getId(),
                unlimited ? "unlimited" : String.valueOf(maxIterations),
                maxIterationRetries,
                Boolean.TRUE.equals(agent.getDisableTaskOver()),
                convId);

        // Accumulate token usage across all iterations (including retries)
        TokenUsage cumulativeUsage = new TokenUsage();

        return executeLoopWithRetry(virtualAgent, agent, convId, userMessage, effectiveExecutor,
                maxIterations, cumulativeUsage, 0, maxIterationRetries)
                .whenComplete((result, error) -> {
                    // Cleanup: unregister virtual agent
                    agentManager.removeAgent(virtualAgent.getId());
                    logger.debug("Cleaned up virtual agent {}", virtualAgent.getId());

                    if (internalConversation) {
                        conversationManager.deleteConversation(convId);
                        logger.debug("Cleaned up internal conversation {}", convId);
                    }
                    if (error != null) {
                        logger.error("Autonomous loop failed for agent '{}': {}",
                                agent.getId(), error.getMessage());
                    } else {
                        // Set cumulative usage on the final result
                        result.setUsage(cumulativeUsage);
                        logger.info("Autonomous loop completed for agent '{}' with result type: {} " +
                                        "(total tokens: {} in / {} out, estimated cost: ${} USD)",
                                agent.getId(), result.getClass().getSimpleName(),
                                cumulativeUsage.getInputTokens(), cumulativeUsage.getOutputTokens(),
                                cumulativeUsage.getEstimatedCostUsd());
                    }
                });
    }

    // ==================== PRIVATE METHODS ====================

    /**
     * Builds a composite ToolExecutor that combines a user-provided lambda (priority)
     * with config-based executors from {@code executorClass} on FunctionConfig (fallback).
     */
    private ToolExecutor buildCompositeExecutor(Agent agent, ToolExecutor lambdaExecutor) {
        Map<String, ToolExecutor> configExecutors = new HashMap<>();
        if (agent.getFunctions() != null) {
            for (FunctionConfig func : agent.getFunctions()) {
                if (func.getExecutorClass() != null && !func.getExecutorClass().isEmpty()) {
                    String resolved = config.resolveExecutorClassName(func.getExecutorClass());
                    if (resolved != null) {
                        try {
                            Class<?> clazz = Class.forName(resolved);
                            ToolExecutor instance = (ToolExecutor) clazz.getDeclaredConstructor().newInstance();
                            configExecutors.put(func.getName(), instance);
                            logger.debug("Loaded executor '{}' for function '{}'", resolved, func.getName());
                        } catch (Exception e) {
                            logger.warn("Failed to instantiate executor '{}' for function '{}': {}",
                                    resolved, func.getName(), e.getMessage());
                        }
                    } else {
                        logger.warn("Cannot resolve executor class '{}' for function '{}' - "
                                + "use FQCN or configure functionExecutorClassPackage",
                                func.getExecutorClass(), func.getName());
                    }
                }
            }
        }

        if (!configExecutors.isEmpty()) {
            logger.info("Loaded {} config-based executor(s) for agent '{}'",
                    configExecutors.size(), agent.getId());
        }

        return call -> {
            if (lambdaExecutor != null) {
                return lambdaExecutor.execute(call);
            }
            ToolExecutor configExec = configExecutors.get(call.getName());
            if (configExec != null) {
                return configExec.execute(call);
            }
            return "Error: No executor configured for function '" + call.getName() + "'";
        };
    }

    /**
     * Returns {@code true} when the loop should terminate because the
     * iteration counter reached {@code maxIterations}. Always returns
     * {@code false} when {@code originalAgent.maxIterationsUnlimited} is
     * {@code true}. Package-private to allow unit testing.
     */
    static boolean isMaxIterationsExceeded(Agent originalAgent, int iteration, int maxIterations) {
        if (Boolean.TRUE.equals(originalAgent.getMaxIterationsUnlimited())) {
            return false;
        }
        return iteration >= maxIterations;
    }

    /**
     * Computes how long the autonomous loop should sleep before starting the
     * next iteration so that at least {@code minIntervalMs} milliseconds have
     * elapsed since the start of the previous iteration. The feature is
     * disabled (returns 0) when {@code minIntervalMs} is null or non-positive,
     * or when {@code previousIterationStartMs} is 0 (first iteration).
     * Returns 0 rather than a negative value when the previous iteration
     * already took longer than the budget — a slow iteration never adds an
     * extra wait on top of itself. Package-private for tests.
     */
    static long computeThrottleDelayMs(Integer minIntervalMs, long previousIterationStartMs, long nowMs) {
        if (minIntervalMs == null || minIntervalMs <= 0 || previousIterationStartMs <= 0) {
            return 0;
        }
        long elapsed = nowMs - previousIterationStartMs;
        long remaining = minIntervalMs - elapsed;
        return Math.max(0, remaining);
    }

    /**
     * Builds a virtual agent for the autonomous loop:
     * - autonomous=false (so requestAgent treats it as a normal agent)
     * - resultClass=null (no forced structured output)
     * - task_over function injected (unless {@code disableTaskOver=true} on the original)
     * - unique temporary ID to avoid collisions
     *
     * <p>Package-private so unit tests can exercise the task_over injection logic
     * without spinning up a full AgentService + HTTP stack.
     */
    Agent buildVirtualAgent(Agent original) {
        List<FunctionConfig> functions = applyGroupFilter(original,
                original.getFunctions() != null ? original.getFunctions() : new ArrayList<>());

        boolean infiniteLoop = Boolean.TRUE.equals(original.getInfiniteLoop())
                || Boolean.TRUE.equals(original.getDisableTaskOver());  // legacy alias

        // Auto-inject task_over ONLY when:
        //   - the agent is NOT in infinite-loop mode, AND
        //   - no existing function already declares endsTurn=true
        // This is the backwards-compat fallback: legacy agents get task_over transparently.
        boolean hasEndsTurnTool = functions.stream()
                .anyMatch(fc -> Boolean.TRUE.equals(fc.getEndsTurn()));
        boolean injectTaskOver = !infiniteLoop && !hasEndsTurnTool;

        String instructions = original.getInstructions() != null ? original.getInstructions() : "";
        if (injectTaskOver) {
            functions.add(buildTaskOverFunction(original));
            instructions += "\n\nWhen the task is fully complete, you MUST call the '"
                    + ToolBuilder.TASK_OVER_FUNCTION_NAME
                    + "' function with the final result. Do not simply respond with text when you are done.";
        }

        return Agent.builder()
                .id(original.getId() + "-autonomous-" + UUID.randomUUID().toString().substring(0, 8))
                .name(original.getName())
                .model(original.getModel())
                .instructions(instructions)
                .resultClass(null)
                .temperature(original.getTemperature())
                .responseTimeout(original.getResponseTimeout())
                .retrieval(original.getRetrieval())
                .webSearch(original.getWebSearch())
                .codeInterpreter(original.getCodeInterpreter())
                .maxTokens(original.getMaxTokens())
                .functions(functions)
                .autonomous(false)
                .maxIterations(original.getMaxIterations())
                .maxToolTokenOutput(original.getMaxToolTokenOutput())
                .build();
    }

    /**
     * Extracts the group-filter logic used by {@link #buildVirtualAgent(Agent)}. Given the original
     * agent and a source list of functions, returns a new mutable list containing only the functions
     * whose group is null / blank / "default" / present in the original's {@code enabledToolGroups}.
     * If {@code enabledToolGroups} is null the full source list is returned unchanged (legacy mode).
     * <p>
     * Package-private so {@link AgentService#updateAgentFunctions(String, List)} can re-apply the
     * same filter when propagating a new function list to virtual children.
     */
    static List<FunctionConfig> applyGroupFilter(Agent original, List<FunctionConfig> source) {
        List<FunctionConfig> out = new ArrayList<>();
        if (source == null || source.isEmpty()) return out;
        Set<String> enabledGroups = original.getEnabledToolGroups();
        if (enabledGroups == null) {
            out.addAll(source);
            return out;
        }
        for (FunctionConfig fc : source) {
            String g = fc.getGroup();
            if (g == null || g.isBlank() || "default".equals(g) || enabledGroups.contains(g)) {
                out.add(fc);
            }
        }
        return out;
    }

    /**
     * Mutates {@code filtered} in-place, appending an auto {@code task_over} function if and only if
     * the equivalent of {@link #buildVirtualAgent(Agent)} would have. Used by
     * {@link AgentService#updateAgentFunctions(String, List)} to keep virtual children consistent
     * with what they'd look like if freshly rebuilt.
     * <p>
     * Package-private. {@code virtualChild} is unused for now but exposed in case a future
     * refinement needs per-child context (e.g. a different resultClass).
     */
    void maybeInjectTaskOver(Agent virtualChild, Agent parent, List<FunctionConfig> filtered) {
        boolean infiniteLoop = Boolean.TRUE.equals(parent.getInfiniteLoop())
                || Boolean.TRUE.equals(parent.getDisableTaskOver());
        if (infiniteLoop) return;
        boolean hasEndsTurnTool = filtered.stream()
                .anyMatch(fc -> Boolean.TRUE.equals(fc.getEndsTurn()));
        if (hasEndsTurnTool) return;
        filtered.add(buildTaskOverFunction(parent));
    }

    private FunctionConfig buildTaskOverFunction(Agent agent) {
        Map<String, Object> schema = buildTaskOverSchema(agent);
        return FunctionConfig.builder()
                .name(ToolBuilder.TASK_OVER_FUNCTION_NAME)
                .description("Call this function when the task is fully complete. "
                        + "Pass the final structured result as parameters.")
                .parameters(schema)
                .build();
    }

    private Map<String, Object> buildTaskOverSchema(Agent agent) {
        if (agent.getResultClass() != null && !agent.getResultClass().isEmpty()) {
            String resolvedClassName = AgentServiceConfig.resolveClassName(
                    agent.getResultClass(), config.getAgentResultClassPackage());
            if (resolvedClassName != null) {
                try {
                    Class<?> resultClass = Class.forName(resolvedClassName);
                    Map<String, Object> schema = JsonSchemaGenerator.createFunctionSchemaFromClass(resultClass);
                    logger.debug("Generated task_over schema from class '{}': {} properties",
                            resolvedClassName, schema.getOrDefault("properties", Map.of()));
                    return schema;
                } catch (ClassNotFoundException e) {
                    logger.warn("Result class not found for task_over schema: {} (resolved: {})",
                            agent.getResultClass(), resolvedClassName);
                }
            } else {
                logger.warn("Cannot resolve result class '{}' for task_over schema", agent.getResultClass());
            }
        }
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of(),
                "additionalProperties", false);
    }

    /**
     * Wraps executeLoop with retry logic for MAX_ITERATIONS_EXCEEDED errors.
     * When the autonomous loop exceeds maxIterations, clears the conversation and retries from scratch.
     */
    private CompletableFuture<AgentResult> executeLoopWithRetry(Agent virtualAgent, Agent originalAgent,
                                                                 String convId, String userMessage,
                                                                 ToolExecutor toolExecutor,
                                                                 int maxIterations, TokenUsage cumulativeUsage,
                                                                 int retryAttempt, int maxRetries) {
        return executeLoop(virtualAgent, originalAgent, convId, userMessage, toolExecutor, 0, maxIterations, cumulativeUsage, 0L)
                .handle((result, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(result);
                    }

                    Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                            ? error.getCause() : error;

                    // Only retry MAX_ITERATIONS_EXCEEDED
                    boolean isMaxIterations = cause instanceof AgentException
                            && ((AgentException) cause).getErrorCode() == AgentException.ErrorCode.MAX_ITERATIONS_EXCEEDED;

                    if (!isMaxIterations || retryAttempt >= maxRetries) {
                        return CompletableFuture.<AgentResult>failedFuture(cause);
                    }

                    logger.warn("Autonomous agent '{}' exceeded max iterations (attempt {}/{}), retrying full loop...",
                            originalAgent.getId(), retryAttempt + 1, maxRetries);

                    // Clear conversation history for a fresh retry
                    conversationManager.clearHistory(convId);

                    return executeLoopWithRetry(virtualAgent, originalAgent, convId, userMessage, toolExecutor,
                            maxIterations, cumulativeUsage, retryAttempt + 1, maxRetries);
                })
                .thenCompose(f -> f);
    }

    /**
     * The loop: call requestAgent → handle response → repeat.
     * Each iteration goes through the full AgentService.requestAgent() pipeline (permits, retries, etc).
     *
     * @param previousIterationStartMs wall-clock start time of the previous
     *     iteration (via {@link System#currentTimeMillis()}), or {@code 0}
     *     for the very first iteration. Used by the optional
     *     {@code minIterationIntervalMs} throttle to measure the inter-iteration
     *     delay start-to-start rather than end-to-start.
     */
    private CompletableFuture<AgentResult> executeLoop(Agent virtualAgent, Agent originalAgent,
                                                       String convId, String userMessage,
                                                       ToolExecutor toolExecutor,
                                                       int iteration, int maxIterations,
                                                       TokenUsage cumulativeUsage,
                                                       long previousIterationStartMs) {
        if (isMaxIterationsExceeded(originalAgent, iteration, maxIterations)) {
            return CompletableFuture.failedFuture(new AgentException(
                    AgentException.ErrorCode.MAX_ITERATIONS_EXCEEDED,
                    "Autonomous agent '" + originalAgent.getId()
                            + "' exceeded max iterations (" + maxIterations + ")"));
        }

        // Optional throttle: enforce a minimum interval between iteration
        // starts. First iteration is never delayed (previousIterationStartMs==0
        // short-circuits computeThrottleDelayMs to 0). A slow previous iteration
        // that already exceeded the budget also returns 0 — the next iteration
        // fires immediately instead of waiting on top of the overshoot.
        long throttleDelay = computeThrottleDelayMs(
                originalAgent.getMinIterationIntervalMs(),
                previousIterationStartMs,
                System.currentTimeMillis());
        if (throttleDelay > 0) {
            logger.debug("Throttling agent '{}' for {} ms before iteration {} (minIterationIntervalMs={})",
                    originalAgent.getId(), throttleDelay, iteration, originalAgent.getMinIterationIntervalMs());
            try {
                Thread.sleep(throttleDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(e);
            }
        }
        long thisIterationStartMs = System.currentTimeMillis();

        logger.debug("Autonomous loop iteration {} for agent '{}'", iteration, originalAgent.getId());

        // Compact old tool results if configured — before sending to LLM
        Integer compactAfter = originalAgent.getCompactToolResultsAfterIteration();
        if (compactAfter != null && iteration >= compactAfter) {
            int keepIterations = originalAgent.getCompactKeepLastNIterations() != null
                    ? originalAgent.getCompactKeepLastNIterations() : 1;
            int compacted = conversationManager.compactToolResults(convId, keepIterations);
            if (compacted > 0) {
                logger.info("Compacted {} old tool results at iteration {} for agent '{}'",
                        compacted, iteration, originalAgent.getId());
            }
        }

        // Per-iteration token-budget truncation — runs AFTER compaction so the
        // cheaper compaction step gets a chance to shrink bulky tool results
        // first; any remaining over-budget messages are then dropped oldest-first.
        Integer maxTokens = originalAgent.getMaxConversationTokens();
        if (maxTokens != null && maxTokens > 0) {
            int removed = conversationManager.truncateByTokenBudget(convId, maxTokens);
            if (removed > 0) {
                logger.info("Per-iteration truncation for agent '{}' at iteration {}: removed {} messages (budget {} tokens)",
                        originalAgent.getId(), iteration, removed, maxTokens);
            }
        }

        // Call the REAL requestAgent → goes through permits, retries, rate limiting.
        // First iteration: use conversationId overload (adds userMessage + assistant response to conversation).
        // Subsequent iterations: use history overload (stateless), then manually store assistant response
        // in conversation so the next iteration has a consistent view.
        CompletableFuture<AgentResult> requestFuture;
        if (iteration == 0) {
            requestFuture = agentService.requestAgent(virtualAgent.getId(), userMessage, convId);
        } else {
            List<Message> history = conversationManager.getHistory(convId);
            requestFuture = agentService.requestAgent(virtualAgent.getId(), null, history)
                    .thenApply(result -> {
                        // Stateless overload doesn't update conversation — store assistant message manually
                        // so handleFunctionCalls/replaceLastAssistantMessage works correctly.
                        String content = result.getContent() != null ? result.getContent() : "";
                        if (result.hasFunctionCalls()) {
                            StringBuilder toolSummary = new StringBuilder();
                            for (FunctionCall call : result.getFunctionCalls()) {
                                if (toolSummary.length() > 0) toolSummary.append("\n");
                                toolSummary.append("[Tool call: ").append(call.getName())
                                        .append("(").append(call.getArguments() != null ? call.getArguments() : "")
                                        .append(")]");
                            }
                            content = content.isEmpty() ? toolSummary.toString() : content + "\n" + toolSummary;
                        }
                        conversationManager.addAssistantMessage(convId, content);
                        return result;
                    });
        }

        return requestFuture
                .thenCompose(result -> {
                    // Accumulate token usage from this iteration
                    cumulativeUsage.accumulate(result.getUsage());
                    return handleResponse(
                            result, virtualAgent, originalAgent, convId, userMessage,
                            toolExecutor, iteration, maxIterations, cumulativeUsage,
                            thisIterationStartMs);
                });
    }

    /**
     * Handles the agent response: checks for task_over, executes tools, or nudges.
     */
    private CompletableFuture<AgentResult> handleResponse(AgentResult result,
                                                          Agent virtualAgent, Agent originalAgent,
                                                          String convId, String userMessage,
                                                          ToolExecutor toolExecutor,
                                                          int iteration, int maxIterations,
                                                          TokenUsage cumulativeUsage,
                                                          long thisIterationStartMs) {
        if (result.hasFunctionCalls()) {
            return handleFunctionCalls(result, virtualAgent, originalAgent, convId, userMessage,
                    toolExecutor, iteration, maxIterations, cumulativeUsage, thisIterationStartMs);
        }

        // No function calls - agent is "thinking aloud" or returned structured output as text
        String textContent = result.getContent() != null ? result.getContent() : "";
        logger.debug("Agent '{}' thinking: {}",
                originalAgent.getId(),
                textContent.length() > 100 ? textContent.substring(0, 100) + "..." : textContent);

        // Try to parse text as resultClass (GPT sometimes returns structured JSON as text
        // instead of calling task_over)
        AgentResult parsedResult = tryParseAsResult(textContent, originalAgent);
        if (parsedResult != null) {
            logger.info("Agent '{}' returned structured result as text at iteration {} - "
                    + "auto-completing (skipping nudge)", originalAgent.getId(), iteration);
            return CompletableFuture.completedFuture(parsedResult);
        }

        // Conversational agents: a plain reply IS the end of the turn. Stop the loop
        // cleanly instead of nudging the model into another iteration.
        if (Boolean.TRUE.equals(originalAgent.getEndTurnOnPlainReply())) {
            logger.debug("Agent '{}' ended turn on plain reply at iteration {}",
                    originalAgent.getId(), iteration);
            return CompletableFuture.completedFuture(new DefaultResult(textContent));
        }

        // Nudge: requestAgent already stored the assistant message in conversation.
        // We just need to continue the loop - the next iteration will send a continuation message.
        return executeLoop(virtualAgent, originalAgent, convId, userMessage,
                toolExecutor, iteration + 1, maxIterations, cumulativeUsage, thisIterationStartMs);
    }

    /**
     * Handles function calls: checks for task_over, executes other tools.
     */
    private CompletableFuture<AgentResult> handleFunctionCalls(AgentResult result,
                                                               Agent virtualAgent, Agent originalAgent,
                                                               String convId, String userMessage,
                                                               ToolExecutor toolExecutor,
                                                               int iteration, int maxIterations,
                                                               TokenUsage cumulativeUsage,
                                                               long thisIterationStartMs) {
        List<FunctionCall> calls = result.getFunctionCalls();

        // requestAgent already stored the assistant message (with tool call summary) in conversation.
        // Now we need to add proper tool call history and execute them.

        // Replace the summary message with proper assistant-with-tool-calls message
        // (the last message added by requestAgent is a text summary, we need structured tool calls)
        conversationManager.replaceLastAssistantMessage(convId,
                Message.assistantWithToolCalls(result.getContent(), calls));

        Integer maxTokens = originalAgent.getMaxToolTokenOutput();
        boolean infiniteLoop = Boolean.TRUE.equals(originalAgent.getInfiniteLoop())
                || Boolean.TRUE.equals(originalAgent.getDisableTaskOver());  // legacy alias

        // Build a quick lookup of endsTurn tool names from the virtual agent's functions.
        // task_over is always endsTurn=true when present in the virtual agent.
        Set<String> endsTurnToolNames = new HashSet<>();
        if (virtualAgent.getFunctions() != null) {
            for (FunctionConfig fc : virtualAgent.getFunctions()) {
                if (Boolean.TRUE.equals(fc.getEndsTurn())
                        || ToolBuilder.TASK_OVER_FUNCTION_NAME.equals(fc.getName())) {
                    endsTurnToolNames.add(fc.getName());
                }
            }
        }

        AgentResult endTurnResult = null;

        for (FunctionCall call : calls) {
            boolean isTaskOver = ToolBuilder.TASK_OVER_FUNCTION_NAME.equals(call.getName());
            boolean isEndsTurn = endsTurnToolNames.contains(call.getName());

            if (isTaskOver && infiniteLoop) {
                // Infinite-loop agent: reject hallucinated task_over calls.
                logger.warn("Agent '{}' emitted task_over at iteration {} despite infiniteLoop=true — ignoring and continuing the loop",
                        originalAgent.getId(), iteration);
                conversationManager.addMessage(convId,
                        Message.toolResult(call.getId(), call.getName(),
                                "Error: task_over is not available for this agent. "
                                        + "Keep acting via the other tools — the loop will only end on external cancellation."));
                continue;
            }

            if (isTaskOver) {
                // Special case: task_over carries a structured result (derived from agent.resultClass).
                logger.info("Agent '{}' called task_over at iteration {}",
                        originalAgent.getId(), iteration);
                endTurnResult = deserializeTaskOverResult(call, originalAgent);
                conversationManager.addMessage(convId,
                        Message.toolResult(call.getId(), call.getName(), "Task completed."));
                continue;
            }

            // Execute the tool (normal OR endsTurn).
            String toolResult;
            try {
                logger.debug("Executing tool '{}' with args: {}", call.getName(), call.getArguments());
                toolResult = toolExecutor.execute(call);
            } catch (Exception e) {
                logger.warn("Tool '{}' execution failed: {}", call.getName(), e.getMessage());
                toolResult = "Error executing " + call.getName() + ": " + e.getMessage();
            }
            toolResult = trimToolOutput(toolResult, maxTokens, call.getName());
            conversationManager.addMessage(convId,
                    Message.toolResult(call.getId(), call.getName(), toolResult));

            if (isEndsTurn) {
                // Any user-declared endsTurn tool: capture the tool result as the final content
                // of the turn, and stop the loop after this batch of calls.
                logger.info("Agent '{}' called endsTurn tool '{}' at iteration {} — ending turn",
                        originalAgent.getId(), call.getName(), iteration);
                if (endTurnResult == null) {
                    String finalContent = result.getContent() != null && !result.getContent().isEmpty()
                            ? result.getContent() : toolResult;
                    DefaultResult dr = new DefaultResult(finalContent, calls);
                    endTurnResult = dr;
                }
            }
        }

        if (endTurnResult != null) {
            return CompletableFuture.completedFuture(endTurnResult);
        }

        // Continue loop - tool results are in conversation, next requestAgent will pick them up
        return executeLoop(virtualAgent, originalAgent, convId, userMessage,
                toolExecutor, iteration + 1, maxIterations, cumulativeUsage, thisIterationStartMs);
    }

    private String trimToolOutput(String output, Integer maxTokens, String toolName) {
        if (maxTokens == null || output == null) {
            return output;
        }
        int maxChars = maxTokens * 4;
        if (output.length() <= maxChars) {
            return output;
        }
        logger.info("Trimming tool '{}' output from ~{} tokens to {} tokens ({}→{} chars)",
                toolName, output.length() / 4, maxTokens, output.length(), maxChars);
        return output.substring(0, maxChars) + "\n... [trimmed: output exceeded " + maxTokens + " token limit]";
    }

    private AgentResult tryParseAsResult(String textContent, Agent originalAgent) {
        if (textContent == null || textContent.trim().isEmpty()) {
            return null;
        }
        if (originalAgent.getResultClass() == null || originalAgent.getResultClass().isEmpty()) {
            return null;
        }
        String trimmed = textContent.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        if (!trimmed.startsWith("{")) {
            return null;
        }
        String resolvedClassName = AgentServiceConfig.resolveClassName(
                originalAgent.getResultClass(), config.getAgentResultClassPackage());
        if (resolvedClassName == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Class<? extends AgentResult> resultClass =
                    (Class<? extends AgentResult>) Class.forName(resolvedClassName);
            return AgentResult.jsonMapper(trimmed, resultClass);
        } catch (Exception e) {
            logger.debug("Text content did not parse as '{}': {}", resolvedClassName, e.getMessage());
            return null;
        }
    }

    private AgentResult deserializeTaskOverResult(FunctionCall call, Agent originalAgent) {
        String arguments = call.getArguments();

        if (originalAgent.getResultClass() == null || originalAgent.getResultClass().isEmpty()) {
            return new DefaultResult(arguments);
        }

        String resolvedClassName = AgentServiceConfig.resolveClassName(
                originalAgent.getResultClass(), config.getAgentResultClassPackage());
        if (resolvedClassName == null) {
            logger.warn("Cannot resolve result class '{}', returning raw arguments",
                    originalAgent.getResultClass());
            return new DefaultResult(arguments);
        }

        try {
            @SuppressWarnings("unchecked")
            Class<? extends AgentResult> resultClass =
                    (Class<? extends AgentResult>) Class.forName(resolvedClassName);
            return AgentResult.jsonMapper(arguments, resultClass);
        } catch (ClassNotFoundException e) {
            logger.warn("Result class not found '{}', returning raw arguments", resolvedClassName);
            return new DefaultResult(arguments);
        } catch (Exception e) {
            logger.warn("Failed to deserialize task_over result to '{}': {}",
                    resolvedClassName, e.getMessage());
            return new DefaultResult(arguments);
        }
    }
}
