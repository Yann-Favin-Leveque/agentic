package io.github.yannfavinleveque.agentic.agent.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.model.DefaultResult;
import io.github.yannfavinleveque.agentic.support.HttpHelper;
import io.github.yannfavinleveque.agentic.agent.core.Instance;
import io.github.yannfavinleveque.agentic.agent.config.InstanceConfig;
import io.github.yannfavinleveque.agentic.agent.core.Provider;
import io.github.yannfavinleveque.agentic.agent.exception.AgentException;
import io.github.yannfavinleveque.agentic.common.Page;
import io.github.yannfavinleveque.agentic.domain.assistant.Assistant;
import io.github.yannfavinleveque.agentic.domain.batch.Batch;
import io.github.yannfavinleveque.agentic.domain.batch.EndpointType;
import io.github.yannfavinleveque.agentic.domain.chat.ChatMessage;
import io.github.yannfavinleveque.agentic.domain.image.ImageRequest.Quality;
import io.github.yannfavinleveque.agentic.domain.image.Size;
import io.github.yannfavinleveque.agentic.support.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * AgentService - Refactored agent service with modular architecture.
 *
 * This is a cleaner implementation that separates concerns:
 * - {@link InstanceRouter} - Load balancing and instance routing
 * - {@link ThreadManager} - Thread lifecycle and messaging
 * - {@link ClaudeAdapter} - Claude/Anthropic API handling
 * - {@link FileManager} - File and vector store operations
 * - {@link BatchManager} - Batch processing
 * - {@link ChatCompletionService} - Chat, embeddings, and image generation
 * - {@link AgentManager} - Agent CRUD and OpenAI Assistant sync
 * - {@link AgentRequestService} - Agent request execution with retry logic
 *
 * All operations use typed exceptions from the exception package for better error handling.
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
    private final ThreadManager threadManager;
    private final ClaudeAdapter claudeAdapter;
    private final FileManager fileManager;
    private final BatchManager batchManager;
    private final ChatCompletionService chatCompletionService;
    private final AgentManager agentManager;
    private final AgentRequestService agentRequestService;

    /**
     * Constructs AgentService with the provided configuration.
     */
    public AgentService(AgentServiceConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Initialize HTTP helper
        this.httpHelper = new HttpHelper();

        // Initialize rate limiter
        this.rateLimiter = new RateLimiter(config.getRequestsPerSecond());
        logger.info("Initialized rate limiter: {} requests/second", config.getRequestsPerSecond());

        // Parse instances from JSON config (with provider filtering)
        List<Instance> instances = parseInstances(config);
        this.instanceRouter = new InstanceRouter(instances);

        if (instances.isEmpty()) {
            logger.warn("⚠️ No AI instances configured. Running in DEGRADED MODE.");
            logger.warn("   Set OPENAI_INSTANCES environment variable to enable AI features.");
        } else {
            logger.info("Initialized {} AI instance(s)", instances.size());
            for (Instance inst : instances) {
                logger.info("  - {} ({}) models: {}", inst.getId(), inst.getProvider(), inst.getDeployedModels());
            }
        }

        // Initialize specialized managers
        this.claudeAdapter = new ClaudeAdapter(httpHelper);
        this.threadManager = new ThreadManager(httpHelper, instanceRouter, claudeAdapter);
        this.fileManager = new FileManager(httpHelper, instanceRouter);
        this.batchManager = new BatchManager(httpHelper, instanceRouter);
        this.chatCompletionService = new ChatCompletionService(httpHelper, instanceRouter,
                claudeAdapter, objectMapper, config);

        // Initialize agent manager and load definitions
        this.agentManager = new AgentManager(config, httpHelper, instanceRouter, objectMapper);
        if (config.getAgentJsonFolderPath() != null && !config.getAgentJsonFolderPath().isEmpty()) {
            agentManager.loadAgentDefinitions();
        }

        // Initialize request service
        this.agentRequestService = new AgentRequestService(config, httpHelper, instanceRouter,
                claudeAdapter, objectMapper, rateLimiter, agentManager);

        logger.info("AgentService initialized successfully");
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
     * Creates or updates an OpenAI Assistant for an agent on ALL configured instances.
     *
     * @param agentId Agent ID
     * @return CompletableFuture with the created/updated Assistant (from first instance)
     */
    public CompletableFuture<Assistant> createAgent(String agentId) {
        return agentManager.createAgent(agentId);
    }

    /**
     * Creates or updates ALL loaded agents on ALL configured instances.
     *
     * @return CompletableFuture that completes when all agents are created/updated
     */
    public CompletableFuture<Void> createAllAgents() {
        return agentManager.createAllAgents();
    }

    // ==================== AGENT REQUESTS ====================

    /**
     * Sends a request to an agent and waits for completion.
     *
     * @param agentId     Agent ID
     * @param userMessage User message content
     * @param threadRef   Thread reference (null for oneshot)
     * @return CompletableFuture with the agent's response as AgentResult
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage, String threadRef) {
        return agentRequestService.requestAgent(agentId, userMessage, threadRef);
    }

    /**
     * Sends a message to an agent (oneshot - creates temporary thread).
     *
     * @param agentId     Agent ID
     * @param userMessage User message content
     * @return CompletableFuture with the agent's response as AgentResult
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage) {
        return agentRequestService.requestAgent(agentId, userMessage);
    }

    /**
     * Sends a request to an agent with vector store support.
     *
     * @param agentId       Agent ID
     * @param userMessage   User message
     * @param vectorStoreId Vector store ID
     * @return CompletableFuture with response
     */
    public CompletableFuture<String> requestAgentWithVectorStorage(String agentId, String userMessage,
                                                                     String vectorStoreId) {
        return agentRequestService.requestAgentWithVectorStorage(agentId, userMessage, vectorStoreId);
    }

    // ==================== THREAD OPERATIONS ====================

    /**
     * Creates a new thread for the specified model.
     * @see ThreadManager#createThread(String)
     */
    public CompletableFuture<String> createThread(String model) {
        return threadManager.createThread(model);
    }

    /**
     * Sends a message to an existing thread.
     * @see ThreadManager#sendMessage(Agent, String, String)
     */
    public CompletableFuture<String> sendMessageToThread(String agentId, String threadRef, String message) {
        Agent agent = getAgent(agentId);
        return threadManager.sendMessage(agent, threadRef, message);
    }

    /**
     * Deletes a thread.
     * @see ThreadManager#deleteThread(String)
     */
    public CompletableFuture<Boolean> deleteThread(String threadRef) {
        return threadManager.deleteThread(threadRef);
    }

    // ==================== CHAT COMPLETIONS ====================

    /**
     * Executes a chat completion (string response).
     * @see ChatCompletionService#requestChatCompletion(String, List, Double)
     */
    public CompletableFuture<String> requestChatCompletion(String model, List<ChatMessage> messages,
                                                            Double temperature) {
        return chatCompletionService.requestChatCompletion(model, messages, temperature);
    }

    /**
     * Executes a chat completion with structured output.
     * @see ChatCompletionService#chatCompletion(String, List, Double, Class)
     */
    public <T extends AgentResult> CompletableFuture<T> chatCompletion(String model,
                                                                         List<ChatMessage> messages,
                                                                         Double temperature,
                                                                         Class<T> resultClass) {
        return chatCompletionService.chatCompletion(model, messages, temperature, resultClass);
    }

    /**
     * Executes a chat completion without structured output.
     */
    public CompletableFuture<DefaultResult> chatCompletion(String model, List<ChatMessage> messages,
                                                            Double temperature) {
        return chatCompletionService.chatCompletion(model, messages, temperature);
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
        return chatCompletionService.chatCompletion(model, messages, temperature,
                resultClassName, config.getAgentResultClassPackage());
    }

    // ==================== EMBEDDINGS ====================

    /**
     * Generates embeddings for text.
     * @see ChatCompletionService#generateEmbedding(String, String)
     */
    public CompletableFuture<float[]> generateEmbedding(String text, String model) {
        return chatCompletionService.generateEmbedding(text, model);
    }

    /**
     * Generates embeddings using default model.
     */
    public CompletableFuture<float[]> generateEmbedding(String text) {
        return chatCompletionService.generateEmbedding(text);
    }

    // ==================== IMAGE GENERATION ====================

    /**
     * Generates an image.
     * @see ChatCompletionService#generateImage(String, String, Size, Quality)
     */
    public CompletableFuture<String> generateImage(String prompt, String model, Size size, Quality quality) {
        return chatCompletionService.generateImage(prompt, model, size, quality);
    }

    /**
     * Generates an image with default settings.
     */
    public CompletableFuture<String> generateImage(String prompt) {
        return chatCompletionService.generateImage(prompt);
    }

    // ==================== FILE OPERATIONS ====================

    /**
     * Uploads a file.
     * @see FileManager#uploadFile(Path, String)
     */
    public CompletableFuture<String> uploadFile(Path filePath, String purpose) {
        return fileManager.uploadFile(filePath, purpose);
    }

    /**
     * Uploads a file for assistants.
     */
    public CompletableFuture<String> uploadFileForAssistants(Path filePath) {
        return fileManager.uploadFileForAssistants(filePath);
    }

    /**
     * Deletes a file.
     * @see FileManager#deleteFile(String)
     */
    public CompletableFuture<Boolean> deleteFile(String fileRef) {
        return fileManager.deleteFile(fileRef);
    }

    /**
     * Extracts the actual file ID from an encoded file reference.
     */
    public String extractFileId(String fileRef) {
        return instanceRouter.extractActualId(fileRef);
    }

    // ==================== VECTOR STORE OPERATIONS ====================

    /**
     * Creates a vector store.
     * @see FileManager#createVectorStore(String, List)
     */
    public CompletableFuture<String> createVectorStore(String name, List<String> fileIds) {
        return fileManager.createVectorStore(name, fileIds);
    }

    /**
     * Deletes a vector store.
     * @see FileManager#deleteVectorStore(String)
     */
    public CompletableFuture<Boolean> deleteVectorStore(String vectorStoreRef) {
        return fileManager.deleteVectorStore(vectorStoreRef);
    }

    // ==================== BATCH OPERATIONS ====================

    /**
     * Creates a batch.
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
     * @see BatchManager#getBatch(String)
     */
    public CompletableFuture<Batch> getBatch(String batchId) {
        return batchManager.getBatch(batchId);
    }

    /**
     * Cancels a batch.
     * @see BatchManager#cancelBatch(String)
     */
    public CompletableFuture<Batch> cancelBatch(String batchId) {
        return batchManager.cancelBatch(batchId);
    }

    /**
     * Lists batches.
     * @see BatchManager#listBatches(Integer, String)
     */
    public CompletableFuture<Page<Batch>> listBatches(Integer limit, String after) {
        return batchManager.listBatches(limit, after);
    }

    /**
     * Polls batch until complete.
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
                if (c == '{') openBraces++;
                if (c == '}') openBraces--;
            }
            return openBraces == 0;
        }

        if (trimmed.startsWith("[")) {
            int openBrackets = 0;
            for (char c : trimmed.toCharArray()) {
                if (c == '[') openBrackets++;
                if (c == ']') openBrackets--;
            }
            return openBrackets == 0;
        }

        // For non-JSON responses, consider them OK if not empty
        return true;
    }

    /**
     * Maps a JSON response to a typed agent result.
     *
     * @param <T>         Result type
     * @param jsonResponse JSON response string
     * @param resultClass  Result class name
     * @return Typed result instance
     */
    public <T extends AgentResult> T mapResponse(String jsonResponse, String resultClass) {
        try {
            if (config.getAgentResultClassPackage() == null) {
                throw new IllegalStateException("Agent result class package not configured");
            }

            String fullClassName = config.getAgentResultClassPackage() + "." + resultClass;
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
     * Gets the set of allowed providers from the environment variable.
     * @return Set of allowed provider names (lowercase), or empty set if no filter
     */
    private Set<String> getAllowedProviders() {
        String providersEnv = System.getenv(ENABLED_PROVIDERS_ENV);
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
            } else if (ic.isAzureOpenAI()) {
                providerType = Provider.AZURE_OPENAI;
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
                    .build();

            result.add(instance);
        }

        return result;
    }
}
