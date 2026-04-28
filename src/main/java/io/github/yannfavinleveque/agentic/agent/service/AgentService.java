package io.github.yannfavinleveque.agentic.agent.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.config.InstanceConfig;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.core.Instance;
import io.github.yannfavinleveque.agentic.agent.core.Provider;
import io.github.yannfavinleveque.agentic.agent.exception.MissingPromptVariableException;
import io.github.yannfavinleveque.agentic.agent.util.PromptTemplate;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import io.github.yannfavinleveque.agentic.agent.model.DefaultResult;
import io.github.yannfavinleveque.agentic.agent.model.FunctionCall;
import io.github.yannfavinleveque.agentic.agent.model.FunctionConfig;
import io.github.yannfavinleveque.agentic.agent.model.Message;
import io.github.yannfavinleveque.agentic.agent.model.ModelRequestOptions;
import io.github.yannfavinleveque.agentic.agent.model.ToolExecutor;
import io.github.yannfavinleveque.agentic.common.Page;
import io.github.yannfavinleveque.agentic.domain.batch.Batch;
import io.github.yannfavinleveque.agentic.domain.batch.EndpointType;
import io.github.yannfavinleveque.agentic.domain.chat.ChatMessage;
import io.github.yannfavinleveque.agentic.domain.image.ImageRequest.Quality;
import io.github.yannfavinleveque.agentic.domain.image.Size;
import io.github.yannfavinleveque.agentic.support.HttpHelper;
import io.github.yannfavinleveque.agentic.support.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * AgentService - Main entry point for AI agent operations.
 * <p>
 * Modular architecture with specialized services:
 * </p>
 * <ul>
 * <li>{@link InstanceRouter} - Load balancing and instance routing</li>
 * <li>{@link ClaudeAdapter} - Claude/Anthropic API handling</li>
 * <li>{@link BatchManager} - Batch processing</li>
 * <li>{@link AgentManager} - Agent CRUD operations</li>
 * <li>{@link UnifiedRequestService} - Stateless agent requests, embeddings, and image generation</li>
 * </ul>
 * <p>
 * <b>API:</b> Use {@link #requestAgent(String, String, List)} for all agent requests.
 * This is fully stateless and works with both OpenAI and Claude models.
 * </p>
 */
public class AgentService {

    private static final Logger logger = LoggerFactory.getLogger(AgentService.class);
    private static final String ENABLED_PROVIDERS_ENV = "ENABLED_PROVIDERS";

    private final AgentServiceConfig config;
    private final HttpHelper httpHelper;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;

    // Specialized managers
    private final InstanceRouter instanceRouter;
    private final ClaudeAdapter claudeAdapter;
    private final BatchManager batchManager;
    private final AgentManager agentManager;
    private final UnifiedRequestService unifiedRequestService;
    private final ConversationManager conversationManager;
    private final AutonomousAgentRunner autonomousRunner;

    /**
     * Constructs AgentService with the provided configuration.
     */
    public AgentService(AgentServiceConfig config) {
        // Resolve agent JSON folder path (extracts from JAR if needed)
        String resolvedPath = config.resolveAgentJsonFolderPath();

        // Create a new config with resolved path
        this.config = config.toBuilder()
                .agentJsonFolderPath(resolvedPath)
                .build();

        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Initialize HTTP helper with configured max streams per instance
        this.httpHelper = new HttpHelper(this.config.getMaxConcurrentStreamsPerInstance());

        // Parse instances from JSON config (with provider filtering)
        List<Instance> instances = parseInstances(config);

        // Initialize instance router with per-instance rate limiters
        this.instanceRouter = new InstanceRouter(instances, this.config.getRequestsPerSecond());

        // Global rate limiter (kept for backwards compatibility with some services)
        this.rateLimiter = new RateLimiter(this.config.getRequestsPerSecond());

        if (instances.isEmpty()) {
            logger.warn("⚠️ No AI instances configured. Running in DEGRADED MODE.");
            logger.warn("   Set OPENAI_INSTANCES environment variable to enable AI features.");
        } else {
            logger.info("Initialized {} AI instance(s)", instances.size());
            for (Instance inst : instances) {
                String rateLimitInfo = (inst.getRateLimits() != null && !inst.getRateLimits().isEmpty())
                        ? " rateLimits: " + inst.getRateLimits()
                        : "";
                logger.info("  - {} ({}) models: {}{}", inst.getId(), inst.getProvider(), inst.getDeployedModels(), rateLimitInfo);
            }
        }

        // Initialize specialized managers
        this.claudeAdapter = new ClaudeAdapter(httpHelper);
        this.batchManager = new BatchManager(httpHelper, instanceRouter);

        // Initialize agent manager and load definitions (use this.config which has the resolved path)
        this.agentManager = new AgentManager(this.config, httpHelper, instanceRouter, objectMapper);
        if (this.config.getAgentJsonFolderPath() != null && !this.config.getAgentJsonFolderPath().isEmpty()) {
            agentManager.loadAgentDefinitions();
        }

        // Initialize unified stateless request service (handles agent requests, embeddings, images)
        this.unifiedRequestService = new UnifiedRequestService(this.config, httpHelper, instanceRouter,
                claudeAdapter, objectMapper, agentManager);

        // Initialize conversation manager for multi-turn conversations
        this.conversationManager = new ConversationManager();

        // Initialize autonomous agent runner (pass agentManager + conversationManager,
        // then set AgentService reference to resolve circular dependency)
        this.autonomousRunner = new AutonomousAgentRunner(this.config, agentManager, conversationManager);
        this.autonomousRunner.setAgentService(this);

        logger.info("AgentService initialized successfully");
    }

    // ==================== CONVERSATION MANAGEMENT ====================

    /**
     * Creates a new conversation for multi-turn interactions.
     * <p>
     * Use this to maintain conversation history across multiple requests.
     * The conversation ID can be passed to requestAgent() to automatically
     * manage message history.
     * </p>
     *
     * <pre>{@code
     * String convId = agentService.createConversation();
     * AgentResult r1 = agentService.requestAgent("agent", "Hello", convId).join();
     * AgentResult r2 = agentService.requestAgent("agent", "Follow up", convId).join();
     * agentService.deleteConversation(convId);
     * }</pre>
     *
     * @return Unique conversation ID
     */
    public String createConversation() {
        return conversationManager.createConversation();
    }

    /**
     * Deletes a conversation and frees its memory.
     *
     * @param conversationId Conversation ID
     * @return true if conversation existed and was deleted
     */
    public boolean deleteConversation(String conversationId) {
        return conversationManager.deleteConversation(conversationId);
    }

    /**
     * Gets the number of messages in a conversation.
     *
     * @param conversationId Conversation ID
     * @return Number of messages
     */
    public int getConversationMessageCount(String conversationId) {
        return conversationManager.getMessageCount(conversationId);
    }

    /**
     * Inserts a message into an existing conversation.
     * <p>
     * Can be called while an autonomous agent is running on that conversation.
     * The message will be picked up in the next iteration of the agent loop.
     * </p>
     *
     * @param conversationId Conversation ID
     * @param role           Message role ("user", "assistant", "system")
     * @param content        Message content
     * @return the auto-generated message id (opaque, conversation-local), or {@code null} if the
     *         conversation does not exist. The id can be used with
     *         {@link #removeMessage(String, String)} for dedup/replace patterns.
     */
    public String insertMessage(String conversationId, String role, String content) {
        return conversationManager.addMessage(conversationId,
                Message.builder().role(role).content(content).build());
    }

    /**
     * Removes a previously inserted message from a conversation by its id. Intended for
     * dedup/replace patterns (e.g. mid-turn retriever-report refresh) where the caller needs to
     * drop an earlier snapshot before injecting a newer one so input tokens stay bounded. Safe to
     * call concurrently with an active autonomous loop.
     *
     * @param conversationId Conversation ID
     * @param messageId      The id returned by {@link #insertMessage(String, String, String)} (or
     *                       any message that was added via {@link ConversationManager#addMessage})
     * @return {@code true} if a message was removed; {@code false} if the conversation or id were
     *         not found
     */
    public boolean removeMessage(String conversationId, String messageId) {
        return conversationManager.removeMessage(conversationId, messageId);
    }

    /**
     * Gets the full message history of a conversation.
     * <p>
     * Useful to inspect whether injected messages were processed by an autonomous agent.
     * Returns a copy of the history to prevent external modification.
     * </p>
     *
     * @param conversationId Conversation ID
     * @return List of messages (empty list if conversation doesn't exist)
     */
    public List<Message> getConversation(String conversationId) {
        return conversationManager.getHistory(conversationId);
    }

    /**
     * Drops all messages except the {@code keepLastN} most recent ones from a
     * conversation. Intended for token-budget-based memory compaction schemes
     * where a higher-level summary (stored outside the conversation) replaces
     * the older turns. Safe to call concurrently with an active autonomous
     * loop on the same conversation — the runner re-reads history between
     * iterations.
     *
     * @param conversationId Conversation ID
     * @param keepLastN      Number of most recent messages to preserve (0 = clear all)
     * @return Number of messages removed
     */
    public int truncateConversation(String conversationId, int keepLastN) {
        return conversationManager.truncateBefore(conversationId, keepLastN);
    }

    /**
     * Drops the oldest messages from a conversation until the sum of estimated
     * tokens across the remaining messages is at most {@code maxTokens}.
     * Intended for token-budget-based memory compaction schemes where a
     * higher-level summary (stored outside the conversation) replaces the
     * dropped turns. Unlike {@link #truncateConversation(String, int)}, this
     * trims by estimated token count (chars / 4) instead of message count,
     * which is more appropriate when message sizes vary widely. Safe to call
     * concurrently with an active autonomous loop on the same conversation —
     * the runner re-reads history between iterations.
     *
     * @param conversationId Conversation ID
     * @param maxTokens      Maximum total estimated tokens to keep (0 or negative = clear all)
     * @return Number of messages removed
     */
    public int truncateConversationByTokenBudget(String conversationId, int maxTokens) {
        return conversationManager.truncateByTokenBudget(conversationId, maxTokens);
    }

    // ==================== AGENT MANAGEMENT ====================

    /**
     * Gets an agent by ID.
     *
     * @param agentId Agent ID
     * @return Agent
     * @throws io.github.yannfavinleveque.agentic.agent.exception.AgentNotFoundException if not found
     */
    public Agent getAgent(String agentId) {
        return agentManager.getAgent(agentId);
    }

    /**
     * Gets all loaded agents.
     *
     * @return Unmodifiable map of agent ID to Agent
     */
    public Map<String, Agent> getAllAgents() {
        return agentManager.getAllAgents();
    }

    /**
     * Registers an agent.
     */
    public void registerAgent(Agent agent) {
        agentManager.registerAgent(agent);
    }

    /**
     * Registers (or clears with null) a listener invoked once per iteration of the autonomous
     * agent loop, immediately after each LLM call returns. Useful for per-iteration observability
     * (token counts, durations, function-call previews).
     */
    public void setAutonomousIterationListener(AutonomousIterationListener listener) {
        autonomousRunner.setIterationListener(listener);
    }

    /**
     * Lists all registered agents.
     */
    public List<Agent> listAgents() {
        return agentManager.listAgents();
    }

    /**
     * Modifies an existing agent's configuration.
     *
     * @param agentId Agent ID
     * @param updates Map of field names to new values
     * @return CompletableFuture with the updated Agent
     */
    public CompletableFuture<Agent> modifyAgent(String agentId, Map<String, Object> updates) {
        return agentManager.modifyAgent(agentId, updates);
    }

    /**
     * Reloads all agent definitions from JSON files.
     */
    public CompletableFuture<Void> reloadAgents() {
        return agentManager.reloadAgents();
    }

    /**
     * Reloads a specific agent definition from its JSON file.
     *
     * @param agentId ID of the agent to reload
     * @return CompletableFuture that completes when agent is reloaded
     */
    public CompletableFuture<Void> reloadAgent(String agentId) {
        return agentManager.reloadAgent(agentId);
    }

    /**
     * Replaces the function list of a registered agent AND propagates the change (with the same
     * group filter as {@link AutonomousAgentRunner#buildVirtualAgent(Agent)}) to every active
     * autonomous virtual child of that parent — i.e. any registered agent whose id starts with
     * {@code parentAgentId + "-autonomous-"}. The new list takes effect on the NEXT iteration of
     * any in-flight autonomous loop; already-issued LLM HTTP requests are not affected.
     * <p>
     * Thread-safe: mutations are guarded by a per-{@link Agent} {@code synchronized} block so a
     * concurrent loop reading {@link Agent#getFunctions()} always sees a consistent list. An
     * iteration that was already in flight may ship the previous list — the iteration AFTER picks
     * up the change. No retroactive editing of open HTTP streams.
     *
     * @param parentAgentId the registered (non-virtual) agent id, e.g. "conscient-mini"
     * @param newFunctions  the full replacement function list; the per-child filter re-applies
     *                      {@code enabledToolGroups} from the parent before assignment
     * @return number of agents updated (parent + children); {@code 0} if the parent is unknown
     */
    public int updateAgentFunctions(String parentAgentId, List<FunctionConfig> newFunctions) {
        Agent parent;
        try {
            parent = agentManager.getAgent(parentAgentId);
        } catch (Exception e) {
            logger.debug("updateAgentFunctions: unknown parent '{}' — noop", parentAgentId);
            return 0;
        }
        if (parent == null) return 0;

        int updated = 0;
        synchronized (parent) {
            parent.setFunctions(newFunctions);
            updated++;
        }

        String prefix = parentAgentId + "-autonomous-";
        for (Map.Entry<String, Agent> e : agentManager.getAllAgents().entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            Agent child = e.getValue();
            List<FunctionConfig> filtered = AutonomousAgentRunner.applyGroupFilter(parent, newFunctions);
            autonomousRunner.maybeInjectTaskOver(child, parent, filtered);
            synchronized (child) {
                child.setFunctions(filtered);
            }
            updated++;
        }

        logger.debug("updateAgentFunctions '{}': updated {} agents ({} children)",
                parentAgentId, updated, updated - 1);
        return updated;
    }

    // ==================== AGENT REQUESTS ====================

    /**
     * Sends a request to an agent with conversation context.
     * <p>
     * The conversation history is automatically managed. Pass a conversationId
     * from {@link #createConversation()} to maintain multi-turn context.
     * </p>
     *
     * <pre>{@code
     * // Multi-turn conversation
     * String convId = agentService.createConversation();
     * AgentResult r1 = agentService.requestAgent("agent", "Hello", convId).join();
     * AgentResult r2 = agentService.requestAgent("agent", "Follow up", convId).join();
     * agentService.deleteConversation(convId);
     * }</pre>
     *
     * @param agentId        Agent ID
     * @param userMessage    Current user message
     * @param conversationId Conversation ID (from createConversation()), or null for single-turn
     * @return CompletableFuture with the agent's response as AgentResult
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, String conversationId) {
        return requestAgent(agentId, userMessage, conversationId, (Map<String, Object>) null);
    }

    /**
     * Same as {@link #requestAgent(String, String, String)} but with Mustache prompt variables.
     * <p>
     * Any {@code {{name}}} placeholder in the agent's {@code instructions} is replaced with
     * {@code String.valueOf(promptVars.get("name"))} before the system prompt is sent to the LLM.
     * If a referenced variable is missing (or its value is {@code null}), throws
     * {@link MissingPromptVariableException}. {@code userMessage} and {@code history} are
     * NOT scanned — the user is free to use {@code {{...}}} literally in their messages.
     *
     * @param agentId        Agent ID
     * @param userMessage    Current user message
     * @param conversationId Conversation ID, or null for single-turn
     * @param promptVars     Variables to substitute into the agent's instructions (may be null/empty)
     * @return CompletableFuture with the agent's response
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, String conversationId,
            Map<String, Object> promptVars) {
        // If agent is autonomous, delegate to the autonomous runner (config-based executors)
        Agent agent = agentManager.getAgent(agentId);
        Agent resolvedAgent = resolveAgentInstructions(agent, promptVars);
        if (Boolean.TRUE.equals(resolvedAgent.getAutonomous())) {
            return autonomousRunner.run(resolvedAgent, userMessage, conversationId, null);
        }

        List<Message> history = conversationManager.getHistory(conversationId);

        // Add user message to conversation before request
        conversationManager.addUserMessage(conversationId, userMessage);

        return unifiedRequestService.requestAgent(resolvedAgent, userMessage, history)
                .whenComplete((result, error) -> {
                    if (error == null && result != null) {
                        // Never store null content (causes HTTP 400 on next API call)
                        String content = result.getContent() != null ? result.getContent() : "";
                        // Build function call summary if present
                        if (result.hasFunctionCalls()) {
                            StringBuilder toolSummary = new StringBuilder();
                            for (FunctionCall call : result.getFunctionCalls()) {
                                if (toolSummary.length() > 0) toolSummary.append("\n");
                                toolSummary.append("[Tool call: ").append(call.getName())
                                        .append("(").append(call.getArguments() != null ? call.getArguments() : "").append(")]");
                            }
                            content = content.isEmpty() ? toolSummary.toString() : content + "\n" + toolSummary;
                        }
                        conversationManager.addAssistantMessage(conversationId, content);
                    }
                });
    }

    /**
     * Sends a request to an agent with manual conversation history.
     * <p>
     * Use this when you want to manage the conversation history yourself.
     * For automatic history management, use {@link #createConversation()} and
     * pass the conversationId instead.
     * </p>
     *
     * @param agentId     Agent ID
     * @param userMessage Current user message
     * @param history     Previous conversation messages (can be null or empty)
     * @return CompletableFuture with the agent's response as AgentResult
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, List<Message> history) {
        return requestAgent(agentId, userMessage, history, (Map<String, Object>) null);
    }

    /**
     * Same as {@link #requestAgent(String, String, List)} but with Mustache prompt variables.
     * See {@link #requestAgent(String, String, String, Map)} for the substitution semantics.
     *
     * @param agentId     Agent ID
     * @param userMessage Current user message
     * @param history     Previous conversation messages (can be null or empty)
     * @param promptVars  Variables to substitute into the agent's instructions (may be null/empty)
     * @return CompletableFuture with the agent's response
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, List<Message> history,
            Map<String, Object> promptVars) {
        Agent agent = agentManager.getAgent(agentId);
        Agent resolvedAgent = resolveAgentInstructions(agent, promptVars);
        return unifiedRequestService.requestAgent(resolvedAgent, userMessage, history);
    }

    /**
     * Sends a single-turn request to an agent (no conversation history).
     *
     * @param agentId     Agent ID
     * @param userMessage User message
     * @return CompletableFuture with the agent's response as AgentResult
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage) {
        return requestAgent(agentId, userMessage, (Map<String, Object>) null);
    }

    /**
     * Same as {@link #requestAgent(String, String)} but with Mustache prompt variables.
     * See {@link #requestAgent(String, String, String, Map)} for the substitution semantics.
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage,
            Map<String, Object> promptVars) {
        // If agent is autonomous, delegate to the autonomous runner (config-based executors)
        Agent agent = agentManager.getAgent(agentId);
        Agent resolvedAgent = resolveAgentInstructions(agent, promptVars);
        if (Boolean.TRUE.equals(resolvedAgent.getAutonomous())) {
            return autonomousRunner.run(resolvedAgent, userMessage, null, null);
        }
        return unifiedRequestService.requestAgent(resolvedAgent, userMessage, (List<Message>) null);
    }

    /**
     * Sends a request to an agent with a single image (vision).
     *
     * <pre>{@code
     * AgentResult result = agentService.requestAgentVision("vision-agent", "What's in this image?", imageBase64)
     *     .join();
     * }</pre>
     *
     * @param agentId      Agent ID
     * @param userMessage  Text prompt for the image
     * @param imageBase64  Base64-encoded image (PNG format assumed)
     * @return CompletableFuture with the agent's response
     */
    public CompletableFuture<AgentResult> requestAgentVision(String agentId, String userMessage, String imageBase64) {
        return requestAgentVision(agentId, userMessage, imageBase64, (Map<String, Object>) null);
    }

    /**
     * Same as {@link #requestAgentVision(String, String, String)} but with Mustache prompt
     * variables. See {@link #requestAgent(String, String, String, Map)} for the semantics.
     *
     * @param agentId      Agent ID
     * @param userMessage  Text prompt for the image
     * @param imageBase64  Base64-encoded image
     * @param promptVars   Variables to substitute into the agent's instructions (may be null/empty)
     */
    public CompletableFuture<AgentResult> requestAgentVision(String agentId, String userMessage, String imageBase64,
            Map<String, Object> promptVars) {
        Agent agent = agentManager.getAgent(agentId);
        Agent resolvedAgent = resolveAgentInstructions(agent, promptVars);
        return unifiedRequestService.requestAgent(resolvedAgent, userMessage, null,
                imageBase64 != null ? List.of(imageBase64) : null);
    }

    /**
     * Sends a request to an agent with conversation context and images (vision).
     *
     * <pre>{@code
     * // Multi-turn with images
     * String convId = agentService.createConversation();
     * List<String> images = List.of(image1Base64, image2Base64);
     * AgentResult result = agentService.requestAgent("vision-agent", "Compare these", convId, images)
     *     .join();
     * }</pre>
     *
     * @param agentId        Agent ID
     * @param userMessage    Text prompt
     * @param conversationId Conversation ID (from createConversation()), or null for single-turn
     * @param imagesBase64   List of base64-encoded images (can be null)
     * @return CompletableFuture with the agent's response
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, String conversationId,
            List<String> imagesBase64) {
        return requestAgent(agentId, userMessage, conversationId, imagesBase64, null);
    }

    /**
     * Same as {@link #requestAgent(String, String, String, List)} but with Mustache prompt
     * variables. See {@link #requestAgent(String, String, String, Map)} for the semantics.
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, String conversationId,
            List<String> imagesBase64, Map<String, Object> promptVars) {
        Agent agent = agentManager.getAgent(agentId);
        Agent resolvedAgent = resolveAgentInstructions(agent, promptVars);

        List<Message> history = conversationManager.getHistory(conversationId);

        // Add user message with images to conversation
        conversationManager.addUserMessageWithImages(conversationId, userMessage, imagesBase64);

        return unifiedRequestService.requestAgent(resolvedAgent, userMessage, history, imagesBase64)
                .whenComplete((result, error) -> {
                    if (error == null && result != null) {
                        String content = result.getContent() != null ? result.getContent() : "";
                        if (result.hasFunctionCalls()) {
                            StringBuilder toolSummary = new StringBuilder();
                            for (FunctionCall call : result.getFunctionCalls()) {
                                if (toolSummary.length() > 0) toolSummary.append("\n");
                                toolSummary.append("[Tool call: ").append(call.getName())
                                        .append("(").append(call.getArguments() != null ? call.getArguments() : "").append(")]");
                            }
                            content = content.isEmpty() ? toolSummary.toString() : content + "\n" + toolSummary;
                        }
                        conversationManager.addAssistantMessage(conversationId, content);
                    }
                });
    }

    /**
     * Sends a request to an agent with manual history and images (vision).
     * <p>
     * Use this when you want to manage the conversation history yourself.
     * </p>
     *
     * @param agentId      Agent ID
     * @param userMessage  Text prompt
     * @param history      Previous conversation messages (can be null)
     * @param imagesBase64 List of base64-encoded images (can be null)
     * @return CompletableFuture with the agent's response
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, List<Message> history,
            List<String> imagesBase64) {
        return requestAgent(agentId, userMessage, history, imagesBase64, null);
    }

    /**
     * Same as {@link #requestAgent(String, String, List, List)} but with Mustache prompt
     * variables. See {@link #requestAgent(String, String, String, Map)} for the semantics.
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, List<Message> history,
            List<String> imagesBase64, Map<String, Object> promptVars) {
        Agent agent = agentManager.getAgent(agentId);
        Agent resolvedAgent = resolveAgentInstructions(agent, promptVars);
        return unifiedRequestService.requestAgent(resolvedAgent, userMessage, history, imagesBase64);
    }

    // ==================== AUTONOMOUS AGENT REQUESTS ====================

    /**
     * Sends a request to an autonomous agent with a tool executor and conversation context.
     * <p>
     * When the agent is configured with {@code autonomous=true}, the library handles the
     * full tool-calling loop internally: request agent, execute tools via the provided
     * {@code ToolExecutor}, send results back, repeat until the agent calls {@code task_over}.
     * </p>
     * <p>
     * If the agent is NOT autonomous, falls back to a normal single-turn request
     * (toolExecutor is ignored).
     * </p>
     *
     * <pre>{@code
     * AgentResult result = agentService.requestAgent("classifier", "Classify this item",
     *         conversationId,
     *         toolCall -> switch (toolCall.getName()) {
     *             case "lookup_category" -> categoryService.lookup(toolCall.getArgumentsAs(LookupParams.class));
     *             case "validate" -> validator.validate(toolCall.getArguments());
     *             default -> "Unknown tool: " + toolCall.getName();
     *         }).join();
     * }</pre>
     *
     * @param agentId        Agent ID
     * @param userMessage    Initial user message
     * @param conversationId Conversation ID (from createConversation()), or null for internal management
     * @param toolExecutor   Implementation that executes tool calls
     * @return CompletableFuture with the final result (typically typed via resultClass)
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage,
                                                       String conversationId, ToolExecutor toolExecutor) {
        return requestAgent(agentId, userMessage, conversationId, toolExecutor, null);
    }

    /**
     * Same as {@link #requestAgent(String, String, String, ToolExecutor)} but with Mustache
     * prompt variables. See {@link #requestAgent(String, String, String, Map)} for semantics.
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage,
                                                       String conversationId, ToolExecutor toolExecutor,
                                                       Map<String, Object> promptVars) {
        Agent agent = agentManager.getAgent(agentId);
        Agent resolvedAgent = resolveAgentInstructions(agent, promptVars);
        if (!Boolean.TRUE.equals(resolvedAgent.getAutonomous())) {
            // Not autonomous - fall back to normal request, ignore toolExecutor
            return requestAgent(agentId, userMessage, conversationId, promptVars);
        }
        return autonomousRunner.run(resolvedAgent, userMessage, conversationId, toolExecutor);
    }

    /**
     * Sends a request to an autonomous agent with a tool executor (no conversation context).
     * <p>
     * Convenience overload that creates and manages a conversation internally.
     * </p>
     *
     * @param agentId      Agent ID
     * @param userMessage  Initial user message
     * @param toolExecutor Implementation that executes tool calls
     * @return CompletableFuture with the final result
     * @see #requestAgent(String, String, String, ToolExecutor)
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage,
                                                       ToolExecutor toolExecutor) {
        return requestAgent(agentId, userMessage, (String) null, toolExecutor);
    }

    // ==================== DIRECT MODEL REQUESTS ====================

    /**
     * Sends a direct request to a model (without agent configuration).
     * <p>
     * Use this method when you want to call a model directly without registering an agent.
     * All options (structured output, web search, code interpreter, vision, history) are
     * configured via {@link ModelRequestOptions}.
     * </p>
     *
     * <p>
     * Example:
     * </p>
     *
     * <pre>{@code
     * // Simple request
     * AgentResult result = agentService.requestModel("gpt-4o", "Hello!").join();
     *
     * // With structured output
     * ModelRequestOptions options = ModelRequestOptions.builder()
     *         .resultClass(WeatherResult.class)
     *         .build();
     * WeatherResult result = (WeatherResult) agentService.requestModel("gpt-4o", "What's the weather?", options).join();
     *
     * // With web search
     * ModelRequestOptions options = ModelRequestOptions.withWebSearch();
     * AgentResult result = agentService.requestModel("gpt-4o", "Latest news?", options).join();
     *
     * // With vision (single image)
     * ModelRequestOptions options = ModelRequestOptions.builder()
     *         .image(imageBase64)
     *         .build();
     * AgentResult result = agentService.requestModel("gpt-4o", "What's in this image?", options).join();
     *
     * // With conversation management (automatic history)
     * String convId = agentService.createConversation();
     * ModelRequestOptions options = ModelRequestOptions.builder()
     *         .conversationId(convId)
     *         .build();
     * AgentResult r1 = agentService.requestModel("gpt-4o", "Hello!", options).join();
     * AgentResult r2 = agentService.requestModel("gpt-4o", "Follow up", options).join();
     * agentService.deleteConversation(convId);
     * }</pre>
     *
     * @param model       Model name (e.g., "gpt-4o", "claude-sonnet-4-5")
     * @param userMessage Current user message
     * @param options     Request options (can be null for defaults)
     * @return CompletableFuture with the model's response
     */
    public CompletableFuture<AgentResult> requestModel(String model, String userMessage, ModelRequestOptions options) {
        // Handle conversationId if present
        if (options != null && options.getConversationId() != null) {
            String conversationId = options.getConversationId();
            List<Message> history = conversationManager.getHistory(conversationId);

            // Add user message to conversation before request
            if (options.getImages() != null && !options.getImages().isEmpty()) {
                conversationManager.addUserMessageWithImages(conversationId, userMessage, options.getImages());
            } else if (options.getImage() != null) {
                conversationManager.addUserMessageWithImages(conversationId, userMessage, List.of(options.getImage()));
            } else {
                conversationManager.addUserMessage(conversationId, userMessage);
            }

            // Create new options with resolved history (without conversationId to avoid double processing)
            ModelRequestOptions resolvedOptions = ModelRequestOptions.builder()
                    .resultClass(options.getResultClass())
                    .resultSchema(options.getResultSchema())
                    .schemaName(options.getSchemaName())
                    .webSearch(options.isWebSearch())
                    .codeInterpreter(options.isCodeInterpreter())
                    .instructions(options.getInstructions())
                    .temperature(options.getTemperature())
                    .maxTokens(options.getMaxTokens())
                    .image(options.getImage())
                    .images(options.getImages())
                    .history(history)  // Use resolved history
                    .build();

            return unifiedRequestService.requestModel(model, userMessage, resolvedOptions)
                    .whenComplete((result, error) -> {
                        if (error == null && result != null) {
                            String content = result.getContent() != null ? result.getContent() : "";
                            if (result.hasFunctionCalls()) {
                                StringBuilder toolSummary = new StringBuilder();
                                for (FunctionCall call : result.getFunctionCalls()) {
                                    if (toolSummary.length() > 0) toolSummary.append("\n");
                                    toolSummary.append("[Tool call: ").append(call.getName())
                                            .append("(").append(call.getArguments() != null ? call.getArguments() : "").append(")]");
                                }
                                content = content.isEmpty() ? toolSummary.toString() : content + "\n" + toolSummary;
                            }
                            conversationManager.addAssistantMessage(conversationId, content);
                        }
                    });
        }

        return unifiedRequestService.requestModel(model, userMessage, options);
    }

    /**
     * Sends a direct request to a model with default options.
     *
     * @param model       Model name
     * @param userMessage User message
     * @return CompletableFuture with the model's response
     */
    public CompletableFuture<AgentResult> requestModel(String model, String userMessage) {
        return unifiedRequestService.requestModel(model, userMessage);
    }

    // ==================== CHAT COMPLETIONS ====================

    /**
     * Executes a chat completion (string response).
     *
     * @param model       Model name
     * @param messages    Chat messages
     * @param temperature Temperature (optional)
     * @return Response content
     */
    public CompletableFuture<String> requestChatCompletion(String model, List<ChatMessage> messages,
            Double temperature) {
        return unifiedRequestService.requestChatCompletion(model, messages, temperature);
    }

    /**
     * Executes a chat completion with structured output.
     *
     * @param model       Model name
     * @param messages    Chat messages
     * @param temperature Temperature (optional)
     * @param resultClass Result class for typed response
     * @return Typed result
     */
    public <T extends AgentResult> CompletableFuture<T> chatCompletion(String model,
            List<ChatMessage> messages,
            Double temperature,
            Class<T> resultClass) {
        return unifiedRequestService.chatCompletion(model, messages, temperature, resultClass);
    }

    /**
     * Executes a chat completion without structured output.
     */
    public CompletableFuture<DefaultResult> chatCompletion(String model, List<ChatMessage> messages,
            Double temperature) {
        return unifiedRequestService.chatCompletion(model, messages, temperature);
    }

    /**
     * Executes a chat completion with structured output by class name.
     *
     * @param model           Model name
     * @param messages        Chat messages
     * @param temperature     Temperature
     * @param resultClassName Simple class name (e.g., "WeatherResult")
     * @return CompletableFuture with typed result
     */
    public <T extends AgentResult> CompletableFuture<T> chatCompletion(String model,
            List<ChatMessage> messages,
            Double temperature,
            String resultClassName) {
        return unifiedRequestService.chatCompletion(model, messages, temperature,
                resultClassName, config.getAgentResultClassPackage());
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
        return unifiedRequestService.generateEmbedding(text, model);
    }

    /**
     * Generates embeddings using default model (text-embedding-3-small).
     */
    public CompletableFuture<float[]> generateEmbedding(String text) {
        return unifiedRequestService.generateEmbedding(text);
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
        return unifiedRequestService.requestEmbedding(text, model);
    }

    /**
     * Alias for {@link #generateEmbedding(String)} for API consistency.
     */
    public CompletableFuture<float[]> requestEmbedding(String text) {
        return unifiedRequestService.requestEmbedding(text);
    }

    /**
     * Generates embeddings for multiple texts in ONE batch API call. Much more efficient than multiple
     * generateEmbedding() calls.
     *
     * @param texts List of texts to embed (max 2048 per OpenAI limits)
     * @param model Embedding model
     * @return List of float arrays (same order as input texts)
     */
    public CompletableFuture<List<float[]>> generateEmbeddingsBatch(List<String> texts, String model) {
        return unifiedRequestService.generateEmbeddingsBatch(texts, model);
    }

    /**
     * Generates batch embeddings using default model.
     */
    public CompletableFuture<List<float[]>> generateEmbeddingsBatch(List<String> texts) {
        return unifiedRequestService.generateEmbeddingsBatch(texts);
    }

    /**
     * Alias for {@link #generateEmbeddingsBatch(List, String)} for API consistency.
     *
     * @param texts List of texts to embed
     * @param model Embedding model
     * @return List of float arrays
     */
    public CompletableFuture<List<float[]>> requestEmbeddings(List<String> texts, String model) {
        return unifiedRequestService.requestEmbeddings(texts, model);
    }

    /**
     * Alias for {@link #generateEmbeddingsBatch(List)} for API consistency.
     */
    public CompletableFuture<List<float[]>> requestEmbeddings(List<String> texts) {
        return unifiedRequestService.requestEmbeddings(texts);
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
        return unifiedRequestService.generateImage(prompt, model, size, quality);
    }

    /**
     * Generates an image with default settings (dall-e-3, 1024x1024, standard quality).
     */
    public CompletableFuture<String> generateImage(String prompt) {
        return unifiedRequestService.generateImage(prompt);
    }

    /**
     * Alias for {@link #generateImage(String, String, Size, Quality)} for API consistency.
     * Use requestImage/requestModel/requestAgent/requestEmbedding for a consistent API.
     *
     * @param prompt  Image description
     * @param model   Model name (e.g., "dall-e-3")
     * @param size    Image size
     * @param quality Image quality
     * @return Base64-encoded image data
     */
    public CompletableFuture<String> requestImage(String prompt, String model, Size size, Quality quality) {
        return unifiedRequestService.generateImage(prompt, model, size, quality);
    }

    /**
     * Alias for {@link #generateImage(String)} for API consistency.
     */
    public CompletableFuture<String> requestImage(String prompt) {
        return unifiedRequestService.generateImage(prompt);
    }

    /**
     * Edits/transforms an existing image based on a text prompt. For gpt-image-1: Uses image-to-image
     * editing capabilities. For dall-e-2: Supports masked editing with transparent areas. Note:
     * dall-e-3 does not support image editing.
     *
     * @param imageBase64 Base64-encoded input image (PNG format)
     * @param prompt      Text description of desired changes/evolution
     * @param model       Model to use (gpt-image-1 or dall-e-2)
     * @param size        Output image size
     * @param quality     Output quality (STANDARD or HD)
     * @return Base64-encoded edited image
     */
    public CompletableFuture<String> editImage(String imageBase64, String prompt, String model, Size size,
            Quality quality) {
        return unifiedRequestService.editImage(imageBase64, prompt, model, size, quality);
    }

    /**
     * Edits an image with default settings (gpt-image-1, 1024x1024, standard quality).
     */
    public CompletableFuture<String> editImage(String imageBase64, String prompt) {
        return unifiedRequestService.editImage(imageBase64, prompt);
    }

    /**
     * Alias for {@link #editImage(String, String, String, Size, Quality)} for API consistency.
     * Use requestImageEdit for edit operations, requestImage for generation.
     *
     * @param imageBase64 Base64-encoded input image (PNG format)
     * @param prompt      Text description of desired changes
     * @param model       Model to use (gpt-image-1 or dall-e-2)
     * @param size        Output image size
     * @param quality     Output quality
     * @return Base64-encoded edited image
     */
    public CompletableFuture<String> requestImageEdit(String imageBase64, String prompt, String model, Size size,
            Quality quality) {
        return unifiedRequestService.editImage(imageBase64, prompt, model, size, quality);
    }

    /**
     * Alias for {@link #editImage(String, String)} for API consistency.
     */
    public CompletableFuture<String> requestImageEdit(String imageBase64, String prompt) {
        return unifiedRequestService.editImage(imageBase64, prompt);
    }

    // ==================== BATCH OPERATIONS ====================

    /**
     * Creates a batch.
     * 
     * @see BatchManager#createBatch(String, EndpointType, Map)
     */
    public CompletableFuture<Batch> createBatch(String inputFileId, EndpointType endpoint,
            Map<String, String> metadata) {
        return batchManager.createBatch(inputFileId, endpoint, metadata);
    }

    /**
     * Creates a chat completion batch.
     */
    public CompletableFuture<Batch> createChatCompletionBatch(String inputFileId,
            Map<String, String> metadata) {
        return batchManager.createChatCompletionBatch(inputFileId, metadata);
    }

    /**
     * Gets batch status.
     * 
     * @see BatchManager#getBatch(String)
     */
    public CompletableFuture<Batch> getBatch(String batchId) {
        return batchManager.getBatch(batchId);
    }

    /**
     * Cancels a batch.
     * 
     * @see BatchManager#cancelBatch(String)
     */
    public CompletableFuture<Batch> cancelBatch(String batchId) {
        return batchManager.cancelBatch(batchId);
    }

    /**
     * Lists batches.
     * 
     * @see BatchManager#listBatches(Integer, String)
     */
    public CompletableFuture<Page<Batch>> listBatches(Integer limit, String after) {
        return batchManager.listBatches(limit, after);
    }

    /**
     * Polls batch until complete.
     * 
     * @see BatchManager#pollUntilComplete(String, long, long)
     */
    public CompletableFuture<Batch> pollBatchUntilComplete(String batchId, long pollIntervalSeconds,
            long timeoutSeconds) {
        return batchManager.pollUntilComplete(batchId, pollIntervalSeconds, timeoutSeconds);
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Gets an instance by ID.
     */
    public Instance getInstance(String instanceId) {
        return instanceRouter.getInstanceById(instanceId);
    }

    /**
     * Gets the HTTP helper for direct API access.
     */
    public HttpHelper getHttpHelper() {
        return httpHelper;
    }

    /**
     * Gets the instance router.
     */
    public InstanceRouter getInstanceRouter() {
        return instanceRouter;
    }

    /**
     * Checks if running in degraded mode.
     */
    public boolean isDegradedMode() {
        return instanceRouter.isDegradedMode();
    }

    /**
     * Gets the number of configured instances.
     */
    public int getInstanceCount() {
        return instanceRouter.getInstanceCount();
    }

    /**
     * Gets all available models.
     */
    public List<String> getAvailableModels() {
        return instanceRouter.getAvailableModels();
    }

    /**
     * Validates that a response is complete and not truncated.
     *
     * @param response Response string
     * @return true if response appears complete
     */
    public boolean responseOk(String response) {
        if (response == null || response.trim().isEmpty()) {
            return false;
        }

        String trimmed = response.trim();

        // Check for JSON completeness
        if (trimmed.startsWith("{")) {
            int openBraces = 0;
            for (char c : trimmed.toCharArray()) {
                if (c == '{')
                    openBraces++;
                if (c == '}')
                    openBraces--;
            }
            return openBraces == 0;
        }

        if (trimmed.startsWith("[")) {
            int openBrackets = 0;
            for (char c : trimmed.toCharArray()) {
                if (c == '[')
                    openBrackets++;
                if (c == ']')
                    openBrackets--;
            }
            return openBrackets == 0;
        }

        // For non-JSON responses, consider them OK if not empty
        return true;
    }

    /**
     * Maps a JSON response to a typed agent result.
     *
     * @param <T>          Result type
     * @param jsonResponse JSON response string
     * @param resultClass  Result class name
     * @return Typed result instance
     */
    public <T extends AgentResult> T mapResponse(String jsonResponse, String resultClass) {
        try {
            String fullClassName = config.resolveResultClassName(resultClass);
            if (fullClassName == null) {
                throw new IllegalStateException("Cannot resolve result class '" + resultClass +
                        "' - use FQCN or configure agentResultClassPackage");
            }

            @SuppressWarnings("unchecked")
            Class<T> clazz = (Class<T>) Class.forName(fullClassName);

            return AgentResult.jsonMapper(jsonResponse, clazz);

        } catch (ClassNotFoundException e) {
            logger.error("Result class not found: {}", resultClass, e);
            throw new RuntimeException("Result class not found: " + resultClass, e);
        }
    }

    /**
     * Shuts down the service.
     */
    public void shutdown() {
        logger.info("Shutting down AgentService");
    }

    // ==================== PRIVATE METHODS ====================

    /**
     * Returns an {@link Agent} whose {@code instructions} field has had every
     * {@code {{name}}} placeholder substituted from {@code promptVars}.
     * <p>
     * If the original instructions contain no placeholders OR {@code promptVars}
     * is null/empty AND the template contains no placeholders, the original
     * agent is returned unchanged (avoids needless cloning).
     * <p>
     * If the template contains placeholders, a new {@link Agent} is produced via
     * {@code toBuilder()} so the registered agent shared with other concurrent
     * callers is never mutated.
     *
     * @throws MissingPromptVariableException if a referenced variable is missing
     */
    private Agent resolveAgentInstructions(Agent agent, Map<String, Object> promptVars) {
        if (agent == null || agent.getInstructions() == null || agent.getInstructions().isEmpty()) {
            return agent;
        }
        // Fast path: no placeholders in the template → no work, no clone.
        if (PromptTemplate.extractVariables(agent.getInstructions()).isEmpty()) {
            return agent;
        }
        String rendered = PromptTemplate.render(agent.getInstructions(), promptVars, agent.getId());
        return agent.toBuilder().instructions(rendered).build();
    }

    /**
     * Gets the set of allowed providers from the environment variable or system property.
     * Checks System.getProperty first (for testing), then System.getenv.
     *
     * @return Set of allowed provider names (lowercase), or empty set if no filter
     */
    private Set<String> getAllowedProviders() {
        // Check system property first (useful for testing)
        String providersEnv = System.getProperty(ENABLED_PROVIDERS_ENV);
        if (providersEnv == null || providersEnv.trim().isEmpty()) {
            // Fall back to environment variable
            providersEnv = System.getenv(ENABLED_PROVIDERS_ENV);
        }
        if (providersEnv == null || providersEnv.trim().isEmpty()) {
            return Set.of();  // Empty = all allowed
        }
        return Arrays.stream(providersEnv.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Checks if an instance's provider is allowed by the filter.
     */
    private boolean isProviderAllowed(InstanceConfig instanceConfig, Set<String> allowedProviders) {
        if (allowedProviders.isEmpty()) {
            return true;  // No filter = all allowed
        }
        String provider = instanceConfig.getProvider().toLowerCase();
        if (allowedProviders.contains(provider)) {
            return true;
        }
        // Handle aliases
        if (instanceConfig.isAzureOpenAI() &&
                (allowedProviders.contains("azure") || allowedProviders.contains("azure-openai"))) {
            return true;
        }
        if (instanceConfig.isAzureAnthropic() && allowedProviders.contains("azure-anthropic")) {
            return true;
        }
        if (instanceConfig.isOpenAI() && allowedProviders.contains("openai")) {
            return true;
        }
        if (instanceConfig.isAnthropic() && allowedProviders.contains("anthropic")) {
            return true;
        }
        if (instanceConfig.isMistral() && allowedProviders.contains("mistral")) {
            return true;
        }
        if (instanceConfig.isAzureMistral() && allowedProviders.contains("azure-mistral")) {
            return true;
        }
        if (instanceConfig.isCustom() && allowedProviders.contains("custom")) {
            return true;
        }
        return false;
    }

    /**
     * Parses instances from the configuration with provider filtering.
     */
    private List<Instance> parseInstances(AgentServiceConfig config) {
        List<Instance> result = new ArrayList<>();

        if (!config.isUsingJsonConfig()) {
            return result;
        }

        List<InstanceConfig> instanceConfigs = config.parseInstances();
        Set<String> allowedProviders = getAllowedProviders();

        // Filter by enabled AND allowed providers
        List<InstanceConfig> enabledInstances = instanceConfigs.stream()
                .filter(InstanceConfig::isEnabled)
                .filter(ic -> isProviderAllowed(ic, allowedProviders))
                .collect(Collectors.toList());

        logger.info("Loaded {} instance(s) from JSON configuration ({} total, {} after filtering)",
                enabledInstances.size(), instanceConfigs.size(), enabledInstances.size());
        if (!allowedProviders.isEmpty()) {
            logger.info("Provider filter active: {}", allowedProviders);
        }

        for (InstanceConfig ic : enabledInstances) {
            Provider providerType;
            if (ic.isAzureAnthropic()) {
                providerType = Provider.AZURE_ANTHROPIC;
            } else if (ic.isAnthropic()) {
                providerType = Provider.ANTHROPIC;
            } else if (ic.isAzureOpenAI()) {
                providerType = Provider.AZURE_OPENAI;
            } else if (ic.isAzureMistral()) {
                providerType = Provider.AZURE_MISTRAL;
            } else if (ic.isMistral()) {
                providerType = Provider.MISTRAL;
            } else if (ic.isCustom()) {
                providerType = Provider.CUSTOM;
            } else {
                providerType = Provider.OPENAI;
            }

            String baseUrl = ic.getUrl();
            if (baseUrl != null && baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }

            Instance instance = Instance.builder()
                    .id(ic.getId())
                    .baseUrl(baseUrl)
                    .apiKey(ic.getKey())
                    .provider(providerType)
                    .azureApiVersion(ic.getApiVersion())
                    .deployedModels(ic.getModelsList())
                    .rateLimits(ic.getRateLimits() != null ? ic.getRateLimits() : java.util.Collections.emptyMap())
                    .customSpec(ic.getCustom())
                    .build();

            result.add(instance);
        }

        return result;
    }

}
