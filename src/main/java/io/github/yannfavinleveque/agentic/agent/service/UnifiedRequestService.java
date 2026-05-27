package io.github.yannfavinleveque.agentic.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.config.RetryConfig;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.core.Instance;
import io.github.yannfavinleveque.agentic.agent.core.Provider;
import io.github.yannfavinleveque.agentic.agent.core.ProviderConfig;
import io.github.yannfavinleveque.agentic.agent.custom.CustomProviderSpec;
import io.github.yannfavinleveque.agentic.agent.custom.Feature;
import io.github.yannfavinleveque.agentic.agent.custom.FeatureValidator;
import io.github.yannfavinleveque.agentic.agent.exception.AgentException;
import io.github.yannfavinleveque.agentic.agent.exception.ContentFilterException;
import io.github.yannfavinleveque.agentic.agent.exception.RateLimitException;
import io.github.yannfavinleveque.agentic.agent.exception.RequestTimeoutException;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import io.github.yannfavinleveque.agentic.agent.model.CacheableSegment;
import io.github.yannfavinleveque.agentic.agent.model.ClaudeRequest;
import io.github.yannfavinleveque.agentic.agent.model.ClaudeResponse;
import io.github.yannfavinleveque.agentic.agent.model.DefaultResult;
import io.github.yannfavinleveque.agentic.agent.model.FunctionCall;
import io.github.yannfavinleveque.agentic.agent.model.Message;
import io.github.yannfavinleveque.agentic.agent.model.ModelRequestOptions;
import io.github.yannfavinleveque.agentic.common.ModelPricing;
import io.github.yannfavinleveque.agentic.common.ResponseFormat;
import io.github.yannfavinleveque.agentic.common.TokenUsage;
import io.github.yannfavinleveque.agentic.domain.chat.Chat;
import io.github.yannfavinleveque.agentic.domain.chat.ChatMessage;
import io.github.yannfavinleveque.agentic.domain.chat.ChatRequest;
import io.github.yannfavinleveque.agentic.domain.embedding.EmbeddingRequest;
import io.github.yannfavinleveque.agentic.domain.image.Image;
import io.github.yannfavinleveque.agentic.domain.image.ImageRequest;
import io.github.yannfavinleveque.agentic.domain.image.ImageRequest.Quality;
import io.github.yannfavinleveque.agentic.domain.image.ImageResponseFormat;
import io.github.yannfavinleveque.agentic.domain.image.Size;
import io.github.yannfavinleveque.agentic.support.HttpHelper;
import io.github.yannfavinleveque.agentic.support.JsonSchemaGenerator;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Unified stateless request service for both OpenAI and Claude. Uses OpenAI Responses API and
 * Claude Messages API for a fully stateless architecture.
 * <p>
 * This service handles:
 * </p>
 * <ul>
 * <li>Agent requests: OpenAI Responses API and Claude Messages API</li>
 * <li>Chat completions: OpenAI Chat Completions API and Claude Messages API</li>
 * <li>Embeddings: OpenAI Embeddings API</li>
 * <li>Image generation: DALL-E API</li>
 * </ul>
 * <p>
 * Key features:
 * </p>
 * <ul>
 * <li>Fully stateless - no threads, no assistants</li>
 * <li>Conversation history passed with each request</li>
 * <li>Unified tool support (web search, code interpreter, custom functions)</li>
 * <li>Auto-detection of provider based on model name</li>
 * </ul>
 *
 * @see AgentService#requestAgent(String, String, List)
 */
public class UnifiedRequestService {

    private static final Logger logger = LoggerFactory.getLogger(UnifiedRequestService.class);

    /**
     * Scheduler for non-blocking delays.
     */
    private static final ScheduledExecutorService DELAY_SCHEDULER = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "unified-delay-scheduler");
        t.setDaemon(true);
        return t;
    });

    // Concurrency control
    private final Map<String, InstanceLimiter> instanceLimiters = new ConcurrentHashMap<>();

    private static class InstanceLimiter {

        final AtomicInteger inProgress = new AtomicInteger(0);
        final AtomicLong lastRequestTimeMs = new AtomicLong(0);
        final int maxConcurrent;
        final long minIntervalMs; // minimum ms between requests (1000 / requestsPerSecond)
        final String instanceId;
        /** Queue of waiters for when all concurrent slots are taken. */
        final java.util.Queue<CompletableFuture<Void>> waitQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

        InstanceLimiter(String instanceId, int maxConcurrent, int requestsPerSecond) {
            this.instanceId = instanceId;
            this.maxConcurrent = maxConcurrent;
            this.minIntervalMs = requestsPerSecond > 0 ? 1000L / requestsPerSecond : 0;
        }

        /**
         * Returns the delay in ms needed before the next request can be sent.
         * Updates lastRequestTimeMs atomically to "claim" the next slot.
         */
        long acquireRateSlot() {
            if (minIntervalMs <= 0) return 0;
            while (true) {
                long last = lastRequestTimeMs.get();
                long now = System.currentTimeMillis();
                long earliest = last + minIntervalMs;
                long nextTime = Math.max(now, earliest);
                if (lastRequestTimeMs.compareAndSet(last, nextTime)) {
                    return Math.max(0, nextTime - now);
                }
            }
        }

        boolean tryAcquire() {
            int current = inProgress.get();
            if (current >= maxConcurrent)
                return false;
            return inProgress.compareAndSet(current, current + 1);
        }

        /**
         * Non-blocking acquire: returns a future that completes when a permit is available.
         * If a permit is immediately available, returns an already-completed future.
         * Otherwise, queues the caller and completes the future when release() frees a slot.
         */
        CompletableFuture<Void> acquireAsync() {
            if (tryAcquire()) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> waiter = new CompletableFuture<>();
            waitQueue.add(waiter);
            // Double-check: a slot may have freed between tryAcquire and queue.add
            if (tryAcquire()) {
                CompletableFuture<Void> polled = waitQueue.poll();
                if (polled != null) {
                    polled.complete(null);
                }
            }
            return waiter;
        }

        void release() {
            inProgress.decrementAndGet();
            // Wake up next waiter in queue, if any
            drainWaiters();
        }

        private void drainWaiters() {
            while (!waitQueue.isEmpty() && tryAcquire()) {
                CompletableFuture<Void> waiter = waitQueue.poll();
                if (waiter != null) {
                    waiter.complete(null);
                } else {
                    // No waiter found despite non-empty check — race condition, release the permit
                    inProgress.decrementAndGet();
                    break;
                }
            }
        }

        int getCurrentCount() {
            return inProgress.get();
        }

    }

    /**
     * Internal class representing a parsed API response with text content and function calls.
     */
    @Data
    private static class ParsedResponse {
        private final String textContent;
        private final List<FunctionCall> functionCalls;
        private final TokenUsage tokenUsage;

        /**
         * Stream-only: the provider's finish/stop reason for the streamed turn
         * ({@code finish_reason} for OpenAI, {@code stop_reason} for Anthropic). Lets the streaming
         * boundary check detect a tool-call turn. Null on the blocking path.
         */
        private String streamStopReason;

        static ParsedResponse ofText(String text) {
            return new ParsedResponse(text, Collections.emptyList(), null);
        }

        static ParsedResponse ofText(String text, TokenUsage tokenUsage) {
            return new ParsedResponse(text, Collections.emptyList(), tokenUsage);
        }

        static ParsedResponse ofFunctionCalls(List<FunctionCall> calls) {
            return new ParsedResponse(null, calls, null);
        }

        static ParsedResponse of(String text, List<FunctionCall> calls) {
            return new ParsedResponse(text, calls != null ? calls : Collections.emptyList(), null);
        }

        static ParsedResponse of(String text, List<FunctionCall> calls, TokenUsage tokenUsage) {
            return new ParsedResponse(text, calls != null ? calls : Collections.emptyList(), tokenUsage);
        }

        boolean hasFunctionCalls() {
            return functionCalls != null && !functionCalls.isEmpty();
        }
    }

    private final AgentServiceConfig config;
    private final HttpHelper httpHelper;
    private final InstanceRouter instanceRouter;
    private final ClaudeAdapter claudeAdapter;
    private final ObjectMapper objectMapper;
    private final AgentManager agentManager;

    public UnifiedRequestService(AgentServiceConfig config, HttpHelper httpHelper,
            InstanceRouter instanceRouter, ClaudeAdapter claudeAdapter,
            ObjectMapper objectMapper, AgentManager agentManager) {
        this.config = config;
        this.httpHelper = httpHelper;
        this.instanceRouter = instanceRouter;
        this.claudeAdapter = claudeAdapter;
        this.objectMapper = objectMapper;
        this.agentManager = agentManager;
        logger.info("UnifiedRequestService initialized (stateless mode)");
    }

    // ==================== MAIN API ====================

    /**
     * Sends a stateless request to an agent with conversation history.
     *
     * @param agentId     Agent ID
     * @param userMessage Current user message
     * @param history     Previous conversation messages (can be null or empty)
     * @return CompletableFuture with the agent's response
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, List<Message> history) {
        Agent agent = agentManager.getAgent(agentId);
        return attemptRequestWithRetry(agent, segmentsOf(userMessage), history, 0);
    }

    /**
     * Sends a stateless request to a pre-resolved {@link Agent} with conversation history.
     * Used by {@link AgentService} when it needs to rewrite agent fields (e.g. Mustache
     * variable substitution into {@code instructions}) before the request, without mutating
     * the registered agent shared by other concurrent calls.
     *
     * @param agent       Resolved agent (caller is responsible for not mutating it during the call)
     * @param userMessage Current user message
     * @param history     Previous conversation messages (can be null or empty)
     * @return CompletableFuture with the agent's response
     */
    public CompletableFuture<AgentResult> requestAgent(Agent agent, String userMessage, List<Message> history) {
        return attemptRequestWithRetry(agent, segmentsOf(userMessage), history, 0);
    }

    /**
     * Segment-aware variant of {@link #requestAgent(Agent, String, List)}. Instead of a single
     * user-message string, the caller supplies an ordered list of {@link CacheableSegment}s for the
     * current user turn. Anthropic providers turn each segment into a {@code text} content block and
     * place a {@code cache_control} marker at every requested boundary (respecting Anthropic's
     * 4-breakpoint cap); all other providers concatenate the segments in order into a single user
     * message (their caching is automatic on a stable prefix, or unsupported). History handling is
     * unchanged.
     *
     * @param agent        Resolved agent
     * @param userSegments Ordered segments for the current user turn (must be non-null/non-empty)
     * @param history      Previous conversation messages (can be null or empty)
     * @return CompletableFuture with the agent's response
     */
    public CompletableFuture<AgentResult> requestAgent(Agent agent, List<CacheableSegment> userSegments,
            List<Message> history) {
        return attemptRequestWithRetry(agent, userSegments, history, 0);
    }

    /**
     * Segment-aware variant resolving the agent by id. See
     * {@link #requestAgent(Agent, List, List)}.
     */
    public CompletableFuture<AgentResult> requestAgentSegments(String agentId,
            List<CacheableSegment> userSegments, List<Message> history) {
        Agent agent = agentManager.getAgent(agentId);
        return attemptRequestWithRetry(agent, userSegments, history, 0);
    }

    // ==================== STREAMING (token-by-token SSE) ====================

    /**
     * Streaming variant of {@link #requestAgent(Agent, String, List)}. Performs a SINGLE streamed
     * model call: as the provider emits natural-language text deltas, each fragment is forwarded to
     * {@code onToken}; the completed future yields a fully-reconstructed {@link AgentResult}
     * (content = accumulated text, usage = the usage reported in the stream, cost via the usual
     * {@code calculatePricing} path).
     *
     * <p><b>Provider support:</b> only Azure OpenAI / OpenAI (Responses-shaped — see note) and
     * Anthropic / Azure Anthropic stream token-by-token. For every other provider this method
     * transparently falls back to the blocking {@link #attemptRequestWithRetry} path (no tokens are
     * emitted, the final {@link AgentResult} is returned as usual).</p>
     *
     * <p><b>OpenAI note:</b> streaming uses the Chat Completions wire format
     * ({@code choices[].delta.content}); the blocking agent path uses the Responses API. The
     * streamed text and usage are therefore parsed from Chat Completions chunks. Tools/structured
     * output are NOT sent on the streamed call (streaming is for the final text turn only).</p>
     *
     * <p><b>Tool-calling boundary (V1 behavior):</b> the stream is treated as the final turn ONLY if
     * it produces plain text with no tool call. If the provider signals a tool call
     * ({@code finish_reason == tool_calls} / {@code stop_reason == tool_use}), the streamed text is
     * <em>discarded</em> and the same turn is re-issued through the normal blocking path, whose
     * {@link AgentResult} (carrying the parsed {@code functionCalls}) is returned. The caller's
     * agentic loop then executes the tool and calls {@code requestAgentStreaming} again for the next
     * turn. Tool-calling is never broken; at worst a tool-call turn is computed twice (once streamed
     * and thrown away, once blocking). Retries/rate-limiting wrap the whole operation.</p>
     *
     * @param agent        resolved agent (caller must not mutate during the call)
     * @param userSegments ordered user-turn segments (null on autonomous follow-up iterations)
     * @param history      previous conversation messages (nullable)
     * @param onToken      callback invoked per text fragment (nullable → behaves like blocking)
     * @return future with the reconstructed {@link AgentResult}
     */
    public CompletableFuture<AgentResult> requestAgentStreaming(
            Agent agent, List<CacheableSegment> userSegments, List<Message> history, Consumer<String> onToken) {
        if (onToken == null) {
            // No sink → no point streaming; behave exactly like the blocking path.
            return attemptRequestWithRetry(agent, userSegments, history, 0);
        }
        return attemptStreamingWithRetry(agent, userSegments, history, onToken, 0);
    }

    private CompletableFuture<AgentResult> attemptStreamingWithRetry(
            Agent agent, List<CacheableSegment> userSegments, List<Message> history,
            Consumer<String> onToken, int attempt) {

        return executeStreamingRequest(agent, userSegments, history, onToken)
                .thenCompose(parsed -> {
                    // Tool-call boundary: a streamed turn that ended up wanting a tool is NOT the
                    // final answer. Discard the streamed text and re-run the SAME turn blocking so
                    // the function calls are parsed correctly. (Tokens already emitted are ignored
                    // by the caller's agentic loop, which will re-stream the next turn.)
                    if (parsed.hasFunctionCalls() || isToolCallStop(parsed)) {
                        logger.info("Streaming turn produced a tool call (stop={}); falling back to blocking turn "
                                + "for correct tool parsing [Agent: {}]", parsed.streamStopReason, agent.getName());
                        return attemptRequestWithRetry(agent, userSegments, history, 0);
                    }
                    return deserializeResponse(agent, parsed);
                })
                .handle((result, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(result);
                    }
                    Throwable cause = unwrapException(error);
                    int maxRetries = getMaxRetriesForError(cause, agent);
                    if (maxRetries < 0 || attempt >= maxRetries) {
                        return CompletableFuture.<AgentResult>failedFuture(cause);
                    }
                    long delay = calculateDelay(cause, attempt);
                    logger.warn("Streaming request failed (attempt {}/{}), retrying in {}ms: {}",
                            attempt + 1, maxRetries, delay, cause.getMessage());
                    return delayAsync(delay)
                            .thenCompose(v -> attemptStreamingWithRetry(agent, userSegments, history, onToken, attempt + 1));
                })
                .thenCompose(f -> f);
    }

    private static boolean isToolCallStop(ParsedResponse parsed) {
        String stop = parsed.streamStopReason;
        return "tool_calls".equals(stop) || "tool_use".equals(stop);
    }

    private CompletableFuture<ParsedResponse> executeStreamingRequest(
            Agent agent, List<CacheableSegment> userSegments, List<Message> history, Consumer<String> onToken) {

        if (instanceRouter.isDegradedMode()) {
            return CompletableFuture.failedFuture(
                    new AgentException(AgentException.ErrorCode.NO_INSTANCE_AVAILABLE, "No instances configured"));
        }

        int instanceIdx = instanceRouter.getNextInstanceForModel(agent.getModel(), agent.getInstances());
        Instance instance = instanceRouter.getInstance(instanceIdx);
        InstanceLimiter limiter = getLimiterForInstanceAndModel(instance, agent.getModel());

        // Only Anthropic and OpenAI-shaped Azure OpenAI/OpenAI providers stream token-by-token.
        boolean anthropic = ProviderConfig.isAnthropicModel(agent.getModel())
                || instance.getProvider() == Provider.ANTHROPIC
                || instance.getProvider() == Provider.AZURE_ANTHROPIC;
        boolean openai = instance.getProvider() == Provider.OPENAI
                || instance.getProvider() == Provider.AZURE_OPENAI
                || instance.getProvider() == Provider.AZURE;
        if (!anthropic && !openai) {
            // Unsupported provider for streaming → run blocking (no tokens) through the standard
            // permit-managed path and adapt the AgentResult back to a ParsedResponse.
            logger.debug("Streaming not supported for provider {} / model {} — using blocking path",
                    instance.getProvider(), agent.getModel());
            return executeRequest(agent, userSegments, history, 0);
        }

        return limiter.acquireAsync().thenCompose(v -> {
            long rateDelay = limiter.acquireRateSlot();
            if (rateDelay > 0) {
                return delayAsync(rateDelay).thenCompose(v2 ->
                        executeStreamingAfterPermit(agent, userSegments, history, instance, limiter, onToken, anthropic));
            }
            return executeStreamingAfterPermit(agent, userSegments, history, instance, limiter, onToken, anthropic);
        });
    }

    private CompletableFuture<ParsedResponse> executeStreamingAfterPermit(
            Agent agent, List<CacheableSegment> userSegments, List<Message> history,
            Instance instance, InstanceLimiter limiter, Consumer<String> onToken, boolean anthropic) {

        logger.info("→ REQUEST START [STREAM] | Agent: {} | Model: {} | Instance: {}",
                agent.getName(), agent.getModel(), instance.getId());

        CompletableFuture<ParsedResponse> requestFuture;
        try {
            requestFuture = anthropic
                    ? executeClaudeStreamRequest(agent, userSegments, history, instance, onToken)
                    : executeOpenAIStreamRequest(agent, userSegments, history, instance, onToken);
        } catch (Exception e) {
            requestFuture = CompletableFuture.failedFuture(e);
        }

        return requestFuture.whenComplete((response, error) -> {
            limiter.release();
            if (error == null) {
                String usageLog = response.getTokenUsage() != null
                        ? " | " + ModelPricing.formatForLog(response.getTokenUsage()) : "";
                logger.info("← RESPONSE END [STREAM] | Agent: {}{}", agent.getName(), usageLog);
            } else {
                logger.error("← RESPONSE ERROR [STREAM] | Agent: {} | Error: {}", agent.getName(), error.getMessage());
            }
        });
    }

    /**
     * Streamed Anthropic turn. Builds messages exactly like {@link #executeClaudeRequest} and
     * delegates to {@link ClaudeAdapter#callClaudeStreamAsync}; the synthetic {@link ClaudeResponse}
     * is parsed through the normal {@link #parseClaudeResponse}, then the stream stop_reason is
     * threaded onto the {@link ParsedResponse} so the boundary check can detect a tool turn.
     */
    private CompletableFuture<ParsedResponse> executeClaudeStreamRequest(
            Agent agent, List<CacheableSegment> userSegments, List<Message> history,
            Instance instance, Consumer<String> onToken) {

        List<ClaudeRequest.ClaudeMessage> messages = new ArrayList<>();
        if (history != null) {
            for (Message msg : history) {
                if (!msg.isSystem()) {
                    messages.add(buildClaudeMessage(msg));
                }
            }
        }
        if (userSegments != null && !userSegments.isEmpty()) {
            messages.add(ClaudeRequest.ClaudeMessage.builder()
                    .role("user")
                    .contentBlocks(ClaudeAdapter.buildUserContentBlocks(userSegments))
                    .build());
        }

        List<ClaudeRequest.ClaudeTool> tools = ToolBuilder.buildClaudeTools(agent);

        Class<?> resultClass = null;
        if (agent.getResultClass() != null && !agent.getResultClass().isEmpty()) {
            String fullClassName = config.resolveResultClassName(agent.getResultClass());
            if (fullClassName != null) {
                try {
                    resultClass = Class.forName(fullClassName);
                } catch (ClassNotFoundException e) {
                    logger.warn("Result class not found: {} (resolved: {})", agent.getResultClass(), fullClassName);
                }
            }
        }

        return claudeAdapter.callClaudeStreamAsync(
                instance,
                agent.getModel(),
                agent.getInstructions(),
                messages,
                agent.getTemperature(),
                agent.getMaxTokens() != null ? agent.getMaxTokens() : 32768,
                resultClass,
                tools,
                agent.getReasoningEffort(),
                agent.getToolChoice(),
                agent.getResponseTimeout(),
                onToken)
                .thenApply(resp -> {
                    ParsedResponse parsed = parseClaudeResponse(resp, instance);
                    parsed.streamStopReason = resp.getStopReason();
                    return parsed;
                });
    }

    /**
     * Streamed OpenAI / Azure OpenAI turn via the Chat Completions endpoint
     * ({@code choices[].delta.content}). Text fragments are forwarded to {@code onToken} and
     * accumulated; the final {@code usage} chunk (requested via {@code stream_options.include_usage})
     * feeds {@link #calculatePricing}. Tools / structured output are intentionally omitted — the
     * streamed turn is the final natural-language turn; tool turns go through the blocking path.
     */
    private CompletableFuture<ParsedResponse> executeOpenAIStreamRequest(
            Agent agent, List<CacheableSegment> userSegments, List<Message> history,
            Instance instance, Consumer<String> onToken) {

        // Build Chat Completions messages (system + history + current user turn).
        List<Map<String, Object>> messages = new ArrayList<>();
        if (agent.getInstructions() != null && !agent.getInstructions().isEmpty()) {
            Map<String, Object> sys = new HashMap<>();
            sys.put("role", "system");
            sys.put("content", agent.getInstructions());
            messages.add(sys);
        }
        if (history != null) {
            for (Message msg : history) {
                if (msg.isSystem()) continue;
                Map<String, Object> m = new HashMap<>();
                m.put("role", msg.getRole());
                m.put("content", msg.getContent() != null ? msg.getContent() : "");
                messages.add(m);
            }
        }
        String userMessage = (userSegments == null) ? null : ClaudeAdapter.concatSegments(userSegments);
        if (userMessage != null) {
            Map<String, Object> u = new HashMap<>();
            u.put("role", "user");
            u.put("content", userMessage);
            messages.add(u);
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", agent.getModel());
        requestBody.put("messages", messages);
        requestBody.put("max_completion_tokens", agent.getMaxTokens() != null ? agent.getMaxTokens() : 32768);
        if (agent.getTemperature() != null) {
            requestBody.put("temperature", agent.getTemperature());
        }
        requestBody.put("stream", true);
        requestBody.put("stream_options", Map.of("include_usage", true));

        final StringBuilder textAccumulator = new StringBuilder();
        final AtomicReference<Integer> inputTokens = new AtomicReference<>();
        final AtomicReference<Integer> outputTokens = new AtomicReference<>();
        final AtomicReference<Integer> cacheRead = new AtomicReference<>();
        final AtomicReference<String> finishReason = new AtomicReference<>();

        Consumer<String> onLine = line -> {
            io.github.yannfavinleveque.agentic.support.StreamDeltaParsers.Delta d =
                    io.github.yannfavinleveque.agentic.support.StreamDeltaParsers.parseOpenAIDelta(line);
            if (!d.text.isEmpty()) {
                textAccumulator.append(d.text);
                onToken.accept(d.text);
            }
            if (d.inputTokens != null) inputTokens.set(d.inputTokens);
            if (d.outputTokens != null) outputTokens.set(d.outputTokens);
            if (d.cacheReadInputTokens != null) cacheRead.set(d.cacheReadInputTokens);
            if (d.finishReason != null) finishReason.set(d.finishReason);
        };

        return httpHelper.postStream(
                instance,
                ProviderConfig.Endpoint.CHAT_COMPLETIONS,
                agent.getModel(),
                requestBody,
                onLine,
                agent.getResponseTimeout())
                .thenApply(rawBody -> {
                    Integer in = inputTokens.get();
                    Integer cached = cacheRead.get();
                    Integer uncached = in;
                    if (in != null && cached != null) {
                        uncached = Math.max(0, in - cached);
                    }
                    TokenUsage usage = (in != null || outputTokens.get() != null)
                            ? calculatePricing(agent.getModel(), uncached, outputTokens.get(), null, cached, instance)
                            : null;
                    ParsedResponse parsed = ParsedResponse.ofText(textAccumulator.toString(), usage);
                    parsed.streamStopReason = finishReason.get();
                    return parsed;
                });
    }

    /**
     * Wraps a (possibly null) plain user message string into a single non-boundary
     * {@link CacheableSegment}. A {@code null} message → {@code null} segment list (the autonomous
     * loop sends {@code userMessage == null} on follow-up iterations, which must stay null so no
     * extra user message is appended).
     */
    private static List<CacheableSegment> segmentsOf(String userMessage) {
        if (userMessage == null) {
            return null;
        }
        return List.of(new CacheableSegment(userMessage, false));
    }

    /**
     * Sends a stateless request without history (single-turn).
     *
     * @param agentId     Agent ID
     * @param userMessage User message
     * @return CompletableFuture with the agent's response
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage) {
        return requestAgent(agentId, userMessage, (List<Message>) null);
    }

    /**
     * Sends a stateless request with a single image (vision).
     *
     * @param agentId      Agent ID
     * @param userMessage  Text prompt for the image
     * @param imageBase64  Base64-encoded image (PNG assumed)
     * @return CompletableFuture with the agent's response
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, String imageBase64) {
        return requestAgent(agentId, userMessage, null, List.of(imageBase64));
    }

    /**
     * Sends a stateless request with history and optional images (vision).
     *
     * @param agentId       Agent ID
     * @param userMessage   Text prompt
     * @param history       Previous conversation messages (can be null)
     * @param imagesBase64  List of base64-encoded images (can be null)
     * @return CompletableFuture with the agent's response
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, List<Message> history,
            List<String> imagesBase64) {
        Agent agent = agentManager.getAgent(agentId);
        return requestAgent(agent, userMessage, history, imagesBase64);
    }

    /**
     * Vision overload that takes a pre-resolved {@link Agent} (e.g. with Mustache variables
     * already substituted into {@code instructions}). See {@link #requestAgent(Agent, String, List)}
     * for rationale.
     */
    public CompletableFuture<AgentResult> requestAgent(Agent agent, String userMessage, List<Message> history,
            List<String> imagesBase64) {
        // Build content parts: text + images
        List<Message.ContentPart> contentParts = new ArrayList<>();
        contentParts.add(Message.ContentPart.text(userMessage));

        if (imagesBase64 != null) {
            for (String imageBase64 : imagesBase64) {
                contentParts.add(Message.ContentPart.pngBase64(imageBase64));
            }
        }

        // Create user message with multimodal content
        Message userMsg = Message.builder()
                .role("user")
                .content(contentParts)
                .build();

        // Build full history with current message
        List<Message> fullHistory = new ArrayList<>();
        if (history != null) {
            fullHistory.addAll(history);
        }
        fullHistory.add(userMsg);

        return attemptRequestAgentWithImages(agent, fullHistory, 0);
    }

    private CompletableFuture<AgentResult> attemptRequestAgentWithImages(Agent agent, List<Message> messagesWithUser, int attempt) {
        return executeRequestAgentWithImages(agent, messagesWithUser, attempt)
                .thenCompose(parsed -> deserializeResponse(agent, parsed))
                .handle((result, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(result);
                    }

                    Throwable cause = unwrapException(error);
                    int maxRetries = getMaxRetriesForError(cause, agent);

                    if (maxRetries < 0 || attempt >= maxRetries) {
                        return CompletableFuture.<AgentResult>failedFuture(cause);
                    }

                    long delay = calculateDelay(cause, attempt);
                    logger.warn("Request failed (attempt {}/{}), retrying in {}ms: {} [{}]",
                            attempt + 1, maxRetries, delay, cause.getMessage(),
                            cause instanceof AgentException ? ((AgentException) cause).getErrorCode() : "UNKNOWN");

                    return delayAsync(delay)
                            .thenCompose(v -> attemptRequestAgentWithImages(agent, messagesWithUser, attempt + 1));
                })
                .thenCompose(f -> f);
    }

    private CompletableFuture<ParsedResponse> executeRequestAgentWithImages(Agent agent, List<Message> messagesWithUser, int attempt) {
        if (instanceRouter.isDegradedMode()) {
            return CompletableFuture.failedFuture(
                    new AgentException(AgentException.ErrorCode.NO_INSTANCE_AVAILABLE, "No instances configured"));
        }

        int instanceIdx = instanceRouter.getNextInstanceForModel(agent.getModel(), agent.getInstances());
        Instance instance = instanceRouter.getInstance(instanceIdx);
        InstanceLimiter limiter = getLimiterForInstanceAndModel(instance, agent.getModel());

        // Acquire concurrent stream permit (non-blocking queue-based)
        return limiter.acquireAsync().thenCompose(v -> {
            // Then rate limit: non-blocking wait if too soon since last request on this instance
            long rateDelay = limiter.acquireRateSlot();
            if (rateDelay > 0) {
                return delayAsync(rateDelay)
                        .thenCompose(v2 -> executeRequestAgentWithImagesAfterPermit(agent, messagesWithUser, instance, limiter));
            }
            return executeRequestAgentWithImagesAfterPermit(agent, messagesWithUser, instance, limiter);
        });
    }

    private CompletableFuture<ParsedResponse> executeRequestAgentWithImagesAfterPermit(
            Agent agent, List<Message> messagesWithUser, Instance instance, InstanceLimiter limiter) {

        logger.info("-> REQUEST AGENT [VISION] | Agent: {} | Model: {} | Instance: {} | Messages: {}",
                agent.getName(), agent.getModel(), instance.getId(), messagesWithUser.size());

        CompletableFuture<ParsedResponse> requestFuture;

        // Routing order: custom > anthropic > mistral > grok > deepseek > gemini > openai
        if (instance.getProvider() == Provider.CUSTOM) {
            requestFuture = executeCustomRequest(agent, messagesWithUser, instance);
        } else if (ProviderConfig.isAnthropicModel(agent.getModel())) {
            requestFuture = executeClaudeRequestWithImages(agent, messagesWithUser, instance);
        } else if (MistralAdapter.isMistralModel(agent.getModel())
                || instance.getProvider() == Provider.MISTRAL
                || instance.getProvider() == Provider.AZURE_MISTRAL) {
            requestFuture = executeMistralRequest(agent, messagesWithUser, instance);
        } else if (GrokAdapter.isGrokModel(agent.getModel())
                || instance.getProvider() == Provider.GROK
                || instance.getProvider() == Provider.AZURE_GROK) {
            requestFuture = executeGrokRequest(agent, messagesWithUser, instance);
        } else if (DeepSeekAdapter.isDeepSeekModel(agent.getModel())
                || instance.getProvider() == Provider.DEEPSEEK) {
            requestFuture = executeDeepSeekRequest(agent, messagesWithUser, instance);
        } else if (GeminiAdapter.isGeminiModel(agent.getModel())
                || instance.getProvider() == Provider.GEMINI) {
            requestFuture = executeGeminiRequest(agent, messagesWithUser, instance);
        } else {
            requestFuture = executeOpenAIRequestWithImages(agent, messagesWithUser, instance);
        }

        return requestFuture
                .whenComplete((response, error) -> {
                    limiter.release();
                    if (error == null) {
                        String usageLog = response.getTokenUsage() != null
                                ? " | " + ModelPricing.formatForLog(response.getTokenUsage()) : "";
                        logger.info("<- RESPONSE AGENT [VISION] | Agent: {}{} | Response: {}",
                                agent.getName(), usageLog, response);
                    } else {
                        logger.error("<- ERROR AGENT [VISION] | Agent: {} | Error: {}",
                                agent.getName(), error.getMessage());
                    }
                });
    }

    private CompletableFuture<ParsedResponse> executeOpenAIRequestWithImages(Agent agent, List<Message> messagesWithUser, Instance instance) {
        List<Map<String, Object>> input = new ArrayList<>();

        for (Message msg : messagesWithUser) {
            input.addAll(buildOpenAIInputItems(msg));
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", agent.getModel());
        requestBody.put("input", input);

        if (agent.getInstructions() != null && !agent.getInstructions().isEmpty()) {
            requestBody.put("instructions", agent.getInstructions());
        }

        if (agent.getTemperature() != null) {
            requestBody.put("temperature", agent.getTemperature());
        }

        requestBody.put("max_output_tokens", agent.getMaxTokens() != null ? agent.getMaxTokens() : 32768);

        // Reasoning configuration
        addOpenAIReasoningConfig(requestBody, agent.getReasoningEffort());

        List<Object> tools = buildOpenAIToolsForRequest(agent);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
        }

        if (agent.getResultClass() != null && !agent.getResultClass().isEmpty()) {
            String fullClassName = config.resolveResultClassName(agent.getResultClass());
            if (fullClassName != null) {
                try {
                    Class<?> resultClass = Class.forName(fullClassName);
                    Map<String, Object> schema = JsonSchemaGenerator.createFunctionSchemaFromClass(resultClass);

                    Map<String, Object> textFormat = new HashMap<>();
                    textFormat.put("type", "json_schema");
                    textFormat.put("name", resultClass.getSimpleName().toLowerCase() + "_response");
                    textFormat.put("schema", schema);
                    textFormat.put("strict", true);

                    Map<String, Object> text = new HashMap<>();
                    text.put("format", textFormat);
                    requestBody.put("text", text);
                } catch (ClassNotFoundException e) {
                    logger.warn("Result class not found: {} (resolved: {})", agent.getResultClass(), fullClassName);
                }
            } else {
                logger.warn("Cannot resolve result class '{}' - use FQCN or configure agentResultClassPackage",
                        agent.getResultClass());
            }
        }

        return httpHelper.postRaw(
                instance,
                ProviderConfig.Endpoint.RESPONSES,
                agent.getModel(),
                requestBody,
                agent.getResponseTimeout()).thenApply(json -> extractResponsesContentParsed(json, instance));
    }

    private CompletableFuture<ParsedResponse> executeClaudeRequestWithImages(Agent agent, List<Message> messagesWithUser, Instance instance) {
        List<ClaudeRequest.ClaudeMessage> messages = new ArrayList<>();
        for (Message msg : messagesWithUser) {
            if (!msg.isSystem()) {
                messages.add(buildClaudeMessage(msg));
            }
        }

        List<ClaudeRequest.ClaudeTool> tools = ToolBuilder.buildClaudeTools(agent);

        Class<?> resultClass = null;
        if (agent.getResultClass() != null && !agent.getResultClass().isEmpty()) {
            String fullClassName = config.resolveResultClassName(agent.getResultClass());
            if (fullClassName != null) {
                try {
                    resultClass = Class.forName(fullClassName);
                } catch (ClassNotFoundException e) {
                    logger.warn("Result class not found: {} (resolved: {})", agent.getResultClass(), fullClassName);
                }
            }
        }

        return claudeAdapter.callClaudeAsync(
                instance,
                agent.getModel(),
                agent.getInstructions(),
                messages,
                agent.getTemperature(),
                agent.getMaxTokens() != null ? agent.getMaxTokens() : 32768,
                resultClass,
                tools,
                agent.getReasoningEffort(),
                agent.getToolChoice()).thenApply(resp -> parseClaudeResponse(resp, instance));
    }

    // ==================== MISTRAL CHAT COMPLETIONS ====================

    /**
     * Executes a Mistral request via the OpenAI-compatible /v1/chat/completions endpoint
     * (or /models/chat/completions on Azure AI Foundry). Used for both
     * {@link Provider#MISTRAL} and {@link Provider#AZURE_MISTRAL} instances.
     *
     * <p>Differences from {@link #executeOpenAIRequestWithImages}:</p>
     * <ul>
     *   <li>Uses chat/completions message format (role + content), not Responses API input items.</li>
     *   <li>Roles are sanitized via {@link MistralAdapter#sanitizeRole}.</li>
     *   <li>System instructions are prepended as a leading {@code system} message.</li>
     *   <li>Tools follow the standard {@code {type:"function",function:{...}}} format.</li>
     *   <li>{@code response_format} carries the JSON schema (no Responses {@code text.format}).</li>
     *   <li>Magistral models get {@code prompt_mode: "reasoning"} via {@link MistralAdapter}.</li>
     * </ul>
     */
    private CompletableFuture<ParsedResponse> executeMistralRequest(
            Agent agent, List<Message> messagesWithUser, Instance instance) {
        return executeChatCompletionsCompatRequest(
                agent, messagesWithUser, instance,
                MistralAdapter::buildRequestBody,
                json -> extractChatCompletionsContentParsed(json, instance));
    }

    /**
     * Executes a Chat Completions stateless request via any OpenAI-compatible
     * provider (Mistral, Grok, DeepSeek, Gemini). Differences between providers
     * are encapsulated by the {@code bodyBuilder} function and the
     * {@code responseParser} for response-side specifics (e.g., DeepSeek's
     * {@code reasoning_content} extraction).
     */
    private CompletableFuture<ParsedResponse> executeChatCompletionsCompatRequest(
            Agent agent,
            List<Message> messagesWithUser,
            Instance instance,
            BodyBuilder bodyBuilder,
            java.util.function.Function<String, ParsedResponse> responseParser) {

        List<Map<String, Object>> messages = new ArrayList<>();
        if (agent.getInstructions() != null && !agent.getInstructions().isEmpty()) {
            Map<String, Object> sys = new HashMap<>();
            sys.put("role", "system");
            sys.put("content", agent.getInstructions());
            messages.add(sys);
        }
        for (Message msg : messagesWithUser) {
            messages.addAll(buildChatCompletionsMessages(msg));
        }
        List<Map<String, Object>> tools = buildChatCompletionsTools(agent);
        Map<String, Object> responseFormat = buildChatCompletionsResponseFormat(agent);

        Map<String, Object> body = bodyBuilder.build(agent, messages, tools, responseFormat);

        return httpHelper.postRaw(
                instance,
                ProviderConfig.Endpoint.CHAT_COMPLETIONS,
                agent.getModel(),
                body,
                agent.getResponseTimeout())
                .thenApply(responseParser);
    }

    /**
     * Functional interface used by {@link #executeChatCompletionsCompatRequest} to delegate
     * provider-specific body construction to a static adapter method (e.g.
     * {@link MistralAdapter#buildRequestBody}).
     */
    @FunctionalInterface
    private interface BodyBuilder {
        Map<String, Object> build(Agent agent,
                                  List<Map<String, Object>> messages,
                                  List<Map<String, Object>> tools,
                                  Map<String, Object> responseFormat);
    }

    private CompletableFuture<ParsedResponse> executeGrokRequest(
            Agent agent, List<Message> messagesWithUser, Instance instance) {
        return executeChatCompletionsCompatRequest(
                agent, messagesWithUser, instance,
                GrokAdapter::buildRequestBody,
                json -> extractChatCompletionsContentParsed(json, instance));
    }

    private CompletableFuture<ParsedResponse> executeDeepSeekRequest(
            Agent agent, List<Message> messagesWithUser, Instance instance) {
        return executeChatCompletionsCompatRequest(
                agent, messagesWithUser, instance,
                DeepSeekAdapter::buildRequestBody,
                json -> extractChatCompletionsContentWithReasoning(json, instance));
    }

    private CompletableFuture<ParsedResponse> executeGeminiRequest(
            Agent agent, List<Message> messagesWithUser, Instance instance) {
        return executeChatCompletionsCompatRequest(
                agent, messagesWithUser, instance,
                GeminiAdapter::buildRequestBody,
                json -> extractChatCompletionsContentParsed(json, instance));
    }

    /**
     * Like {@link #extractChatCompletionsContentParsed} but also extracts DeepSeek's
     * non-standard {@code reasoning_content} field and prepends it to the parsed text
     * as a section, so callers see the chain-of-thought.
     *
     * <p>If {@code reasoning_content} is missing, the behavior matches
     * {@link #extractChatCompletionsContentParsed} exactly.</p>
     *
     * <p>The chain-of-thought is wrapped in {@code [REASONING]\n...\n[/REASONING]\n\n}
     * markers so callers who only want the final answer can split on the closing tag.
     * The reasoning is therefore visible by default but trivially strippable.</p>
     */
    @SuppressWarnings("unchecked")
    ParsedResponse extractChatCompletionsContentWithReasoning(String jsonResponse, Instance instance) {
        ParsedResponse base = extractChatCompletionsContentParsed(jsonResponse, instance);

        // Try to recover reasoning_content from the assistant message.
        String reasoning = null;
        try {
            Map<String, Object> response = objectMapper.readValue(jsonResponse,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            Object choices = response.get("choices");
            if (choices instanceof List && !((List<?>) choices).isEmpty()) {
                Object first = ((List<?>) choices).get(0);
                if (first instanceof Map) {
                    Object msg = ((Map<?, ?>) first).get("message");
                    if (msg instanceof Map) {
                        Map<String, Object> messageMap = (Map<String, Object>) msg;
                        reasoning = DeepSeekAdapter.extractReasoningContent(messageMap);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to extract DeepSeek reasoning_content: {}", e.getMessage());
        }

        if (reasoning == null || reasoning.isEmpty()) {
            return base;
        }

        String existing = base.getTextContent() == null ? "" : base.getTextContent();
        String combined = "[REASONING]\n" + reasoning + "\n[/REASONING]\n\n" + existing;

        return ParsedResponse.of(combined, base.getFunctionCalls(), base.getTokenUsage());
    }

    /**
     * Builds chat-completions-format messages from a single Message, mirroring the OpenAI
     * Chat Completions wire shape used by Mistral / Grok / DeepSeek / etc.
     *
     * <ul>
     *   <li>tool result → {@code {role:"tool", tool_call_id, content}}</li>
     *   <li>assistant with tool calls → {@code {role:"assistant", content, tool_calls:[...]}}</li>
     *   <li>multimodal user → content array with {@code text} + {@code image_url} parts</li>
     *   <li>plain text → {@code {role, content}}</li>
     * </ul>
     */
    private List<Map<String, Object>> buildChatCompletionsMessages(Message msg) {
        List<Map<String, Object>> out = new ArrayList<>();

        if (msg.isToolResult()) {
            Map<String, Object> m = new HashMap<>();
            m.put("role", "tool");
            m.put("tool_call_id", msg.getToolCallId());
            m.put("content", msg.getContent() == null ? "" : msg.getContent());
            out.add(m);
            return out;
        }

        if (msg.isAssistant() && msg.hasToolCalls()) {
            Map<String, Object> m = new HashMap<>();
            m.put("role", "assistant");
            m.put("content", msg.getContent() == null ? "" : msg.getContent());
            List<Map<String, Object>> calls = new ArrayList<>();
            for (FunctionCall fc : msg.getFunctionCalls()) {
                Map<String, Object> call = new HashMap<>();
                call.put("id", fc.getId());
                call.put("type", "function");
                Map<String, Object> fn = new HashMap<>();
                fn.put("name", fc.getName());
                fn.put("arguments", fc.getArguments() == null ? "{}" : fc.getArguments());
                call.put("function", fn);
                calls.add(call);
            }
            m.put("tool_calls", calls);
            out.add(m);
            return out;
        }

        Map<String, Object> m = new HashMap<>();
        m.put("role", MistralAdapter.sanitizeRole(msg.getRole()));

        if (msg.isMultimodal()) {
            List<Map<String, Object>> contentParts = new ArrayList<>();
            for (Message.ContentPart part : msg.getContentParts()) {
                Map<String, Object> p = new HashMap<>();
                switch (part.getType()) {
                    case "text":
                        p.put("type", "text");
                        p.put("text", part.getText());
                        break;
                    case "image_url":
                        p.put("type", "image_url");
                        Map<String, Object> imgU = new HashMap<>();
                        imgU.put("url", part.getImageUrl());
                        p.put("image_url", imgU);
                        break;
                    case "image_base64":
                        p.put("type", "image_url");
                        Map<String, Object> imgB = new HashMap<>();
                        String dataUrl = "data:" + part.getMediaType() + ";base64," + part.getImageBase64();
                        imgB.put("url", dataUrl);
                        p.put("image_url", imgB);
                        break;
                    default:
                        logger.warn("Unknown content part type for chat/completions: {}", part.getType());
                        continue;
                }
                contentParts.add(p);
            }
            m.put("content", contentParts);
        } else {
            m.put("content", msg.getContent() == null ? "" : msg.getContent());
        }

        out.add(m);
        return out;
    }

    /**
     * Builds chat-completions-format tools list from the agent's function definitions.
     * Returns null if the agent has no functions configured. Web search and code interpreter
     * are intentionally NOT included — providers using this format (Mistral, Grok, ...) do
     * not implement them as native tools.
     */
    private List<Map<String, Object>> buildChatCompletionsTools(Agent agent) {
        if (agent.getFunctions() == null || agent.getFunctions().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> tools = new ArrayList<>();
        String parameterClassPackage = config.getFunctionParameterClassPackage();
        for (var func : agent.getFunctions()) {
            if (!ToolBuilder.isFunctionEnabledForAgent(func, agent)) {
                continue;
            }
            Map<String, Object> tool = new HashMap<>();
            tool.put("type", "function");
            Map<String, Object> fn = new HashMap<>();
            fn.put("name", func.getName());
            if (func.getDescription() != null) {
                fn.put("description", func.getDescription());
            }
            fn.put("parameters", ToolBuilder.buildFunctionSchema(func, parameterClassPackage));
            tool.put("function", fn);
            tools.add(tool);
        }
        return tools.isEmpty() ? null : tools;
    }

    /**
     * Builds {@code response_format} for chat-completions when the agent declares a result class.
     * Returns null if structured output is not requested or the class can't be resolved.
     */
    private Map<String, Object> buildChatCompletionsResponseFormat(Agent agent) {
        if (agent.getResultClass() == null || agent.getResultClass().isEmpty()) {
            return null;
        }
        String fullClassName = config.resolveResultClassName(agent.getResultClass());
        if (fullClassName == null) {
            logger.warn("Cannot resolve result class '{}' for chat-completions response_format",
                    agent.getResultClass());
            return null;
        }
        try {
            Class<?> resultClass = Class.forName(fullClassName);
            Map<String, Object> schema = JsonSchemaGenerator.createFunctionSchemaFromClass(resultClass);

            Map<String, Object> jsonSchema = new HashMap<>();
            jsonSchema.put("name", resultClass.getSimpleName().toLowerCase() + "_response");
            jsonSchema.put("schema", schema);
            jsonSchema.put("strict", true);

            Map<String, Object> rf = new HashMap<>();
            rf.put("type", "json_schema");
            rf.put("json_schema", jsonSchema);
            return rf;
        } catch (ClassNotFoundException e) {
            logger.warn("Result class not found for response_format: {}", agent.getResultClass());
            return null;
        }
    }

    /**
     * Extracts content and function calls from an OpenAI-compatible chat/completions JSON response
     * (used by Mistral, Grok, DeepSeek, Together, Ollama, ...).
     *
     * <p>Detects {@code finish_reason == "length"} and throws
     * {@link AgentException.ErrorCode#MAX_TOKENS_EXCEEDED} for parity with Claude/OpenAI Responses.</p>
     */
    @SuppressWarnings("unchecked")
    /**
     * Computes pricing for a model on a given instance, consulting
     * {@link CustomProviderSpec#getModelPricing()} as a fallback when the
     * instance is a {@link Provider#CUSTOM} one. Built-in providers fall back
     * to the static {@link ModelPricing} table only.
     */
    private TokenUsage calculatePricing(String model, Integer in, Integer out, Instance instance) {
        return calculatePricing(model, in, out, null, null, instance);
    }

    /**
     * Cache-aware variant of {@link #calculatePricing(String, Integer, Integer, Instance)}.
     * Pass {@code null} for {@code cacheCreate} / {@code cacheRead} when the provider does
     * not report cache statistics — the pricing layer then prices cache tokens at zero
     * (no double-counting against input).
     */
    private TokenUsage calculatePricing(String model, Integer in, Integer out,
            Integer cacheCreate, Integer cacheRead, Instance instance) {
        Map<String, ModelPricing.PriceEntry> fallback = null;
        if (instance != null && instance.getProvider() == Provider.CUSTOM
                && instance.getCustomSpec() != null) {
            fallback = instance.getCustomSpec().getModelPricing();
        }
        return ModelPricing.calculate(model, in, out, cacheCreate, cacheRead, fallback);
    }

    private ParsedResponse extractChatCompletionsContentParsed(String jsonResponse, Instance instance) {
        try {
            Map<String, Object> response = objectMapper.readValue(jsonResponse,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });

            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                        "No choices in chat completions response");
            }
            Map<String, Object> choice0 = choices.get(0);
            String finishReason = (String) choice0.get("finish_reason");
            if ("length".equals(finishReason)) {
                throw new AgentException(AgentException.ErrorCode.MAX_TOKENS_EXCEEDED,
                        "Chat completions response truncated (finish_reason=length). "
                                + "Consider increasing maxTokens or reducing prompt size.");
            }

            Map<String, Object> message = (Map<String, Object>) choice0.get("message");
            String text = null;
            List<FunctionCall> functionCalls = new ArrayList<>();
            if (message != null) {
                Object contentObj = message.get("content");
                if (contentObj instanceof String) {
                    text = (String) contentObj;
                } else if (contentObj instanceof List) {
                    StringBuilder sb = new StringBuilder();
                    for (Object part : (List<?>) contentObj) {
                        if (part instanceof Map) {
                            Object t = ((Map<?, ?>) part).get("text");
                            if (t != null) sb.append(t);
                        }
                    }
                    text = sb.length() > 0 ? sb.toString() : null;
                }

                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
                if (toolCalls != null) {
                    for (Map<String, Object> tc : toolCalls) {
                        String id = (String) tc.get("id");
                        Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                        String name = fn != null ? (String) fn.get("name") : null;
                        Object args = fn != null ? fn.get("arguments") : null;
                        String argsStr;
                        if (args == null) {
                            argsStr = "{}";
                        } else if (args instanceof String) {
                            argsStr = (String) args;
                        } else {
                            try {
                                argsStr = objectMapper.writeValueAsString(args);
                            } catch (Exception ex) {
                                argsStr = args.toString();
                            }
                        }
                        functionCalls.add(FunctionCall.builder()
                                .id(id)
                                .name(name)
                                .arguments(argsStr)
                                .build());
                    }
                }
            }

            TokenUsage tokenUsage = null;
            Map<String, Object> usageMap = (Map<String, Object>) response.get("usage");
            if (usageMap != null) {
                Integer promptTokens = usageMap.get("prompt_tokens") instanceof Number
                        ? ((Number) usageMap.get("prompt_tokens")).intValue() : null;
                Integer completionTokens = usageMap.get("completion_tokens") instanceof Number
                        ? ((Number) usageMap.get("completion_tokens")).intValue() : null;
                // OpenAI-compat providers: prompt_tokens INCLUDES cached tokens. Subtract
                // to get the uncached portion that should be priced at the input rate;
                // cached_tokens are then priced at the (much cheaper) cache-read rate.
                Integer cached = null;
                Object details = usageMap.get("prompt_tokens_details");
                if (details instanceof Map) {
                    Object c = ((Map<?, ?>) details).get("cached_tokens");
                    if (c instanceof Number) {
                        cached = ((Number) c).intValue();
                    }
                }
                Integer uncached = promptTokens;
                if (promptTokens != null && cached != null) {
                    uncached = Math.max(0, promptTokens - cached);
                }
                String model = (String) response.get("model");
                tokenUsage = calculatePricing(model, uncached, completionTokens, null, cached, instance);
            }

            if (!functionCalls.isEmpty()) {
                return ParsedResponse.of(text, functionCalls, tokenUsage);
            }
            return ParsedResponse.ofText(text == null ? "" : text, tokenUsage);

        } catch (Exception e) {
            if (e instanceof AgentException) {
                throw (AgentException) e;
            }
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Failed to parse chat completions response: " + e.getMessage(), e);
        }
    }

    // ==================== CUSTOM PROVIDER ====================

    /**
     * Executes a request to a {@link Provider#CUSTOM} instance, dispatching by
     * {@link CustomProviderSpec#getApiFormat()}. Validates required features against
     * the spec first (may throw {@link io.github.yannfavinleveque.agentic.agent.exception.UnsupportedFeatureException}
     * in {@link io.github.yannfavinleveque.agentic.agent.custom.LenientMode#THROW} mode).
     */
    private CompletableFuture<ParsedResponse> executeCustomRequest(
            Agent agent, List<Message> messagesWithUser, Instance instance) {

        CustomProviderSpec spec = instance.getCustomSpec();
        if (spec == null) {
            return CompletableFuture.failedFuture(new AgentException(
                    AgentException.ErrorCode.INVALID_CONFIGURATION,
                    "Instance " + instance.getId() + " is provider=CUSTOM but has no custom spec"));
        }

        // 1. Validate features required by the agent against the spec.
        // The returned set is the *sanitized* feature set that body builders should honor:
        //  - THROW: the call above already threw, we never reach the body builders.
        //  - WARN : the unsupported feature has been logged AND removed from `allowed`
        //           => the body builders must not emit it.
        //  - IGNORE: same drop, no log.
        EnumSet<Feature> allowed;
        EnumSet<Feature> requested = collectRequestedFeatures(agent);
        try {
            allowed = FeatureValidator.validate(instance.getId(), spec, requested);
        } catch (RuntimeException re) {
            return CompletableFuture.failedFuture(re);
        }

        String fmt = spec.getApiFormat() == null ? "" : spec.getApiFormat().toLowerCase();
        switch (fmt) {
            case "openai-chat":
                return executeCustomOpenAIChatRequest(agent, messagesWithUser, instance, spec, allowed);
            case "openai-responses":
                // TODO(v1.22): implement openai-responses for CUSTOM. Most non-OpenAI providers
                // do not expose Responses API anyway, so this is intentionally deferred.
                // INVALID_CONFIGURATION code -> not retried by the network-retry path.
                return CompletableFuture.failedFuture(new AgentException(
                        AgentException.ErrorCode.INVALID_CONFIGURATION,
                        "apiFormat=openai-responses is not yet implemented for Provider.CUSTOM "
                                + "(instance " + instance.getId() + "). "
                                + "Use apiFormat=openai-chat instead, or open an issue."));
            case "anthropic-messages":
                // TODO(v1.22): implement anthropic-messages for CUSTOM by reusing ClaudeAdapter
                // with a custom URL/headers builder. Deferred to keep this release focused.
                // INVALID_CONFIGURATION code -> not retried by the network-retry path.
                return CompletableFuture.failedFuture(new AgentException(
                        AgentException.ErrorCode.INVALID_CONFIGURATION,
                        "apiFormat=anthropic-messages is not yet implemented for Provider.CUSTOM "
                                + "(instance " + instance.getId() + "). "
                                + "Use Provider.ANTHROPIC or Provider.AZURE_ANTHROPIC for native Claude routing, "
                                + "or open an issue."));
            default:
                return CompletableFuture.failedFuture(new AgentException(
                        AgentException.ErrorCode.INVALID_CONFIGURATION,
                        "Unknown apiFormat: " + spec.getApiFormat() + " for instance " + instance.getId()));
        }
    }

    /**
     * Implements {@code apiFormat=openai-chat} for {@link Provider#CUSTOM}. Builds the OpenAI
     * Chat Completions wire format (same as Mistral) but reads endpoint path, auth header, query
     * params and extra headers from the spec instead of {@link ProviderConfig}.
     *
     * <p>Covers Mistral (when wired manually), Grok (xAI), DeepSeek, Together, Groq, Ollama,
     * and any other OpenAI-compatible chat/completions endpoint.</p>
     *
     * <p>The {@code allowed} set is the sanitized feature set returned by {@link FeatureValidator}:
     * any feature the agent requested but the provider does not declare as supported has already
     * been removed (and logged in {@code WARN} mode). Body builders below MUST gate inclusion of
     * tools, response_format and reasoning_effort on this set so that {@code WARN}/{@code IGNORE}
     * actually strip the unsupported feature from the outgoing HTTP body — not just from the log.</p>
     */
    private CompletableFuture<ParsedResponse> executeCustomOpenAIChatRequest(
            Agent agent, List<Message> messagesWithUser, Instance instance, CustomProviderSpec spec,
            EnumSet<Feature> allowed) {

        // 1. Build messages (same shape as Mistral / OpenAI Chat Completions)
        List<Map<String, Object>> messages = new ArrayList<>();
        if (agent.getInstructions() != null && !agent.getInstructions().isEmpty()) {
            Map<String, Object> sys = new HashMap<>();
            sys.put("role", "system");
            sys.put("content", agent.getInstructions());
            messages.add(sys);
        }
        for (Message msg : messagesWithUser) {
            messages.addAll(buildChatCompletionsMessages(msg));
        }

        // 2. Build body
        Map<String, Object> body = new HashMap<>();
        body.put("model", agent.getModel());
        body.put("messages", messages);
        if (agent.getTemperature() != null) {
            body.put("temperature", agent.getTemperature());
        }
        body.put("max_tokens", agent.getMaxTokens() != null ? agent.getMaxTokens() : 32768);

        // FUNCTION_CALLING -> "tools" : only emit when the validator left it in `allowed`.
        if (allowed != null && allowed.contains(Feature.FUNCTION_CALLING)) {
            List<Map<String, Object>> tools = buildChatCompletionsTools(agent);
            if (tools != null && !tools.isEmpty()) {
                body.put("tools", tools);
            }
        }
        // STRUCTURED_OUTPUT -> "response_format" : same gating.
        if (allowed != null && allowed.contains(Feature.STRUCTURED_OUTPUT)) {
            Map<String, Object> rf = buildChatCompletionsResponseFormat(agent);
            if (rf != null) {
                body.put("response_format", rf);
            }
        }
        // REASONING -> "reasoning_effort" : only emit when allowed AND the agent declared it.
        // The custom executor does not (yet) emit Mistral-Magistral's "prompt_mode"; that path
        // lives in MistralAdapter and is exercised by Provider.MISTRAL, not Provider.CUSTOM.
        if (allowed != null && allowed.contains(Feature.REASONING)
                && agent.getReasoningEffort() != null
                && !agent.getReasoningEffort().isBlank()
                && !"none".equalsIgnoreCase(agent.getReasoningEffort())) {
            body.put("reasoning_effort", agent.getReasoningEffort());
        }

        // 3. Resolve URL: baseUrl + spec endpoint path + spec query params
        String endpointPath = spec.getEndpointPath("chat_completions");
        if (endpointPath == null || endpointPath.isEmpty()) {
            return CompletableFuture.failedFuture(new AgentException(
                    AgentException.ErrorCode.INVALID_CONFIGURATION,
                    "Custom provider '" + instance.getId() + "' does not declare endpoints.chat_completions"));
        }
        String fullUrl = buildCustomUrl(instance.getBaseUrl(), endpointPath, spec.getQueryParamsView());

        // 4. Build headers: auth + extras
        Map<String, String> headers = new HashMap<>();
        if (spec.getAuth() != null && spec.getAuth().getHeader() != null) {
            headers.put(spec.getAuth().getHeader(),
                    spec.getAuth().renderValue(instance.getApiKey()));
        }
        for (Map.Entry<String, String> h : spec.getExtraHeadersView().entrySet()) {
            headers.put(h.getKey(), h.getValue());
        }

        long timeoutMs = agent.getResponseTimeout() != null ? agent.getResponseTimeout() : 900_000L;

        return httpHelper.postRawCustom(fullUrl, headers, body, timeoutMs)
                .thenApply(json -> extractChatCompletionsContentParsed(json, instance));
    }

    /** Returns the EnumSet of {@link Feature}s the agent's configuration requires. */
    private EnumSet<Feature> collectRequestedFeatures(Agent agent) {
        EnumSet<Feature> set = EnumSet.noneOf(Feature.class);
        if (Boolean.TRUE.equals(agent.getWebSearch())) {
            set.add(Feature.WEB_SEARCH);
        }
        if (Boolean.TRUE.equals(agent.getCodeInterpreter())) {
            set.add(Feature.CODE_INTERPRETER);
        }
        if (agent.getResultClass() != null && !agent.getResultClass().isEmpty()) {
            set.add(Feature.STRUCTURED_OUTPUT);
        }
        if (agent.getFunctions() != null && !agent.getFunctions().isEmpty()) {
            set.add(Feature.FUNCTION_CALLING);
        }
        if (agent.getReasoningEffort() != null && !agent.getReasoningEffort().isBlank()
                && !"none".equalsIgnoreCase(agent.getReasoningEffort())) {
            set.add(Feature.REASONING);
        }
        // VISION is request-time, not agent-time -> handled at the per-request level
        // by executeXxxWithImages variants. For now, do not add it here.
        return set;
    }

    /**
     * Concatenates a base URL with a custom-spec endpoint path and appends query parameters
     * (URL-encoded as in {@link ProviderConfig#buildUrl}).
     */
    private String buildCustomUrl(String baseUrl, String path, Map<String, String> queryParams) {
        String b = baseUrl == null ? "" : baseUrl;
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        StringBuilder sb = new StringBuilder(b).append(path);
        if (queryParams != null && !queryParams.isEmpty()) {
            sb.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : queryParams.entrySet()) {
                if (!first) sb.append('&');
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
        }
        return sb.toString();
    }

    /**
     * Parses a Claude response into a ParsedResponse with text and function calls.
     * Detects max_tokens stop reason and throws MAX_TOKENS_EXCEEDED before deserialization.
     */
    private ParsedResponse parseClaudeResponse(ClaudeResponse response, Instance instance) {
        if (response == null || response.getContent() == null) {
            return ParsedResponse.ofText("");
        }

        // Detect truncated output before attempting deserialization
        if ("max_tokens".equals(response.getStopReason())) {
            throw new AgentException(AgentException.ErrorCode.MAX_TOKENS_EXCEEDED,
                    "Claude response truncated (stop_reason=max_tokens). "
                            + "Consider increasing maxTokens or reducing prompt size.");
        }

        StringBuilder textContent = new StringBuilder();
        List<FunctionCall> functionCalls = new ArrayList<>();

        for (ClaudeResponse.Content content : response.getContent()) {
            if ("text".equals(content.getType())) {
                if (content.getText() != null) {
                    textContent.append(content.getText());
                }
            } else if ("tool_use".equals(content.getType())) {
                // Claude returns tool_use blocks for function calls
                // Input is already parsed as Map/Object by Jackson, need to serialize back to JSON
                String argsJson = "{}";
                if (content.getInput() != null) {
                    try {
                        argsJson = objectMapper.writeValueAsString(content.getInput());
                    } catch (Exception e) {
                        logger.warn("Failed to serialize Claude tool_use input: {}", e.getMessage());
                        argsJson = content.getInput().toString();
                    }
                }
                FunctionCall call = FunctionCall.builder()
                        .id(content.getId())
                        .name(content.getName())
                        .arguments(argsJson)
                        .build();
                functionCalls.add(call);
                logger.debug("Parsed Claude tool_use: {} with input: {}", content.getName(), argsJson);
            } else if ("thinking".equals(content.getType())) {
                // Claude extended thinking block — skip for output, just log
                logger.debug("Claude response contains thinking block (skipped for output)");
            }
        }

        // Extract token usage and estimated cost — Anthropic surfaces uncached input,
        // cache writes, and cache reads in three separate fields. Forward all three so
        // the pricing layer can apply the discounted cache-read rate (~0.10x input)
        // and the cache-write premium (~1.25x input).
        TokenUsage tokenUsage = null;
        if (response.getUsage() != null) {
            tokenUsage = calculatePricing(
                    response.getModel(),
                    response.getUsage().getInputTokens(),
                    response.getUsage().getOutputTokens(),
                    response.getUsage().getCacheCreationInputTokens(),
                    response.getUsage().getCacheReadInputTokens(),
                    instance);
        }

        if (!functionCalls.isEmpty()) {
            String text = textContent.length() > 0 ? textContent.toString() : null;
            return ParsedResponse.of(text, functionCalls, tokenUsage);
        }

        return ParsedResponse.ofText(textContent.toString(), tokenUsage);
    }

    // ==================== REQUEST MODEL (DIRECT MODEL CALLS) ====================

    /**
     * Sends a direct request to a model (without agent configuration).
     * <p>
     * Use this method when you want to call a model directly without registering an agent.
     * Supports structured output, web search, code interpreter, vision, and conversation history.
     * All options are configured via {@link ModelRequestOptions}.
     * </p>
     *
     * <p>
     * Example:
     * </p>
     *
     * <pre>{@code
     * // Simple text request
     * AgentResult result = agentService.requestModel("gpt-4o", "Hello!").join();
     *
     * // With structured output
     * ModelRequestOptions options = ModelRequestOptions.builder()
     *         .resultClass(MyResult.class)
     *         .build();
     * MyResult result = (MyResult) agentService.requestModel("gpt-4o", "Extract data", options).join();
     *
     * // With web search
     * ModelRequestOptions options = ModelRequestOptions.withWebSearch();
     * AgentResult result = agentService.requestModel("gpt-4o", "What's the latest news?", options).join();
     *
     * // With vision (single image)
     * ModelRequestOptions options = ModelRequestOptions.builder()
     *         .image(imageBase64)
     *         .build();
     * AgentResult result = agentService.requestModel("gpt-4o", "What's in this image?", options).join();
     *
     * // With vision (multiple images) and history
     * ModelRequestOptions options = ModelRequestOptions.builder()
     *         .images(List.of(img1, img2))
     *         .history(previousMessages)
     *         .build();
     * AgentResult result = agentService.requestModel("gpt-4o", "Compare these images", options).join();
     * }</pre>
     *
     * @param model       Model name (e.g., "gpt-4o", "claude-sonnet-4-5")
     * @param userMessage Current user message
     * @param options     Request options (can be null for defaults)
     * @return CompletableFuture with the model's response
     */
    public CompletableFuture<AgentResult> requestModel(String model, String userMessage, ModelRequestOptions options) {
        // Extract images from options
        List<String> images = null;
        List<Message> history = null;

        if (options != null) {
            history = options.getHistory();
            if (options.getImage() != null) {
                images = List.of(options.getImage());
            } else if (options.getImages() != null && !options.getImages().isEmpty()) {
                images = options.getImages();
            }
        }

        // If we have images, build multimodal message
        if (images != null && !images.isEmpty()) {
            List<Message.ContentPart> contentParts = new ArrayList<>();
            contentParts.add(Message.ContentPart.text(userMessage));
            for (String imageBase64 : images) {
                contentParts.add(Message.ContentPart.pngBase64(imageBase64));
            }

            Message userMsg = Message.builder()
                    .role("user")
                    .content(contentParts)
                    .build();

            List<Message> fullHistory = new ArrayList<>();
            if (history != null) {
                fullHistory.addAll(history);
            }
            fullHistory.add(userMsg);

            return requestModelInternal(model, fullHistory, options);
        }

        // No images - build simple text message
        Message userMsg = Message.builder()
                .role("user")
                .content(userMessage)
                .build();

        List<Message> fullHistory = new ArrayList<>();
        if (history != null) {
            fullHistory.addAll(history);
        }
        fullHistory.add(userMsg);

        return requestModelInternal(model, fullHistory, options);
    }

    /**
     * Sends a direct request to a model with default options.
     *
     * @param model       Model name
     * @param userMessage User message
     * @return CompletableFuture with the model's response
     */
    public CompletableFuture<AgentResult> requestModel(String model, String userMessage) {
        return requestModel(model, userMessage, null);
    }

    /**
     * Internal method for requestModel with pre-built history (including current user message).
     */
    private CompletableFuture<AgentResult> requestModelInternal(String model, List<Message> messagesWithUser,
            ModelRequestOptions options) {
        Agent.AgentBuilder agentBuilder = Agent.builder()
                .id("__direct_" + model + "__")
                .name("Direct " + model)
                .model(model);

        if (options != null) {
            if (options.getInstructions() != null) {
                agentBuilder.instructions(options.getInstructions());
            }
            if (options.getTemperature() != null) {
                agentBuilder.temperature(options.getTemperature());
            }
            if (options.getMaxTokens() != null) {
                agentBuilder.maxTokens(options.getMaxTokens());
            }
            agentBuilder.webSearch(options.isWebSearch());
            agentBuilder.codeInterpreter(options.isCodeInterpreter());

            if (options.getResultClass() != null) {
                agentBuilder.resultClass(options.getResultClass().getSimpleName());
            }
        }

        Agent tempAgent = agentBuilder.build();
        return attemptRequestModelInternalWithRetry(tempAgent, messagesWithUser, options, 0);
    }

    private CompletableFuture<AgentResult> attemptRequestModelInternalWithRetry(
            Agent tempAgent, List<Message> messagesWithUser, ModelRequestOptions options, int attempt) {

        return executeRequestModelInternal(tempAgent, messagesWithUser, options, attempt)
                .thenCompose(parsed -> deserializeModelResponse(parsed, options))
                .handle((result, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(result);
                    }

                    Throwable cause = unwrapException(error);
                    int maxRetries = getMaxRetriesForError(cause, tempAgent);

                    if (maxRetries < 0 || attempt >= maxRetries) {
                        return CompletableFuture.<AgentResult>failedFuture(cause);
                    }

                    long delay = calculateDelay(cause, attempt);
                    logger.warn("Request failed (attempt {}/{}), retrying in {}ms: {} [{}]",
                            attempt + 1, maxRetries, delay, cause.getMessage(),
                            cause instanceof AgentException ? ((AgentException) cause).getErrorCode() : "UNKNOWN");

                    return delayAsync(delay)
                            .thenCompose(v -> attemptRequestModelInternalWithRetry(tempAgent, messagesWithUser, options, attempt + 1));
                })
                .thenCompose(f -> f);
    }

    private CompletableFuture<ParsedResponse> executeRequestModelInternal(
            Agent tempAgent, List<Message> messagesWithUser, ModelRequestOptions options, int attempt) {

        if (instanceRouter.isDegradedMode()) {
            return CompletableFuture.failedFuture(
                    new AgentException(AgentException.ErrorCode.NO_INSTANCE_AVAILABLE, "No instances configured"));
        }

        int instanceIdx = instanceRouter.getNextInstanceForModel(tempAgent.getModel(), tempAgent.getInstances());
        Instance instance = instanceRouter.getInstance(instanceIdx);
        InstanceLimiter limiter = getLimiterForInstanceAndModel(instance, tempAgent.getModel());

        // Acquire concurrent stream permit (non-blocking queue-based)
        return limiter.acquireAsync().thenCompose(v -> {
            long rateDelay = limiter.acquireRateSlot();
            if (rateDelay > 0) {
                return delayAsync(rateDelay)
                        .thenCompose(v2 -> executeRequestModelInternalAfterPermit(tempAgent, messagesWithUser, options, instance, limiter));
            }
            return executeRequestModelInternalAfterPermit(tempAgent, messagesWithUser, options, instance, limiter);
        });
    }

    private CompletableFuture<ParsedResponse> executeRequestModelInternalAfterPermit(
            Agent tempAgent, List<Message> messagesWithUser, ModelRequestOptions options,
            Instance instance, InstanceLimiter limiter) {

        logger.info("-> REQUEST MODEL | Model: {} | Instance: {} | Messages: {}",
                tempAgent.getModel(), instance.getId(), messagesWithUser.size());

        CompletableFuture<ParsedResponse> requestFuture;

        // Routing order: custom > anthropic > mistral > grok > deepseek > gemini > openai
        if (instance.getProvider() == Provider.CUSTOM) {
            requestFuture = executeCustomRequest(tempAgent, messagesWithUser, instance);
        } else if (ProviderConfig.isAnthropicModel(tempAgent.getModel())) {
            requestFuture = executeClaudeRequestModelInternal(tempAgent, messagesWithUser, options, instance);
        } else if (MistralAdapter.isMistralModel(tempAgent.getModel())
                || instance.getProvider() == Provider.MISTRAL
                || instance.getProvider() == Provider.AZURE_MISTRAL) {
            requestFuture = executeMistralRequest(tempAgent, messagesWithUser, instance);
        } else if (GrokAdapter.isGrokModel(tempAgent.getModel())
                || instance.getProvider() == Provider.GROK
                || instance.getProvider() == Provider.AZURE_GROK) {
            requestFuture = executeGrokRequest(tempAgent, messagesWithUser, instance);
        } else if (DeepSeekAdapter.isDeepSeekModel(tempAgent.getModel())
                || instance.getProvider() == Provider.DEEPSEEK) {
            requestFuture = executeDeepSeekRequest(tempAgent, messagesWithUser, instance);
        } else if (GeminiAdapter.isGeminiModel(tempAgent.getModel())
                || instance.getProvider() == Provider.GEMINI) {
            requestFuture = executeGeminiRequest(tempAgent, messagesWithUser, instance);
        } else {
            requestFuture = executeOpenAIRequestModelInternal(tempAgent, messagesWithUser, options, instance);
        }

        return requestFuture
                .whenComplete((response, error) -> {
                    limiter.release();
                    if (error == null) {
                        String usageLog = response.getTokenUsage() != null
                                ? " | " + ModelPricing.formatForLog(response.getTokenUsage()) : "";
                        logger.info("<- RESPONSE MODEL | Model: {}{} | Response: {}",
                                tempAgent.getModel(), usageLog, response);
                    } else {
                        logger.error("<- ERROR MODEL | Model: {} | Error: {}",
                                tempAgent.getModel(), error.getMessage());
                    }
                });
    }

    private CompletableFuture<ParsedResponse> executeOpenAIRequestModelInternal(
            Agent tempAgent, List<Message> messagesWithUser, ModelRequestOptions options, Instance instance) {

        List<Map<String, Object>> input = new ArrayList<>();

        for (Message msg : messagesWithUser) {
            input.addAll(buildOpenAIInputItems(msg));
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", tempAgent.getModel());
        requestBody.put("input", input);

        if (options != null && options.getInstructions() != null) {
            requestBody.put("instructions", options.getInstructions());
        }

        if (options != null && options.getTemperature() != null) {
            requestBody.put("temperature", options.getTemperature());
        }

        Integer maxTokens = (options != null && options.getMaxTokens() != null) ? options.getMaxTokens() : 32768;
        requestBody.put("max_output_tokens", maxTokens);

        // Reasoning configuration
        addOpenAIReasoningConfig(requestBody, options != null ? options.getReasoningEffort() : null);

        List<Object> tools = buildToolsForRequestModel(options);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
        }

        if (options != null) {
            Map<String, Object> textFormat = buildStructuredOutputFormat(options);
            if (textFormat != null) {
                Map<String, Object> text = new HashMap<>();
                text.put("format", textFormat);
                requestBody.put("text", text);
            }
        }

        return httpHelper.postRaw(
                instance,
                ProviderConfig.Endpoint.RESPONSES,
                tempAgent.getModel(),
                requestBody,
                tempAgent.getResponseTimeout()).thenApply(json -> extractResponsesContentParsed(json, instance));
    }

    private CompletableFuture<ParsedResponse> executeClaudeRequestModelInternal(
            Agent tempAgent, List<Message> messagesWithUser, ModelRequestOptions options, Instance instance) {

        List<ClaudeRequest.ClaudeMessage> messages = new ArrayList<>();
        for (Message msg : messagesWithUser) {
            if (!msg.isSystem()) {
                messages.add(buildClaudeMessage(msg));
            }
        }

        String systemPrompt = (options != null) ? options.getInstructions() : null;
        Double temperature = (options != null) ? options.getTemperature() : null;
        int maxTokens = (options != null && options.getMaxTokens() != null) ? options.getMaxTokens() : 32768;

        Class<?> resultClass = (options != null) ? options.getResultClass() : null;

        return claudeAdapter.callClaudeAsync(
                instance,
                tempAgent.getModel(),
                systemPrompt,
                messages,
                temperature,
                maxTokens,
                resultClass,
                null,
                options != null ? options.getReasoningEffort() : null).thenApply(resp -> parseClaudeResponse(resp, instance));
    }

    @SuppressWarnings("unchecked")
    private List<Object> buildToolsForRequestModel(ModelRequestOptions options) {
        if (options == null) {
            return null;
        }

        List<Object> tools = new ArrayList<>();

        if (options.isWebSearch()) {
            Map<String, Object> webSearchTool = new HashMap<>();
            webSearchTool.put("type", "web_search_preview");
            tools.add(webSearchTool);
        }

        if (options.isCodeInterpreter()) {
            Map<String, Object> codeInterpreterTool = new HashMap<>();
            codeInterpreterTool.put("type", "code_interpreter");
            Map<String, Object> container = new HashMap<>();
            container.put("type", "auto");
            codeInterpreterTool.put("container", container);
            tools.add(codeInterpreterTool);
        }

        return tools.isEmpty() ? null : tools;
    }

    private Map<String, Object> buildStructuredOutputFormat(ModelRequestOptions options) {
        if (options.getResultClass() != null) {
            try {
                Map<String, Object> schema = JsonSchemaGenerator.createFunctionSchemaFromClass(options.getResultClass());
                Map<String, Object> textFormat = new HashMap<>();
                textFormat.put("type", "json_schema");
                textFormat.put("name", options.getResultClass().getSimpleName().toLowerCase() + "_response");
                textFormat.put("schema", schema);
                textFormat.put("strict", true);
                return textFormat;
            } catch (Exception e) {
                logger.warn("Failed to build schema from class: {}", options.getResultClass(), e);
                return null;
            }
        }

        if (options.getResultSchema() != null) {
            Map<String, Object> textFormat = new HashMap<>();
            textFormat.put("type", "json_schema");
            textFormat.put("name", options.getSchemaName() != null ? options.getSchemaName() : "response");
            textFormat.put("schema", options.getResultSchema());
            textFormat.put("strict", true);
            return textFormat;
        }

        return null;
    }

    private CompletableFuture<AgentResult> deserializeModelResponse(ParsedResponse parsed, ModelRequestOptions options) {
        try {
            AgentResult result;

            // If there are function calls, return them in DefaultResult
            if (parsed.hasFunctionCalls()) {
                result = new DefaultResult(parsed.getTextContent(), parsed.getFunctionCalls());
            } else {
                String jsonResponse = parsed.getTextContent();

                if (options == null || options.getResultClass() == null) {
                    result = new DefaultResult(jsonResponse);
                } else {
                    result = (AgentResult) objectMapper.readValue(jsonResponse, options.getResultClass());
                }
            }

            // Propagate token usage to result
            if (parsed.getTokenUsage() != null) {
                result.setUsage(parsed.getTokenUsage());
            }

            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            return CompletableFuture.failedFuture(new AgentException(
                    AgentException.ErrorCode.DESERIALIZATION_FAILED,
                    "Failed to deserialize response: " + e.getMessage(), e));
        }
    }

    // ==================== INTERNAL FLOW ====================

    private CompletableFuture<AgentResult> attemptRequestWithRetry(
            Agent agent, List<CacheableSegment> userSegments, List<Message> history, int attempt) {

        return executeRequest(agent, userSegments, history, attempt)
                .thenCompose(parsed -> deserializeResponse(agent, parsed))
                .handle((result, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(result);
                    }

                    Throwable cause = unwrapException(error);
                    int maxRetries = getMaxRetriesForError(cause, agent);

                    if (maxRetries < 0 || attempt >= maxRetries) {
                        return CompletableFuture.<AgentResult>failedFuture(cause);
                    }

                    long delay = calculateDelay(cause, attempt);
                    logger.warn("Request failed (attempt {}/{}), retrying in {}ms: {} [{}]",
                            attempt + 1, maxRetries, delay, cause.getMessage(),
                            cause instanceof AgentException ? ((AgentException) cause).getErrorCode() : "UNKNOWN");

                    return delayAsync(delay)
                            .thenCompose(v -> attemptRequestWithRetry(agent, userSegments, history, attempt + 1));
                })
                .thenCompose(f -> f);
    }

    private CompletableFuture<ParsedResponse> executeRequest(
            Agent agent, List<CacheableSegment> userSegments, List<Message> history, int attempt) {

        if (instanceRouter.isDegradedMode()) {
            return CompletableFuture.failedFuture(
                    new AgentException(AgentException.ErrorCode.NO_INSTANCE_AVAILABLE, "No instances configured"));
        }

        int instanceIdx = instanceRouter.getNextInstanceForModel(agent.getModel(), agent.getInstances());
        Instance instance = instanceRouter.getInstance(instanceIdx);
        InstanceLimiter limiter = getLimiterForInstanceAndModel(instance, agent.getModel());

        // Acquire concurrent stream permit (non-blocking queue-based)
        return limiter.acquireAsync().thenCompose(v -> {
            long rateDelay = limiter.acquireRateSlot();
            if (rateDelay > 0) {
                return delayAsync(rateDelay)
                        .thenCompose(v2 -> executeRequestAfterPermit(agent, userSegments, history, instance, limiter));
            }
            return executeRequestAfterPermit(agent, userSegments, history, instance, limiter);
        });
    }

    private CompletableFuture<ParsedResponse> executeRequestAfterPermit(
            Agent agent, List<CacheableSegment> userSegments, List<Message> history,
            Instance instance, InstanceLimiter limiter) {

        // For non-Anthropic providers and logging, the segments collapse to a single string.
        String userMessage = (userSegments == null) ? null : ClaudeAdapter.concatSegments(userSegments);
        String msgPreview = userMessage != null && userMessage.length() > 200
                ? userMessage.substring(0, 200) + "..." : userMessage;
        logger.info("→ REQUEST START [V2] | Agent: {} | Model: {} | Instance: {} | Input: {}",
                agent.getName(), agent.getModel(), instance.getId(), msgPreview);

        CompletableFuture<ParsedResponse> requestFuture;

        // Routing order: custom > anthropic > mistral > grok > deepseek > gemini > openai
        if (instance.getProvider() == Provider.CUSTOM) {
            // Build a synthetic message list (history + current user) and route via the
            // multimodal-aware path which is fine for plain text too.
            List<Message> messagesWithUser = new ArrayList<>();
            if (history != null) {
                messagesWithUser.addAll(history);
            }
            if (userMessage != null) {
                messagesWithUser.add(Message.builder().role("user").content(userMessage).build());
            }
            requestFuture = executeCustomRequest(agent, messagesWithUser, instance);
        } else if (ProviderConfig.isAnthropicModel(agent.getModel())) {
            requestFuture = executeClaudeRequest(agent, userSegments, history, instance);
        } else if (MistralAdapter.isMistralModel(agent.getModel())
                || instance.getProvider() == Provider.MISTRAL
                || instance.getProvider() == Provider.AZURE_MISTRAL) {
            List<Message> messagesWithUser = new ArrayList<>();
            if (history != null) {
                messagesWithUser.addAll(history);
            }
            if (userMessage != null) {
                messagesWithUser.add(Message.builder().role("user").content(userMessage).build());
            }
            requestFuture = executeMistralRequest(agent, messagesWithUser, instance);
        } else if (GrokAdapter.isGrokModel(agent.getModel())
                || instance.getProvider() == Provider.GROK
                || instance.getProvider() == Provider.AZURE_GROK) {
            List<Message> messagesWithUser = new ArrayList<>();
            if (history != null) {
                messagesWithUser.addAll(history);
            }
            if (userMessage != null) {
                messagesWithUser.add(Message.builder().role("user").content(userMessage).build());
            }
            requestFuture = executeGrokRequest(agent, messagesWithUser, instance);
        } else if (DeepSeekAdapter.isDeepSeekModel(agent.getModel())
                || instance.getProvider() == Provider.DEEPSEEK) {
            List<Message> messagesWithUser = new ArrayList<>();
            if (history != null) {
                messagesWithUser.addAll(history);
            }
            if (userMessage != null) {
                messagesWithUser.add(Message.builder().role("user").content(userMessage).build());
            }
            requestFuture = executeDeepSeekRequest(agent, messagesWithUser, instance);
        } else if (GeminiAdapter.isGeminiModel(agent.getModel())
                || instance.getProvider() == Provider.GEMINI) {
            List<Message> messagesWithUser = new ArrayList<>();
            if (history != null) {
                messagesWithUser.addAll(history);
            }
            if (userMessage != null) {
                messagesWithUser.add(Message.builder().role("user").content(userMessage).build());
            }
            requestFuture = executeGeminiRequest(agent, messagesWithUser, instance);
        } else {
            requestFuture = executeOpenAIRequest(agent, userMessage, history, instance);
        }

        return requestFuture
                .whenComplete((response, error) -> {
                    limiter.release();
                    if (error == null) {
                        String usageLog = response.getTokenUsage() != null
                                ? " | " + ModelPricing.formatForLog(response.getTokenUsage()) : "";
                        logger.info("← RESPONSE END [V2] | Agent: {}{} | Response: {}",
                                agent.getName(), usageLog, response);
                    } else {
                        logger.error("← RESPONSE ERROR [V2] | Agent: {} | Error: {}",
                                agent.getName(), error.getMessage());
                    }
                });
    }

    // ==================== OPENAI RESPONSES API ====================

    private CompletableFuture<ParsedResponse> executeOpenAIRequest(
            Agent agent, String userMessage, List<Message> history, Instance instance) {

        // Build input array for Responses API
        // Format: array of messages with role and content (can be multimodal)
        List<Map<String, Object>> input = new ArrayList<>();

        // Add history first
        if (history != null) {
            for (Message msg : history) {
                input.addAll(buildOpenAIInputItems(msg));
            }
        }

        // Add current user message (null on subsequent autonomous loop iterations)
        if (userMessage != null) {
            Map<String, Object> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            input.add(userMsg);
        }

        // Build request body for Responses API
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", agent.getModel());
        requestBody.put("input", input);

        // System instructions go in separate field
        if (agent.getInstructions() != null && !agent.getInstructions().isEmpty()) {
            requestBody.put("instructions", agent.getInstructions());
        }

        if (agent.getTemperature() != null) {
            requestBody.put("temperature", agent.getTemperature());
        }

        // Responses API uses max_output_tokens
        if (agent.getMaxTokens() != null) {
            requestBody.put("max_output_tokens", agent.getMaxTokens());
        } else {
            requestBody.put("max_output_tokens", 32768);
        }

        // Reasoning configuration
        addOpenAIReasoningConfig(requestBody, agent.getReasoningEffort());

        // Add tools if configured
        List<Object> tools = buildOpenAIToolsForRequest(agent);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
            logger.debug("Added {} tools to Responses API request for agent {}", tools.size(), agent.getId());
        }

        // Add structured output (text.format) if configured
        if (agent.getResultClass() != null && !agent.getResultClass().isEmpty()) {
            String fullClassName = config.resolveResultClassName(agent.getResultClass());
            if (fullClassName != null) {
                try {
                    Class<?> resultClass = Class.forName(fullClassName);
                    Map<String, Object> schema = JsonSchemaGenerator.createFunctionSchemaFromClass(resultClass);

                    // OpenAI Responses API format: text.format.{type, name, schema, strict}
                    // name must be at format level, not inside json_schema
                    Map<String, Object> textFormat = new HashMap<>();
                    textFormat.put("type", "json_schema");
                    textFormat.put("name", resultClass.getSimpleName().toLowerCase() + "_response");
                    textFormat.put("schema", schema);
                    textFormat.put("strict", true);

                    Map<String, Object> text = new HashMap<>();
                    text.put("format", textFormat);
                    requestBody.put("text", text);
                } catch (ClassNotFoundException e) {
                    logger.warn("Result class not found: {} (resolved: {})", agent.getResultClass(), fullClassName);
                }
            }
        }

        return httpHelper.postRaw(
                instance,
                ProviderConfig.Endpoint.RESPONSES,
                agent.getModel(),
                requestBody,
                agent.getResponseTimeout()).thenApply(json -> extractResponsesContentParsed(json, instance));
    }

    /**
     * Adds reasoning configuration to an OpenAI Responses API request body.
     * If null or "none" → don't send reasoning param (model default, no reasoning for non-reasoning models).
     * "enabled" → "medium". "low"/"medium"/"high" → sent as-is.
     *
     * @param requestBody     The request body map to add reasoning config to
     * @param reasoningEffort The reasoning effort level (null, "none", "low", "medium", "high", "enabled")
     */
    private void addOpenAIReasoningConfig(Map<String, Object> requestBody, String reasoningEffort) {
        // null or blank → don't send reasoning param at all → model uses its default
        if (reasoningEffort == null || reasoningEffort.isBlank()) {
            return;
        }
        // "enabled" maps to "medium", everything else ("none", "low", "medium", "high") sent as-is
        String effort = "enabled".equalsIgnoreCase(reasoningEffort) ? "medium" : reasoningEffort.toLowerCase();
        Map<String, Object> reasoning = new HashMap<>();
        reasoning.put("effort", effort);
        requestBody.put("reasoning", reasoning);
    }

    /**
     * Extracts the text content from a Responses API response as a simple string.
     * This is a legacy method that flattens function calls to text.
     *
     * @deprecated Use {@link #extractResponsesContentParsed(String)} for structured function call access
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    private String extractResponsesContent(String jsonResponse) {
        ParsedResponse parsed = extractResponsesContentParsed(jsonResponse, null);
        if (parsed.hasFunctionCalls()) {
            // Legacy behavior: return text description of function calls
            StringBuilder sb = new StringBuilder();
            if (parsed.getTextContent() != null && !parsed.getTextContent().isEmpty()) {
                sb.append(parsed.getTextContent());
            }
            for (FunctionCall call : parsed.getFunctionCalls()) {
                sb.append("[Function call: ").append(call.getName())
                  .append("(").append(call.getArguments()).append(")] ");
            }
            return sb.toString().trim();
        }
        return parsed.getTextContent();
    }

    /**
     * Extracts content and function calls from a Responses API response.
     * Returns a ParsedResponse containing both text content and structured FunctionCall objects.
     */
    @SuppressWarnings("unchecked")
    private ParsedResponse extractResponsesContentParsed(String jsonResponse, Instance instance) {
        try {
            Map<String, Object> response = objectMapper.readValue(jsonResponse,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });

            // Detect truncated output (OpenAI Responses API: status=incomplete + max_output_tokens)
            String status = (String) response.get("status");
            if ("incomplete".equals(status)) {
                Map<String, Object> incompleteDetails = (Map<String, Object>) response.get("incomplete_details");
                String reason = incompleteDetails != null ? (String) incompleteDetails.get("reason") : "unknown";
                if ("max_output_tokens".equals(reason)) {
                    throw new AgentException(AgentException.ErrorCode.MAX_TOKENS_EXCEEDED,
                            "OpenAI response truncated (status=incomplete, reason=max_output_tokens). "
                                    + "Consider increasing maxTokens or reducing prompt size.");
                }
            }

            // Responses API returns output array
            List<Map<String, Object>> output = (List<Map<String, Object>>) response.get("output");
            if (output == null || output.isEmpty()) {
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "No output in response");
            }

            StringBuilder textContent = new StringBuilder();
            List<FunctionCall> functionCalls = new ArrayList<>();
            boolean hasReasoning = false;

            // Process all output items
            for (Map<String, Object> item : output) {
                String type = (String) item.get("type");

                if ("message".equals(type)) {
                    // Extract text from message
                    List<Map<String, Object>> content = (List<Map<String, Object>>) item.get("content");
                    if (content != null) {
                        for (Map<String, Object> contentItem : content) {
                            String contentType = (String) contentItem.get("type");
                            if ("output_text".equals(contentType) || "text".equals(contentType)) {
                                String text = (String) contentItem.get("text");
                                if (text != null) {
                                    textContent.append(text);
                                }
                            }
                        }
                    }
                } else if ("function_call".equals(type)) {
                    // Model wants to call a function - extract as structured FunctionCall
                    String callId = (String) item.get("call_id");
                    String name = (String) item.get("name");
                    String args = item.get("arguments") != null ? item.get("arguments").toString() : "{}";

                    FunctionCall call = FunctionCall.builder()
                            .id(callId)
                            .name(name)
                            .arguments(args)
                            .build();
                    functionCalls.add(call);
                    logger.debug("Parsed function call: {} with args: {}", name, args);
                } else if ("reasoning".equals(type)) {
                    // Reasoning block — model used reasoning tokens. Skip content, just flag it.
                    hasReasoning = true;
                    logger.debug("Response contains reasoning block (skipped for output)");
                }
            }

            // Extract token usage and estimated cost. OpenAI Responses API surfaces
            // cached prompt tokens in usage.input_tokens_details.cached_tokens, and
            // (like Chat Completions) usage.input_tokens INCLUDES cached tokens — so
            // we subtract `cached` to avoid double-counting at the input rate.
            TokenUsage tokenUsage = null;
            Map<String, Object> usageMap = (Map<String, Object>) response.get("usage");
            if (usageMap != null) {
                Integer inputTokens = usageMap.get("input_tokens") instanceof Number
                        ? ((Number) usageMap.get("input_tokens")).intValue() : null;
                Integer outputTokens = usageMap.get("output_tokens") instanceof Number
                        ? ((Number) usageMap.get("output_tokens")).intValue() : null;
                Integer cached = null;
                Object details = usageMap.get("input_tokens_details");
                if (details instanceof Map) {
                    Object c = ((Map<?, ?>) details).get("cached_tokens");
                    if (c instanceof Number) {
                        cached = ((Number) c).intValue();
                    }
                }
                Integer uncached = inputTokens;
                if (inputTokens != null && cached != null) {
                    uncached = Math.max(0, inputTokens - cached);
                }
                tokenUsage = calculatePricing(
                        (String) response.get("model"), uncached, outputTokens, null, cached, instance);
            }

            // If we have function calls, return them (with or without text)
            if (!functionCalls.isEmpty()) {
                String text = textContent.length() > 0 ? textContent.toString() : null;
                return ParsedResponse.of(text, functionCalls, tokenUsage);
            }

            // Return text content if available
            if (textContent.length() > 0) {
                return ParsedResponse.ofText(textContent.toString(), tokenUsage);
            }

            // Last fallback: try to extract any text from first output
            Map<String, Object> firstOutput = output.get(0);
            if (firstOutput.containsKey("content")) {
                List<Map<String, Object>> content = (List<Map<String, Object>>) firstOutput.get("content");
                if (content != null && !content.isEmpty()) {
                    Object text = content.get(0).get("text");
                    if (text != null) {
                        return ParsedResponse.ofText(text.toString(), tokenUsage);
                    }
                }
            }

            // Debug: log the response structure
            List<Object> outputTypes = output.stream()
                    .map(o -> o.get("type"))
                    .collect(java.util.stream.Collectors.toList());

            if (hasReasoning) {
                logger.error("Model returned only reasoning with no text output. "
                        + "The model likely exhausted its token budget on reasoning. "
                        + "Consider setting reasoningEffort to 'none' or increasing maxTokens.");
            }

            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "No text content in response. Output contains: " + outputTypes
                    + (hasReasoning ? " (model used all tokens for reasoning, none left for output)" : ""));

        } catch (Exception e) {
            if (e instanceof AgentException) {
                throw (AgentException) e;
            }
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Failed to parse Responses API response: " + e.getMessage(), e);
        }
    }

    // ==================== CLAUDE MESSAGES API ====================

    /**
     * Builds a Claude message from a Message object.
     * Handles both text-only and multimodal (with images) messages.
     */
    private ClaudeRequest.ClaudeMessage buildClaudeMessage(Message msg) {
        // Tool result → user message with tool_result content block
        if (msg.isToolResult()) {
            List<ClaudeRequest.ClaudeContentBlock> blocks = List.of(
                    ClaudeRequest.ClaudeContentBlock.toolResult(msg.getToolCallId(), msg.getContent()));
            return ClaudeRequest.ClaudeMessage.builder()
                    .role("user")
                    .contentBlocks(blocks)
                    .build();
        }

        // Assistant with tool calls → assistant message with tool_use content blocks
        if (msg.isAssistant() && msg.hasToolCalls()) {
            List<ClaudeRequest.ClaudeContentBlock> blocks = new ArrayList<>();
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                blocks.add(ClaudeRequest.ClaudeContentBlock.text(msg.getContent()));
            }
            for (FunctionCall call : msg.getFunctionCalls()) {
                blocks.add(ClaudeRequest.ClaudeContentBlock.toolUse(
                        call.getId(), call.getName(), call.getArgumentsAsMap()));
            }
            return ClaudeRequest.ClaudeMessage.builder()
                    .role("assistant")
                    .contentBlocks(blocks)
                    .build();
        }

        if (msg.isMultimodal()) {
            // Build multimodal content blocks for Claude
            List<ClaudeRequest.ClaudeContentBlock> contentBlocks = new ArrayList<>();
            for (Message.ContentPart part : msg.getContentParts()) {
                switch (part.getType()) {
                    case "text":
                        contentBlocks.add(ClaudeRequest.ClaudeContentBlock.text(part.getText()));
                        break;
                    case "image_url":
                        contentBlocks.add(ClaudeRequest.ClaudeContentBlock.imageUrl(part.getImageUrl()));
                        break;
                    case "image_base64":
                        contentBlocks.add(ClaudeRequest.ClaudeContentBlock.imageBase64(
                                part.getImageBase64(), part.getMediaType()));
                        break;
                    default:
                        logger.warn("Unknown content part type for Claude: {}", part.getType());
                }
            }
            return ClaudeRequest.ClaudeMessage.builder()
                    .role(msg.getRole())
                    .contentBlocks(contentBlocks)
                    .build();
        } else {
            // Simple text content
            return ClaudeRequest.ClaudeMessage.builder()
                    .role(msg.getRole())
                    .content(msg.getContent())
                    .build();
        }
    }

    private CompletableFuture<ParsedResponse> executeClaudeRequest(
            Agent agent, List<CacheableSegment> userSegments, List<Message> history, Instance instance) {

        // Build messages
        List<ClaudeRequest.ClaudeMessage> messages = new ArrayList<>();
        if (history != null) {
            for (Message msg : history) {
                if (!msg.isSystem()) { // System goes in separate field
                    messages.add(buildClaudeMessage(msg));
                }
            }
        }
        // Add current user turn (null/empty on subsequent autonomous loop iterations).
        // Each segment becomes a text content block; cache_control markers are placed at the
        // requested boundaries by ClaudeAdapter.buildUserContentBlocks (respecting the 4-breakpoint
        // cap). A single non-boundary segment degrades to one plain text block — equivalent to the
        // previous single-string user message, but expressed as a content-block array.
        if (userSegments != null && !userSegments.isEmpty()) {
            messages.add(ClaudeRequest.ClaudeMessage.builder()
                    .role("user")
                    .contentBlocks(ClaudeAdapter.buildUserContentBlocks(userSegments))
                    .build());
        }

        // Get tools
        List<ClaudeRequest.ClaudeTool> tools = ToolBuilder.buildClaudeTools(agent);

        // Get result class if configured
        Class<?> resultClass = null;
        if (agent.getResultClass() != null && !agent.getResultClass().isEmpty()) {
            String fullClassName = config.resolveResultClassName(agent.getResultClass());
            if (fullClassName != null) {
                try {
                    resultClass = Class.forName(fullClassName);
                } catch (ClassNotFoundException e) {
                    logger.warn("Result class not found: {} (resolved: {})", agent.getResultClass(), fullClassName);
                }
            }
        }

        return claudeAdapter.callClaudeAsync(
                instance,
                agent.getModel(),
                agent.getInstructions(),
                messages,
                agent.getTemperature(),
                agent.getMaxTokens() != null ? agent.getMaxTokens() : 32768,
                resultClass,
                tools,
                agent.getReasoningEffort(),
                agent.getToolChoice()).thenApply(resp -> parseClaudeResponse(resp, instance));
    }

    // ==================== RESPONSE HANDLING ====================

    /**
     * Deserializes a parsed response into an AgentResult.
     * If the response contains function calls, they are included in the result.
     */
    private CompletableFuture<AgentResult> deserializeResponse(Agent agent, ParsedResponse parsed) {
        try {
            String jsonResponse = parsed.getTextContent();
            List<FunctionCall> functionCalls = parsed.hasFunctionCalls() ? parsed.getFunctionCalls() : null;
            AgentResult result;

            // No resultClass → return DefaultResult
            if (agent.getResultClass() == null || agent.getResultClass().isEmpty()) {
                DefaultResult defaultResult = new DefaultResult(jsonResponse);
                if (functionCalls != null) defaultResult.setFunctionCalls(functionCalls);
                result = defaultResult;
            } else {
                String fullClassName = config.resolveResultClassName(agent.getResultClass());
                if (fullClassName == null) {
                    logger.warn("Cannot resolve result class '{}' for agent {} - use FQCN or configure agentResultClassPackage",
                            agent.getResultClass(), agent.getId());
                    DefaultResult defaultResult = new DefaultResult(jsonResponse);
                    if (functionCalls != null) defaultResult.setFunctionCalls(functionCalls);
                    result = defaultResult;
                } else {
                    // Parse text content as resultClass
                    Class<?> resultClass = Class.forName(fullClassName);
                    if (jsonResponse != null && !jsonResponse.isBlank()) {
                        result = (AgentResult) objectMapper.readValue(jsonResponse, resultClass);
                    } else {
                        result = (AgentResult) resultClass.getDeclaredConstructor().newInstance();
                    }

                    // Attach function calls if present (all AgentResult subclasses support this now)
                    if (functionCalls != null && !functionCalls.isEmpty()) {
                        result.setFunctionCalls(functionCalls);
                    }
                }
            }

            // Propagate token usage to result
            if (parsed.getTokenUsage() != null) {
                result.setUsage(parsed.getTokenUsage());
            }

            return CompletableFuture.completedFuture(result);

        } catch (ClassNotFoundException e) {
            return CompletableFuture.failedFuture(new AgentException(
                    AgentException.ErrorCode.INVALID_CONFIGURATION,
                    "Result class not found: " + agent.getResultClass() + " (resolved: " + config.resolveResultClassName(agent.getResultClass()) + ")"));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new AgentException(
                    AgentException.ErrorCode.DESERIALIZATION_FAILED,
                    "Failed to deserialize response: " + e.getMessage(), e));
        }
    }

    /**
     * @deprecated Use {@link #deserializeResponse(Agent, ParsedResponse)} instead
     */
    @Deprecated
    private CompletableFuture<AgentResult> deserializeResponse(Agent agent, String jsonResponse) {
        return deserializeResponse(agent, ParsedResponse.ofText(jsonResponse));
    }

    // ==================== UTILITY METHODS ====================

    private InstanceLimiter getLimiterForInstance(Instance instance) {
        return getLimiterForInstanceAndModel(instance, null);
    }

    private InstanceLimiter getLimiterForInstanceAndModel(Instance instance, String model) {
        // Per-model rate limiting: key = "instanceId:model" (or just "instanceId" if no model)
        String key = model != null ? instance.getId() + ":" + model : instance.getId();
        return instanceLimiters.computeIfAbsent(key, k -> {
            int rps = (model != null)
                    ? instance.getRateLimitForModel(model, config.getRequestsPerSecond())
                    : config.getRequestsPerSecond();
            return new InstanceLimiter(key, config.getMaxConcurrentStreamsPerInstance(), rps);
        });
    }

    private CompletableFuture<Void> delayAsync(long millis) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        DELAY_SCHEDULER.schedule(() -> future.complete(null), millis, TimeUnit.MILLISECONDS);
        return future;
    }

    private Throwable unwrapException(Throwable error) {
        if (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    /**
     * Determines the maximum number of retries allowed for a given error, based on the agent's
     * RetryConfig (falling back to global defaults).
     *
     * @param e     the error that occurred
     * @param agent the agent that made the request (may be null for model-level requests)
     * @return max retries allowed for this error type, or -1 if the error should never be retried
     */
    private int getMaxRetriesForError(Throwable e, Agent agent) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        RetryConfig agentRetryConfig = agent != null ? agent.getRetryConfig() : null;
        RetryConfig effectiveConfig = agentRetryConfig != null ? agentRetryConfig : new RetryConfig();
        RetryConfig globalDefault = config.getDefaultRetryConfig();

        // Content-filter 400s: retry across LLM instances. Empirically the Azure
        // responsible-AI filter is stochastic per request — the same prompt against
        // the same instance can pass on a later attempt — so the default is to
        // walk every compatible instance once before giving up. Set
        // contentFilterRetries to 0 to preserve the legacy "fail immediately on
        // content filter" behaviour, or to a positive integer to cap below the
        // "all instances" default.
        if (message.contains("content_filter") || message.contains("content filter")) {
            int configured = effectiveConfig.resolveContentFilterRetries(globalDefault);
            if (configured == 0) return -1;
            int compatible = countCompatibleInstances(agent);
            if (compatible <= 1) return -1;
            // Cap at "every endpoint once" (compatible - 1, since the first attempt
            // already burned one instance).
            int allInstancesCap = compatible - 1;
            if (configured == RetryConfig.DEFAULT_CONTENT_FILTER_RETRIES_USE_ALL_INSTANCES) {
                return allInstancesCap;
            }
            return Math.min(configured, allInstancesCap);
        }
        // Other 4xx errors (except 429 rate limit) are deterministic — never retry.
        if ((message.contains("400") || message.contains("401") || message.contains("403"))
                && !message.contains("429")) {
            return -1;
        }

        // Classify error type and return appropriate max retries
        if (e instanceof AgentException) {
            AgentException ae = (AgentException) e;
            switch (ae.getErrorCode()) {
                case MAX_TOKENS_EXCEEDED:
                    return effectiveConfig.resolveMaxTokenRetries(globalDefault);
                case DESERIALIZATION_FAILED:
                    return effectiveConfig.resolveDeserializationRetries(globalDefault);
                case MAX_ITERATIONS_EXCEEDED:
                    return effectiveConfig.resolveMaxIterationRetries(globalDefault);
                case INVALID_CONFIGURATION:
                case UNSUPPORTED_FEATURE:
                    // Configuration / unsupported-feature errors are deterministic — never retry.
                    return -1;
                default:
                    break;
            }
        }

        // All other errors (network, rate limit, 502, timeout, etc.) → network retries
        return effectiveConfig.resolveNetworkRetries(globalDefault);
    }

    /**
     * @deprecated Use {@link #getMaxRetriesForError(Throwable, Agent)} instead
     */
    @Deprecated
    private boolean shouldRetry(Throwable e) {
        return getMaxRetriesForError(e, null) != -1;
    }

    private long calculateDelay(Throwable e, int attempt) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        if (message.contains("502")) {
            return config.getError502DelayMs();
        }

        return config.getRetryBaseDelayMs() * (long) Math.pow(2, attempt);
    }

    /**
     * Counts how many configured LLM instances expose the agent's model.
     * Returns 0 when {@code agent} or its model is null. Used by
     * {@link #getMaxRetriesForError(Throwable, Agent)} to bound content-filter
     * retries to "every endpoint at most once".
     */
    private int countCompatibleInstances(Agent agent) {
        if (agent == null || agent.getModel() == null || instanceRouter.isDegradedMode()) {
            return 0;
        }
        String model = agent.getModel();
        int count = 0;
        for (io.github.yannfavinleveque.agentic.agent.core.Instance inst : instanceRouter.getInstances()) {
            if (inst.getDeployedModels().contains(model)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Builds an OpenAI Responses API input message from a Message object.
     * Handles both text-only and multimodal (with images) messages.
     */
    /**
     * Builds one or more OpenAI Responses API input items from a Message.
     * Returns multiple items for assistant messages with tool calls (function_call items)
     * and for tool result messages (function_call_output items).
     * For normal text/multimodal messages, returns a single-item list.
     */
    private List<Map<String, Object>> buildOpenAIInputItems(Message msg) {
        List<Map<String, Object>> items = new ArrayList<>();

        // Tool result → function_call_output item
        if (msg.isToolResult()) {
            Map<String, Object> item = new HashMap<>();
            item.put("type", "function_call_output");
            item.put("call_id", msg.getToolCallId());
            item.put("output", msg.getContent());
            items.add(item);
            return items;
        }

        // Assistant with tool calls → function_call items
        if (msg.isAssistant() && msg.hasToolCalls()) {
            for (FunctionCall call : msg.getFunctionCalls()) {
                Map<String, Object> callItem = new HashMap<>();
                callItem.put("type", "function_call");
                callItem.put("call_id", call.getId());
                callItem.put("name", call.getName());
                callItem.put("arguments", call.getArguments());
                items.add(callItem);
            }
            return items;
        }

        // Default: single message item
        items.add(buildOpenAIInputMessage(msg));
        return items;
    }

    private Map<String, Object> buildOpenAIInputMessage(Message msg) {
        Map<String, Object> inputMsg = new HashMap<>();
        inputMsg.put("role", msg.getRole());

        if (msg.isMultimodal()) {
            // Build multimodal content array for OpenAI
            List<Map<String, Object>> contentParts = new ArrayList<>();
            for (Message.ContentPart part : msg.getContentParts()) {
                Map<String, Object> contentPart = new HashMap<>();

                switch (part.getType()) {
                    case "text":
                        contentPart.put("type", "input_text");
                        contentPart.put("text", part.getText());
                        break;
                    case "image_url":
                        contentPart.put("type", "input_image");
                        contentPart.put("image_url", part.getImageUrl());
                        break;
                    case "image_base64":
                        contentPart.put("type", "input_image");
                        // OpenAI expects data URL format for base64
                        String dataUrl = "data:" + part.getMediaType() + ";base64," + part.getImageBase64();
                        contentPart.put("image_url", dataUrl);
                        break;
                    default:
                        logger.warn("Unknown content part type: {}", part.getType());
                        continue;
                }
                contentParts.add(contentPart);
            }
            inputMsg.put("content", contentParts);
        } else {
            // Simple text content
            inputMsg.put("content", msg.getContent());
        }

        return inputMsg;
    }

    /**
     * Builds tools list for OpenAI Responses API format.
     */
    @SuppressWarnings("unchecked")
    private List<Object> buildOpenAIToolsForRequest(Agent agent) {
        if (!ToolBuilder.hasTools(agent)) {
            return null;
        }

        List<Object> tools = new ArrayList<>();

        // Web search tool
        if (Boolean.TRUE.equals(agent.getWebSearch())) {
            Map<String, Object> webSearchTool = new HashMap<>();
            webSearchTool.put("type", "web_search_preview");
            tools.add(webSearchTool);
            logger.debug("Added web_search_preview tool for agent {}", agent.getId());
        }

        // Code interpreter tool - requires container config for OpenAI
        if (Boolean.TRUE.equals(agent.getCodeInterpreter())) {
            Map<String, Object> codeInterpreterTool = new HashMap<>();
            codeInterpreterTool.put("type", "code_interpreter");
            // OpenAI Responses API requires a container specification
            Map<String, Object> container = new HashMap<>();
            container.put("type", "auto");  // Let OpenAI choose the container
            codeInterpreterTool.put("container", container);
            tools.add(codeInterpreterTool);
            logger.debug("Added code_interpreter tool for agent {}", agent.getId());
        }

        // File search tool (from retrieval flag)
        if (Boolean.TRUE.equals(agent.getRetrieval())) {
            Map<String, Object> fileSearchTool = new HashMap<>();
            fileSearchTool.put("type", "file_search");
            tools.add(fileSearchTool);
            logger.debug("Added file_search tool for agent {}", agent.getId());
        }

        // Custom functions - Responses API format (name/description/parameters at root level)
        if (agent.getFunctions() != null && !agent.getFunctions().isEmpty()) {
            String parameterClassPackage = config.getFunctionParameterClassPackage();
            for (var func : agent.getFunctions()) {
                if (!ToolBuilder.isFunctionEnabledForAgent(func, agent)) {
                    logger.debug("Filtered out function '{}' (group={}) — not in enabledToolGroups {}",
                            func.getName(), func.getGroup(), agent.getEnabledToolGroups());
                    continue;
                }
                Map<String, Object> functionTool = new HashMap<>();
                functionTool.put("type", "function");
                functionTool.put("name", func.getName());
                if (func.getDescription() != null) {
                    functionTool.put("description", func.getDescription());
                }

                // Build parameters schema using ToolBuilder (supports FQCN and simple names)
                Map<String, Object> parameters = ToolBuilder.buildFunctionSchema(func, parameterClassPackage);
                functionTool.put("parameters", parameters);

                tools.add(functionTool);
                logger.debug("Added function tool '{}' for agent {} with schema: {}", func.getName(), agent.getId(), parameters);
            }
        }

        return tools.isEmpty() ? null : tools;
    }

    // ==================== CHAT COMPLETIONS ====================

    /**
     * Executes a chat completion request (string response).
     *
     * @param model       Model name
     * @param messages    Chat messages
     * @param temperature Temperature (optional)
     * @return Response content
     *
     * @deprecated since 1.22.0 — Use {@code requestModel(String, String, ModelRequestOptions)}
     *             instead. The chatCompletion family is legacy: OpenAI Chat Completions only,
     *             no Anthropic/Mistral/custom routing, no web search / code interpreter /
     *             reasoning / structured output via Responses API. To be removed in 2.0.0.
     */
    @Deprecated
    public CompletableFuture<String> requestChatCompletion(String model, List<ChatMessage> messages,
            Double temperature) {
        return attemptChatCompletion(model, messages, temperature, 0);
    }

    /**
     * Executes a chat completion with structured output.
     *
     * @param model       Model name
     * @param messages    Chat messages
     * @param temperature Temperature (optional)
     * @param resultClass Result class for typed response (null = DefaultResult)
     * @return Typed result
     *
     * @deprecated since 1.22.0 — Use {@code requestModel(String, String, ModelRequestOptions)}
     *             instead. The chatCompletion family is legacy: OpenAI Chat Completions only,
     *             no Anthropic/Mistral/custom routing, no web search / code interpreter /
     *             reasoning / structured output via Responses API. To be removed in 2.0.0.
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public <T extends AgentResult> CompletableFuture<T> chatCompletion(String model,
            List<ChatMessage> messages,
            Double temperature,
            Class<T> resultClass) {
        return attemptChatCompletionStructured(model, messages, temperature, resultClass, 0);
    }

    /**
     * Executes a chat completion without structured output.
     *
     * @deprecated since 1.22.0 — Use {@code requestModel(String, String, ModelRequestOptions)}
     *             instead. The chatCompletion family is legacy: OpenAI Chat Completions only,
     *             no Anthropic/Mistral/custom routing, no web search / code interpreter /
     *             reasoning / structured output via Responses API. To be removed in 2.0.0.
     */
    @Deprecated
    public CompletableFuture<DefaultResult> chatCompletion(String model, List<ChatMessage> messages,
            Double temperature) {
        return chatCompletion(model, messages, temperature, DefaultResult.class);
    }

    /**
     * Executes a stateless chat completion with structured output by class name.
     * Resolves the class from agentResultClassPackage + resultClassName.
     *
     * @param model                   Model name (e.g., "gpt-4o", "claude-sonnet-4-5")
     * @param messages                List of chat messages
     * @param temperature             Temperature for response generation
     * @param resultClassName         Simple class name (e.g., "WeatherResult")
     * @param agentResultClassPackage Package containing the result class
     * @return CompletableFuture with typed result
     *
     * @deprecated since 1.22.0 — Use {@code requestModel(String, String, ModelRequestOptions)}
     *             instead. The chatCompletion family is legacy: OpenAI Chat Completions only,
     *             no Anthropic/Mistral/custom routing, no web search / code interpreter /
     *             reasoning / structured output via Responses API. To be removed in 2.0.0.
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public <T extends AgentResult> CompletableFuture<T> chatCompletion(
            String model,
            List<ChatMessage> messages,
            Double temperature,
            String resultClassName,
            String agentResultClassPackage) {

        if (agentResultClassPackage == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("agentResultClassPackage not configured"));
        }

        try {
            String fullClassName = agentResultClassPackage + "." + resultClassName;
            Class<T> resultClass = (Class<T>) Class.forName(fullClassName);
            return chatCompletion(model, messages, temperature, resultClass);
        } catch (ClassNotFoundException e) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Result class not found: " + resultClassName, e));
        }
    }

    private CompletableFuture<String> attemptChatCompletion(String model, List<ChatMessage> messages,
            Double temperature, int attemptNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIdx = instanceRouter.getNextInstanceForModel(model);
                Instance instance = instanceRouter.getInstance(instanceIdx);

                // Check if Anthropic model
                if (ProviderConfig.isAnthropicModel(model)) {
                    return executeChatCompletionClaude(model, messages, temperature, instance);
                }

                // OpenAI
                return executeChatCompletionOpenAI(model, messages, temperature, null, instance);

            } catch (Exception e) {
                return handleChatCompletionException(model, messages, temperature, attemptNumber, e);
            }
        }, httpHelper.getExecutor());
    }

    @SuppressWarnings("unchecked")
    private <T extends AgentResult> CompletableFuture<T> attemptChatCompletionStructured(
            String model, List<ChatMessage> messages, Double temperature,
            Class<T> resultClass, int attemptNumber) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIdx = instanceRouter.getNextInstanceForModel(model);
                Instance instance = instanceRouter.getInstance(instanceIdx);

                String jsonResponse;

                if (ProviderConfig.isAnthropicModel(model)) {
                    jsonResponse = executeChatCompletionClaudeStructured(model, messages, temperature,
                            instance, resultClass);
                } else {
                    ResponseFormat format = null;
                    if (resultClass != null && resultClass != DefaultResult.class) {
                        format = JsonSchemaGenerator.createResponseFormatFromClass(resultClass);
                    }
                    jsonResponse = executeChatCompletionOpenAI(model, messages, temperature, format, instance);
                }

                // Return DefaultResult if no class specified
                if (resultClass == null || resultClass == DefaultResult.class) {
                    return (T) new DefaultResult(jsonResponse);
                }

                // Fix Map fields from Claude responses
                if (ProviderConfig.isAnthropicModel(model)) {
                    jsonResponse = JsonSchemaGenerator.fixMapFieldsFromResponse(jsonResponse, resultClass);
                }

                return objectMapper.readValue(jsonResponse, resultClass);

            } catch (Exception e) {
                return handleChatCompletionStructuredException(model, messages, temperature,
                        resultClass, attemptNumber, e);
            }
        }, httpHelper.getExecutor());
    }

    private String executeChatCompletionOpenAI(String model, List<ChatMessage> messages,
            Double temperature, ResponseFormat format,
            Instance instance) {
        // LOG REQUEST START
        String messagesPreview = messages.size() + " messages";
        logger.info("-> CHAT START | Messages: {} | Temp: {} | Model: {} | Instance: {}",
                messagesPreview, temperature, model, instance.getId());

        ChatRequest.ChatRequestBuilder requestBuilder = ChatRequest.builder()
                .model(model)
                .messages(messages);

        if (temperature != null) {
            requestBuilder.temperature(temperature);
        }
        if (format != null) {
            requestBuilder.responseFormat(format);
        }

        Chat chatResponse = httpHelper.post(instance, ProviderConfig.Endpoint.CHAT_COMPLETIONS,
                model, requestBuilder.build(), Chat.class).join();

        if (chatResponse.getChoices() == null || chatResponse.getChoices().isEmpty()) {
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "No choices returned in chat completion");
        }

        String response = chatResponse.getChoices().get(0).getMessage().getContent();

        // LOG RESPONSE END with usage — OpenAI's prompt_tokens INCLUDES cached tokens,
        // so subtract to isolate the uncached portion (priced at the input rate)
        // from the cache-read portion (priced at the cache-read rate, ~10x cheaper).
        String responsePreview = response.length() > 200 ? response.substring(0, 200) + "..." : response;
        if (chatResponse.getUsage() != null) {
            Integer cached = null;
            if (chatResponse.getUsage().getPromptTokensDetails() != null) {
                cached = chatResponse.getUsage().getPromptTokensDetails().getCachedTokens();
            }
            Integer promptTokens = chatResponse.getUsage().getPromptTokens();
            Integer uncached = promptTokens;
            if (promptTokens != null && cached != null) {
                uncached = Math.max(0, promptTokens - cached);
            }
            TokenUsage tokenUsage = calculatePricing(model,
                    uncached,
                    chatResponse.getUsage().getCompletionTokens(),
                    null, cached,
                    instance);
            logger.info("<- CHAT END | {} | Response: {} | Model: {} | Instance: {}",
                    ModelPricing.formatForLog(tokenUsage), responsePreview, model, instance.getId());
        } else {
            logger.info("<- CHAT END | Response: {} | Model: {} | Instance: {}",
                    responsePreview, model, instance.getId());
        }

        return response;
    }

    private String executeChatCompletionClaude(String model, List<ChatMessage> messages,
            Double temperature, Instance instance) {
        // LOG REQUEST START
        String messagesPreview = messages.size() + " messages";
        logger.info("-> CHAT START | Messages: {} | Temp: {} | Model: {} | Instance: {}",
                messagesPreview, temperature, model, instance.getId());

        List<ClaudeRequest.ClaudeMessage> claudeMessages = claudeAdapter.convertToClaude(messages);
        String systemPrompt = claudeAdapter.extractSystemPrompt(messages);

        ClaudeResponse claudeResponse = claudeAdapter.callClaude(instance, model, systemPrompt,
                claudeMessages, temperature, 32768, null);

        String response = claudeResponse.getTextContent();

        // LOG RESPONSE END with usage — Anthropic surfaces cache writes/reads in
        // separate fields; input_tokens is already the uncached portion.
        String responsePreview = response.length() > 200 ? response.substring(0, 200) + "..." : response;
        if (claudeResponse.getUsage() != null) {
            TokenUsage tokenUsage = calculatePricing(model,
                    claudeResponse.getUsage().getInputTokens(),
                    claudeResponse.getUsage().getOutputTokens(),
                    claudeResponse.getUsage().getCacheCreationInputTokens(),
                    claudeResponse.getUsage().getCacheReadInputTokens(),
                    instance);
            logger.info("<- CHAT END | {} | Response: {} | Model: {} | Instance: {}",
                    ModelPricing.formatForLog(tokenUsage), responsePreview, model, instance.getId());
        } else {
            logger.info("<- CHAT END | Response: {} | Model: {} | Instance: {}",
                    responsePreview, model, instance.getId());
        }

        return response;
    }

    private <T extends AgentResult> String executeChatCompletionClaudeStructured(
            String model, List<ChatMessage> messages, Double temperature,
            Instance instance, Class<T> resultClass) {

        // LOG REQUEST START
        String messagesPreview = messages.size() + " messages";
        logger.info("-> CHAT STRUCTURED START | Messages: {} | Temp: {} | ResultClass: {} | Model: {} | Instance: {}",
                messagesPreview, temperature, resultClass != null ? resultClass.getSimpleName() : "null",
                model, instance.getId());

        List<ClaudeRequest.ClaudeMessage> claudeMessages = claudeAdapter.convertToClaude(messages);
        String systemPrompt = claudeAdapter.extractSystemPrompt(messages);

        ClaudeResponse claudeResponse = claudeAdapter.callClaude(instance, model, systemPrompt,
                claudeMessages, temperature, 32768, resultClass);

        String response = claudeResponse.getTextContent();

        // LOG RESPONSE END with usage — Anthropic cache stats are split across
        // input_tokens (uncached) / cache_creation_input_tokens / cache_read_input_tokens.
        String responsePreview = response.length() > 200 ? response.substring(0, 200) + "..." : response;
        if (claudeResponse.getUsage() != null) {
            TokenUsage tokenUsage = calculatePricing(model,
                    claudeResponse.getUsage().getInputTokens(),
                    claudeResponse.getUsage().getOutputTokens(),
                    claudeResponse.getUsage().getCacheCreationInputTokens(),
                    claudeResponse.getUsage().getCacheReadInputTokens(),
                    instance);
            logger.info("<- CHAT STRUCTURED END | {} | Response: {} | Model: {} | Instance: {}",
                    ModelPricing.formatForLog(tokenUsage), responsePreview, model, instance.getId());
        } else {
            logger.info("<- CHAT STRUCTURED END | Response: {} | Model: {} | Instance: {}",
                    responsePreview, model, instance.getId());
        }

        return response;
    }

    private String handleChatCompletionException(String model, List<ChatMessage> messages,
            Double temperature, int attemptNumber, Exception e) {
        if (shouldRetryChatCompletion(e, attemptNumber)) {
            long delay = calculateChatRetryDelay(e, attemptNumber);
            logger.warn("Chat completion failed (attempt {}/{}), retrying in {}ms: {}",
                    attemptNumber + 1, config.getMaxRetries(), delay, e.getMessage());

            try {
                Thread.sleep(delay);
                return attemptChatCompletion(model, messages, temperature, attemptNumber + 1).join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Retry interrupted", ie);
            }
        }

        throw translateChatException(e);
    }

    @SuppressWarnings("unchecked")
    private <T extends AgentResult> T handleChatCompletionStructuredException(
            String model, List<ChatMessage> messages, Double temperature,
            Class<T> resultClass, int attemptNumber, Exception e) {

        if (shouldRetryChatCompletion(e, attemptNumber)) {
            long delay = calculateChatRetryDelay(e, attemptNumber);
            logger.warn("Structured chat completion failed (attempt {}/{}), retrying in {}ms: {}",
                    attemptNumber + 1, config.getMaxRetries(), delay, e.getMessage());

            try {
                Thread.sleep(delay);
                return attemptChatCompletionStructured(model, messages, temperature,
                        resultClass, attemptNumber + 1).join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Retry interrupted", ie);
            }
        }

        throw translateChatException(e);
    }

    private boolean shouldRetryChatCompletion(Exception e, int attemptNumber) {
        if (attemptNumber >= config.getMaxRetries()) {
            logger.error("Max retries ({}) reached, not retrying", config.getMaxRetries());
            return false;
        }

        String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // Don't retry content filter errors
        if (ContentFilterException.isContentFilterError(errorMessage)) {
            logger.error("Content filter error detected, not retrying");
            return false;
        }

        // Don't retry 4xx errors (except 429 rate limit)
        if (is4xxError(errorMessage) && !isRateLimitError(errorMessage)) {
            logger.error("Client error (4xx) detected, not retrying: {}", e.getMessage());
            return false;
        }

        // Retry rate limits, timeouts, 5xx errors
        return isRateLimitError(errorMessage) || e instanceof RequestTimeoutException ||
                is5xxError(errorMessage);
    }

    private long calculateChatRetryDelay(Exception e, int attemptNumber) {
        String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // Rate limit: use configured rate limit delay
        if (isRateLimitError(errorMessage)) {
            return config.getRateLimitDelayMs();
        }

        // Timeout: no additional delay
        if (e instanceof RequestTimeoutException) {
            return 0;
        }

        // Exponential backoff for other errors
        return config.getRetryBaseDelayMs() * (long) Math.pow(2, attemptNumber);
    }

    private boolean isRateLimitError(String errorMessage) {
        return errorMessage.contains("rate_limit") || errorMessage.contains("429") ||
                errorMessage.contains("rate limit");
    }

    private boolean is4xxError(String errorMessage) {
        return errorMessage.contains("400") || errorMessage.contains("401") ||
                errorMessage.contains("403") || errorMessage.contains("404") ||
                errorMessage.contains("422");
    }

    private boolean is5xxError(String errorMessage) {
        return errorMessage.contains("500") || errorMessage.contains("502") ||
                errorMessage.contains("503") || errorMessage.contains("504");
    }

    private RuntimeException translateChatException(Exception e) {
        if (e instanceof AgentException) {
            return (AgentException) e;
        }

        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        if (ContentFilterException.isContentFilterError(message)) {
            return new ContentFilterException(e.getMessage(), e);
        }

        if (isRateLimitError(message)) {
            return new RateLimitException("Rate limit exceeded: " + e.getMessage());
        }

        return new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                "Chat completion failed: " + e.getMessage(), e);
    }

    // ==================== EMBEDDINGS ====================

    /**
     * Generates embeddings for text.
     *
     * @param text  Text to embed
     * @param model Embedding model
     * @return Float array of embeddings
     */
    public CompletableFuture<float[]> generateEmbedding(String text, String model) {
        return attemptEmbedding(text, model, 0);
    }

    /**
     * Generates embeddings using default model (text-embedding-3-small).
     */
    public CompletableFuture<float[]> generateEmbedding(String text) {
        return generateEmbedding(text, "text-embedding-3-small");
    }

    /**
     * Alias for {@link #generateEmbedding(String, String)} for API consistency.
     * Use requestEmbedding/requestModel/requestAgent for a consistent API.
     *
     * @param text  Text to embed
     * @param model Embedding model
     * @return Float array of embeddings
     */
    public CompletableFuture<float[]> requestEmbedding(String text, String model) {
        return generateEmbedding(text, model);
    }

    /**
     * Alias for {@link #generateEmbedding(String)} for API consistency.
     */
    public CompletableFuture<float[]> requestEmbedding(String text) {
        return generateEmbedding(text);
    }

    /**
     * Generates embeddings for multiple texts in a SINGLE batch API call.
     * Much more efficient than calling generateEmbedding() multiple times.
     *
     * @param texts List of texts to embed (max 2048 per OpenAI limits)
     * @param model Embedding model
     * @return List of float arrays (same order as input texts)
     */
    public CompletableFuture<List<float[]>> generateEmbeddingsBatch(List<String> texts, String model) {
        return attemptEmbeddingsBatch(texts, model, 0);
    }

    /**
     * Generates batch embeddings using default model (text-embedding-3-small).
     */
    public CompletableFuture<List<float[]>> generateEmbeddingsBatch(List<String> texts) {
        return generateEmbeddingsBatch(texts, "text-embedding-3-small");
    }

    /**
     * Alias for {@link #generateEmbeddingsBatch(List, String)} for API consistency.
     *
     * @param texts List of texts to embed
     * @param model Embedding model
     * @return List of float arrays
     */
    public CompletableFuture<List<float[]>> requestEmbeddings(List<String> texts, String model) {
        return generateEmbeddingsBatch(texts, model);
    }

    /**
     * Alias for {@link #generateEmbeddingsBatch(List)} for API consistency.
     */
    public CompletableFuture<List<float[]>> requestEmbeddings(List<String> texts) {
        return generateEmbeddingsBatch(texts);
    }

    private CompletableFuture<float[]> attemptEmbedding(String text, String model, int attemptNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeEmbeddingRequest(text, model);
            } catch (Exception e) {
                return handleEmbeddingException(text, model, attemptNumber, e);
            }
        }, httpHelper.getExecutor());
    }

    private CompletableFuture<List<float[]>> attemptEmbeddingsBatch(List<String> texts, String model, int attemptNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeEmbeddingsBatchRequest(texts, model);
            } catch (Exception e) {
                return handleEmbeddingsBatchException(texts, model, attemptNumber, e);
            }
        }, httpHelper.getExecutor());
    }

    private float[] executeEmbeddingRequest(String text, String model) throws Exception {
        int instanceIdx = instanceRouter.getNextInstanceForModel(model);
        Instance instance = instanceRouter.getInstance(instanceIdx);

        // Truncate text for logging
        String textPreview = text.length() > 100 ? text.substring(0, 100) + "..." : text;

        // LOG REQUEST START
        logger.info("-> EMBEDDING START | Input: {} | Model: {} | Instance: {}",
                textPreview, model, instance.getId());

        EmbeddingRequest request = EmbeddingRequest.builder()
                .model(model)
                .input(text)
                .build();

        EmbeddingResponse response = httpHelper.post(instance, ProviderConfig.Endpoint.EMBEDDINGS,
                model, request, EmbeddingResponse.class).join();

        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Empty response from embeddings API");
        }

        List<Double> embedding = response.getData().get(0).getEmbedding();
        float[] result = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            result[i] = embedding.get(i).floatValue();
        }

        // LOG RESPONSE END with usage
        if (response.getUsage() != null) {
            TokenUsage tokenUsage = calculatePricing(model,
                    response.getUsage().getPromptTokens(), 0, instance);
            logger.info("<- EMBEDDING END | Dimensions: {} | {} | Model: {} | Instance: {}",
                    result.length, ModelPricing.formatForLog(tokenUsage), model, instance.getId());
        } else {
            logger.info("<- EMBEDDING END | Dimensions: {} | Model: {} | Instance: {}",
                    result.length, model, instance.getId());
        }

        return result;
    }

    private List<float[]> executeEmbeddingsBatchRequest(List<String> texts, String model) throws Exception {
        int instanceIdx = instanceRouter.getNextInstanceForModel(model);
        Instance instance = instanceRouter.getInstance(instanceIdx);

        // LOG REQUEST START
        logger.info("-> EMBEDDING BATCH START | Count: {} | Model: {} | Instance: {}",
                texts.size(), model, instance.getId());

        EmbeddingRequest request = EmbeddingRequest.builder()
                .model(model)
                .input(texts)  // Array of strings!
                .build();

        EmbeddingResponse response = httpHelper.post(instance, ProviderConfig.Endpoint.EMBEDDINGS,
                model, request, EmbeddingResponse.class).join();

        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Empty response from embeddings batch API");
        }

        // Convert all embeddings
        List<float[]> results = new ArrayList<>();
        for (EmbeddingResponse.EmbeddingData embeddingData : response.getData()) {
            List<Double> embedding = embeddingData.getEmbedding();
            float[] result = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                result[i] = embedding.get(i).floatValue();
            }
            results.add(result);
        }

        // LOG RESPONSE END with usage
        if (response.getUsage() != null) {
            TokenUsage tokenUsage = calculatePricing(model,
                    response.getUsage().getPromptTokens(), 0, instance);
            logger.info("<- EMBEDDING BATCH END | Count: {} | Dimensions: {} | {} | Model: {} | Instance: {}",
                    results.size(), results.isEmpty() ? 0 : results.get(0).length,
                    ModelPricing.formatForLog(tokenUsage), model, instance.getId());
        } else {
            logger.info("<- EMBEDDING BATCH END | Count: {} | Dimensions: {} | Model: {} | Instance: {}",
                    results.size(), results.isEmpty() ? 0 : results.get(0).length, model, instance.getId());
        }

        return results;
    }

    private float[] handleEmbeddingException(String text, String model, int attemptNumber, Exception e) {
        if (shouldRetryChatCompletion(e, attemptNumber)) {
            long delay = calculateChatRetryDelay(e, attemptNumber);
            logger.warn("Embedding generation failed (attempt {}/{}), retrying in {}ms: {}",
                    attemptNumber + 1, config.getMaxRetries(), delay, e.getMessage());

            try {
                Thread.sleep(delay);
                return attemptEmbedding(text, model, attemptNumber + 1).join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Retry interrupted", ie);
            }
        }

        throw translateChatException(e);
    }

    private List<float[]> handleEmbeddingsBatchException(List<String> texts, String model, int attemptNumber, Exception e) {
        if (shouldRetryChatCompletion(e, attemptNumber)) {
            long delay = calculateChatRetryDelay(e, attemptNumber);
            logger.warn("Batch embedding generation failed (attempt {}/{}), retrying in {}ms: {}",
                    attemptNumber + 1, config.getMaxRetries(), delay, e.getMessage());

            try {
                Thread.sleep(delay);
                return attemptEmbeddingsBatch(texts, model, attemptNumber + 1).join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Retry interrupted", ie);
            }
        }

        throw translateChatException(e);
    }

    // ==================== IMAGE GENERATION ====================

    /**
     * Generates an image using DALL-E.
     *
     * @param prompt  Image description
     * @param model   Model name (e.g., "dall-e-3")
     * @param size    Image size
     * @param quality Image quality
     * @return Base64-encoded image data
     */
    public CompletableFuture<String> generateImage(String prompt, String model, Size size, Quality quality) {
        return attemptImageGeneration(prompt, model, size, quality, 0);
    }

    /**
     * Generates an image with default settings (dall-e-3, 1024x1024, standard quality).
     */
    public CompletableFuture<String> generateImage(String prompt) {
        return generateImage(prompt, "dall-e-3", Size.X1024, Quality.STANDARD);
    }

    /**
     * Edits/transforms an existing image based on a text prompt.
     * For gpt-image-1: Uses image-to-image editing capabilities.
     * For dall-e-2: Supports masked editing with transparent areas.
     * Note: dall-e-3 does not support image editing.
     *
     * @param imageBase64 Base64-encoded input image (PNG format)
     * @param prompt      Text description of desired changes/evolution
     * @param model       Model to use (gpt-image-1 or dall-e-2)
     * @param size        Output image size
     * @param quality     Output quality (STANDARD or HD)
     * @return Base64-encoded edited image
     */
    public CompletableFuture<String> editImage(String imageBase64, String prompt, String model,
            Size size, Quality quality) {
        return attemptImageEdit(imageBase64, prompt, model, size, quality, 0);
    }

    /**
     * Edits an image with default settings (gpt-image-1, 1024x1024, standard quality).
     */
    public CompletableFuture<String> editImage(String imageBase64, String prompt) {
        return editImage(imageBase64, prompt, "gpt-image-1", Size.X1024, Quality.STANDARD);
    }

    private CompletableFuture<String> attemptImageGeneration(String prompt, String model, Size size,
            Quality quality, int attemptNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeImageGenerationRequest(prompt, model, size, quality);
            } catch (Exception e) {
                return handleImageGenerationException(prompt, model, size, quality, attemptNumber, e);
            }
        }, httpHelper.getExecutor());
    }

    private CompletableFuture<String> attemptImageEdit(String imageBase64, String prompt, String model,
            Size size, Quality quality, int attemptNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeImageEditRequest(imageBase64, prompt, model, size, quality);
            } catch (Exception e) {
                return handleImageEditException(imageBase64, prompt, model, size, quality, attemptNumber, e);
            }
        }, httpHelper.getExecutor());
    }

    private String executeImageGenerationRequest(String prompt, String model, Size size, Quality quality)
            throws Exception {
        int instanceIdx = instanceRouter.getNextInstanceForModel(model);
        Instance instance = instanceRouter.getInstance(instanceIdx);

        // Truncate prompt for logging
        String promptPreview = prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt;

        // LOG REQUEST START
        logger.info("-> IMAGE GEN START | Prompt: {} | Size: {} | Quality: {} | Model: {} | Instance: {}",
                promptPreview, size, quality, model, instance.getId());

        // Build request - responseFormat handling varies by model:
        // - DALL-E 3: supports response_format (defaults to URL without it)
        // - gpt-image-1: does NOT support response_format (always returns base64)
        ImageRequest.ImageRequestBuilder requestBuilder = ImageRequest.builder()
                .model(model)
                .prompt(prompt)
                .size(size)
                .quality(quality)
                .n(1);

        // Add response_format for DALL-E 3 (not for gpt-image-1)
        if ("dall-e-3".equals(model) || "dall-e-2".equals(model)) {
            requestBuilder.responseFormat(ImageResponseFormat.B64JSON);
        }

        ImageRequest imageRequest = requestBuilder.build();

        ImageGenerationResponse response = httpHelper.post(instance, ProviderConfig.Endpoint.IMAGES_GENERATIONS,
                model, imageRequest, ImageGenerationResponse.class).join();

        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Image generation returned empty response");
        }

        String imageData = response.getData().get(0).getB64Json();

        // LOG RESPONSE END
        logger.info("<- IMAGE GEN END | ImageDataSize: {} bytes | Model: {} | Instance: {}",
                imageData != null ? imageData.length() : 0, model, instance.getId());

        return imageData;
    }

    private String executeImageEditRequest(String imageBase64, String prompt, String model, Size size, Quality quality)
            throws Exception {
        int instanceIdx = instanceRouter.getNextInstanceForModel(model);
        Instance instance = instanceRouter.getInstance(instanceIdx);

        // Truncate prompt for logging
        String promptPreview = prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt;

        // LOG REQUEST START
        logger.info("-> IMAGE EDIT START | Prompt: {} | InputSize: {} bytes | Size: {} | Quality: {} | Model: {} | Instance: {}",
                promptPreview, imageBase64 != null ? imageBase64.length() : 0, size, quality, model, instance.getId());

        // Build form fields for multipart request
        Map<String, String> formFields = new HashMap<>();
        formFields.put("prompt", prompt);
        formFields.put("model", model);
        formFields.put("n", "1");
        formFields.put("size", size.toString()); // Now returns "1024x1024" directly

        // Add response_format for DALL-E models (gpt-image-1 doesn't support it)
        if ("dall-e-3".equals(model) || "dall-e-2".equals(model)) {
            formFields.put("response_format", "b64_json");
        }

        // Use multipart/form-data to send base64 image
        ImageGenerationResponse response = httpHelper.postMultipartBase64(
                instance,
                ProviderConfig.Endpoint.IMAGES_EDITS,
                imageBase64,
                formFields,
                ImageGenerationResponse.class
        ).join();

        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                    "Image edit returned empty response");
        }

        String imageData = response.getData().get(0).getB64Json();

        // LOG RESPONSE END
        logger.info("<- IMAGE EDIT END | OutputSize: {} bytes | Model: {} | Instance: {}",
                imageData != null ? imageData.length() : 0, model, instance.getId());

        return imageData;
    }

    private String handleImageGenerationException(String prompt, String model, Size size,
            Quality quality, int attemptNumber, Exception e) {
        if (shouldRetryChatCompletion(e, attemptNumber)) {
            long delay = calculateChatRetryDelay(e, attemptNumber);
            logger.warn("Image generation failed (attempt {}/{}), retrying in {}ms: {}",
                    attemptNumber + 1, config.getMaxRetries(), delay, e.getMessage());

            try {
                Thread.sleep(delay);
                return attemptImageGeneration(prompt, model, size, quality, attemptNumber + 1).join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Retry interrupted", ie);
            }
        }

        throw translateChatException(e);
    }

    private String handleImageEditException(String imageBase64, String prompt, String model, Size size,
            Quality quality, int attemptNumber, Exception e) {
        if (shouldRetryChatCompletion(e, attemptNumber)) {
            long delay = calculateChatRetryDelay(e, attemptNumber);
            logger.warn("Image edit failed (attempt {}/{}), retrying in {}ms: {}",
                    attemptNumber + 1, config.getMaxRetries(), delay, e.getMessage());

            try {
                Thread.sleep(delay);
                return attemptImageEdit(imageBase64, prompt, model, size, quality, attemptNumber + 1).join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Retry interrupted", ie);
            }
        }

        throw translateChatException(e);
    }

    // ==================== RESPONSE WRAPPERS ====================

    @Data
    public static class EmbeddingResponse {
        private List<EmbeddingData> data;
        private String model;
        private EmbeddingUsage usage;

        @Data
        public static class EmbeddingData {
            private int index;
            private List<Double> embedding;
            private String object;
        }

        @Data
        public static class EmbeddingUsage {
            private int promptTokens;
            private int totalTokens;
        }
    }

    @Data
    public static class ImageGenerationResponse {
        private List<Image> data;
        private long created;
    }

}
