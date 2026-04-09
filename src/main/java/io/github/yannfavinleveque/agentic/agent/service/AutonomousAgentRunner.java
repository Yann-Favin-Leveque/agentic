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
import java.util.List;
import java.util.Map;
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
        boolean disableTaskOver = Boolean.TRUE.equals(original.getDisableTaskOver());

        List<FunctionConfig> functions = new ArrayList<>();
        if (original.getFunctions() != null) {
            functions.addAll(original.getFunctions());
        }

        String instructions = original.getInstructions() != null ? original.getInstructions() : "";
        if (!disableTaskOver) {
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
        return executeLoop(virtualAgent, originalAgent, convId, userMessage, toolExecutor, 0, maxIterations, cumulativeUsage)
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
     */
    private CompletableFuture<AgentResult> executeLoop(Agent virtualAgent, Agent originalAgent,
                                                       String convId, String userMessage,
                                                       ToolExecutor toolExecutor,
                                                       int iteration, int maxIterations,
                                                       TokenUsage cumulativeUsage) {
        if (isMaxIterationsExceeded(originalAgent, iteration, maxIterations)) {
            return CompletableFuture.failedFuture(new AgentException(
                    AgentException.ErrorCode.MAX_ITERATIONS_EXCEEDED,
                    "Autonomous agent '" + originalAgent.getId()
                            + "' exceeded max iterations (" + maxIterations + ")"));
        }

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
                            toolExecutor, iteration, maxIterations, cumulativeUsage);
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
                                                          TokenUsage cumulativeUsage) {
        if (result.hasFunctionCalls()) {
            return handleFunctionCalls(result, virtualAgent, originalAgent, convId, userMessage,
                    toolExecutor, iteration, maxIterations, cumulativeUsage);
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

        // Nudge: requestAgent already stored the assistant message in conversation.
        // We just need to continue the loop - the next iteration will send a continuation message.
        return executeLoop(virtualAgent, originalAgent, convId, userMessage,
                toolExecutor, iteration + 1, maxIterations, cumulativeUsage);
    }

    /**
     * Handles function calls: checks for task_over, executes other tools.
     */
    private CompletableFuture<AgentResult> handleFunctionCalls(AgentResult result,
                                                               Agent virtualAgent, Agent originalAgent,
                                                               String convId, String userMessage,
                                                               ToolExecutor toolExecutor,
                                                               int iteration, int maxIterations,
                                                               TokenUsage cumulativeUsage) {
        List<FunctionCall> calls = result.getFunctionCalls();

        // requestAgent already stored the assistant message (with tool call summary) in conversation.
        // Now we need to add proper tool call history and execute them.

        // Replace the summary message with proper assistant-with-tool-calls message
        // (the last message added by requestAgent is a text summary, we need structured tool calls)
        conversationManager.replaceLastAssistantMessage(convId,
                Message.assistantWithToolCalls(result.getContent(), calls));

        Integer maxTokens = originalAgent.getMaxToolTokenOutput();
        boolean disableTaskOver = Boolean.TRUE.equals(originalAgent.getDisableTaskOver());
        AgentResult taskOverResult = null;

        for (FunctionCall call : calls) {
            if (ToolBuilder.TASK_OVER_FUNCTION_NAME.equals(call.getName())) {
                if (disableTaskOver) {
                    // Agent was configured with disableTaskOver=true: task_over was not
                    // advertised as a tool. If the model hallucinates a call to it, we reject
                    // the call and nudge the model to keep acting instead of completing.
                    logger.warn("Agent '{}' emitted task_over at iteration {} despite disableTaskOver=true — ignoring and continuing the loop",
                            originalAgent.getId(), iteration);
                    conversationManager.addMessage(convId,
                            Message.toolResult(call.getId(), call.getName(),
                                    "Error: task_over is not available for this agent. "
                                            + "Keep acting via the other tools — the loop will only end on external cancellation."));
                    continue;
                }
                logger.info("Agent '{}' called task_over at iteration {}",
                        originalAgent.getId(), iteration);
                taskOverResult = deserializeTaskOverResult(call, originalAgent);
                conversationManager.addMessage(convId,
                        Message.toolResult(call.getId(), call.getName(), "Task completed."));
            } else {
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
            }
        }

        if (taskOverResult != null) {
            return CompletableFuture.completedFuture(taskOverResult);
        }

        // Continue loop - tool results are in conversation, next requestAgent will pick them up
        return executeLoop(virtualAgent, originalAgent, convId, userMessage,
                toolExecutor, iteration + 1, maxIterations, cumulativeUsage);
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
