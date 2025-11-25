package io.github.sashirestela.openai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
// SimpleOpenAI removed - using HttpHelper directly
import io.github.sashirestela.openai.common.ResponseFormat;
import io.github.sashirestela.openai.common.content.ContentPart;
import io.github.sashirestela.openai.common.content.ContentPart.ContentPartTextAnnotation;
import io.github.sashirestela.openai.common.content.ImageDetail;
import io.github.sashirestela.openai.domain.assistant.Assistant;
import io.github.sashirestela.openai.domain.assistant.AssistantRequest;
import io.github.sashirestela.openai.domain.assistant.ThreadMessageRequest;
import io.github.sashirestela.openai.domain.assistant.ThreadMessageRole;
import io.github.sashirestela.openai.domain.assistant.ThreadRun;
import io.github.sashirestela.openai.domain.assistant.ThreadRun.RunStatus;
import io.github.sashirestela.openai.domain.assistant.ThreadRequest;
import io.github.sashirestela.openai.domain.assistant.ThreadRunRequest;
import io.github.sashirestela.openai.domain.assistant.ToolResourceFull;
import io.github.sashirestela.openai.domain.assistant.VectorStoreRequest;
import io.github.sashirestela.openai.domain.chat.Chat;
import io.github.sashirestela.openai.domain.chat.ChatMessage;
import io.github.sashirestela.openai.domain.chat.ChatRequest;
import io.github.sashirestela.openai.domain.image.Image;
import io.github.sashirestela.openai.domain.image.ImageRequest;
import io.github.sashirestela.openai.domain.image.ImageRequest.Quality;
import io.github.sashirestela.openai.domain.image.Size;
import io.github.sashirestela.openai.support.JsonSchemaGenerator;
import io.github.sashirestela.openai.support.RateLimiter;
import lombok.Builder;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for managing OpenAI/Azure AI agents (Assistants API).
 * Supports multi-instance Azure deployments, rate limiting, structured outputs, and image generation.
 */
public class AgentService {

    private static final Logger logger = LoggerFactory.getLogger(AgentService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AgentServiceConfig config;
    private final List<Instance> instances;  // All instances (OpenAI + Azure) with their deployed models
    private final Map<String, AtomicInteger> modelIndexes;  // Separate round-robin counter per model
    private final AtomicInteger globalInstanceIndex;  // Global counter for model-agnostic operations (threads, vector stores)
    private final RateLimiter rateLimiter;
    private final Map<String, Agent> agents;

    // Claude/Anthropic virtual thread storage (in-memory conversation history)
    private final Map<String, List<ClaudeRequest.ClaudeMessage>> claudeThreads;

    // HTTP helper for ALL API calls (replaces SimpleOpenAI/CleverClient)
    private final HttpHelper httpHelper;

    // Legacy HTTP client (kept for backward compatibility, will be removed)
    private final HttpClient httpClient;

    /**
     * Constructs AgentService with the provided configuration.
     * Initializes instances and loads agent definitions from JSON files.
     * All API calls are made via HttpHelper (no CleverClient/SimpleOpenAI).
     *
     * @param config AgentService configuration
     * @throws IOException if agent definitions cannot be loaded
     */
    public AgentService(AgentServiceConfig config) throws IOException {
        this.config = config;
        this.agents = new ConcurrentHashMap<>();
        this.instances = new ArrayList<>();
        this.modelIndexes = new ConcurrentHashMap<>();  // One atomic counter per model
        this.globalInstanceIndex = new AtomicInteger(0);  // For model-agnostic operations
        this.httpHelper = new HttpHelper();  // Single HTTP helper for all API calls

        // === JSON-based configuration (takes precedence) ===
        if (config.isUsingJsonConfig()) {
            logger.info("Using JSON-based instance configuration");
            List<InstanceConfig> instanceConfigs = config.parseInstances();

            // Get allowed providers from environment (if set)
            Set<String> allowedProviders = getAllowedProviders();

            // Filter to only enabled instances AND allowed providers
            List<InstanceConfig> enabledInstances = instanceConfigs.stream()
                    .filter(InstanceConfig::isEnabled)
                    .filter(ic -> isProviderAllowed(ic, allowedProviders))
                    .collect(java.util.stream.Collectors.toList());

            logger.info("Loaded {} instance(s) from JSON configuration ({} total, {} after filtering by enabled + providers)",
                    enabledInstances.size(), instanceConfigs.size(), enabledInstances.size());
            if (!allowedProviders.isEmpty()) {
                logger.info("Provider filter active: {}", allowedProviders);
            }

            for (InstanceConfig instanceConfig : enabledInstances) {
                // Determine provider type
                Provider providerType;
                if (instanceConfig.isAzureAnthropic()) {
                    providerType = Provider.AZURE_ANTHROPIC;
                } else if (instanceConfig.isAzureOpenAI()) {
                    providerType = Provider.AZURE_OPENAI;
                } else {
                    providerType = Provider.OPENAI;
                }

                // Normalize base URL (remove trailing slash)
                String baseUrl = normalizeBaseUrl(instanceConfig.getUrl());

                // Create Instance object (no SimpleOpenAI client - we use HttpHelper)
                Instance instance = Instance.builder()
                        .id(instanceConfig.getId())
                        .baseUrl(baseUrl)
                        .apiKey(instanceConfig.getKey())
                        .provider(providerType)
                        .azureApiVersion(instanceConfig.getApiVersion())
                        .deployedModels(instanceConfig.getModelsList())
                        .build();

                this.instances.add(instance);
                logger.info("Initialized instance: {} ({}) with models: {}",
                        instanceConfig.getId(),
                        providerType,
                        instanceConfig.getModels());
            }

            logger.info("Initialized {} instance(s) from JSON configuration", this.instances.size());
        }

        if (this.instances.isEmpty()) {
            throw new IllegalStateException("No instances configured. Set OPENAI_INSTANCES environment variable.");
        }

        logger.info("Total instances: {} | Models available: {}",
                this.instances.size(),
                this.instances.stream()
                        .flatMap(i -> i.getDeployedModels().stream())
                        .distinct()
                        .collect(java.util.stream.Collectors.toList()));

        // Initialize rate limiter
        this.rateLimiter = new RateLimiter(config.getRequestsPerSecond());
        logger.info("Initialized rate limiter: {} requests/second", config.getRequestsPerSecond());

        // Initialize Claude virtual threads storage
        this.claudeThreads = new ConcurrentHashMap<>();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        logger.info("Initialized Claude virtual thread storage");

        // Load agent definitions if folder path is provided
        if (config.getAgentJsonFolderPath() != null && !config.getAgentJsonFolderPath().isEmpty()) {
            loadAgentDefinitions();
        }
    }

    // ==================== PROVIDER FILTERING ====================

    /**
     * Environment variable name for allowed providers filter.
     * Format: comma-separated list of providers (e.g., "openai,azure-openai")
     * If not set or empty, all providers are allowed.
     */
    private static final String ENABLED_PROVIDERS_ENV = "ENABLED_PROVIDERS";

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
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Checks if an instance's provider is allowed by the filter.
     * @param instanceConfig The instance configuration
     * @param allowedProviders Set of allowed providers (empty = all allowed)
     * @return true if the provider is allowed
     */
    private boolean isProviderAllowed(InstanceConfig instanceConfig, Set<String> allowedProviders) {
        if (allowedProviders.isEmpty()) {
            return true;  // No filter = all allowed
        }
        String provider = instanceConfig.getProvider().toLowerCase();
        // Check exact match or alias match
        if (allowedProviders.contains(provider)) {
            return true;
        }
        // Handle aliases: "azure" matches "azure-openai"
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

    // ==================== INSTANCE INDEX ENCODING/DECODING ====================
    // Thread IDs and Vector Store IDs are encoded with instance index for persistence
    // Format: "instanceIndex_actualId" (e.g., "3_thread_abc123" or "5_vs_xyz789")

    /**
     * Encodes a thread/vector store ID with its instance index.
     * Format: "instanceIndex_actualId"
     *
     * @param instanceIndex Instance index (0-8)
     * @param actualId Actual OpenAI ID (e.g., "thread_abc123")
     * @return Encoded ID (e.g., "3_thread_abc123")
     */
    private String encodeWithInstance(int instanceIndex, String actualId) {
        return instanceIndex + "_" + actualId;
    }

    /**
     * Decodes an encoded ID to extract instance index and actual ID.
     * Format: "instanceIndex_actualId"
     *
     * @param encodedId Encoded ID (e.g., "3_thread_abc123")
     * @return Array [instanceIndex, actualId] or null if not encoded
     */
    private String[] decodeInstanceId(String encodedId) {
        if (encodedId == null || !encodedId.contains("_")) {
            return null;
        }
        String[] parts = encodedId.split("_", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            Integer.parseInt(parts[0]);  // Validate it's a number
            return parts;
        } catch (NumberFormatException e) {
            return null;  // Not encoded, just a regular ID with underscore
        }
    }

    // ==================== MODEL-AWARE INSTANCE SELECTION ====================

    /**
     * Gets the next instance INDEX that has the specified model deployed.
     * Uses SEPARATE round-robin counter PER MODEL for optimal load distribution.
     *
     * Algorithm:
     * 1. Get or create atomic counter for this model
     * 2. Increment atomic counter
     * 3. Check if instance at this index has the model
     * 4. If yes, return this index
     * 5. If no, increment again and check next
     * 6. If we've checked all instances → error (no instance has this model)
     *
     * Benefits of per-model counters:
     * - gpt-4o requests don't affect gpt-4o-mini round-robin
     * - Each model has independent, evenly distributed load balancing
     *
     * @param model Model name (e.g., "gpt-4o", "dall-e-3")
     * @return INDEX of instance that has this model
     * @throws IllegalArgumentException if no instance has the model
     */
    private int getNextInstanceForModel(String model) {
        // Get or create atomic counter for this specific model
        AtomicInteger modelIndex = modelIndexes.computeIfAbsent(model, k -> new AtomicInteger(0));

        int startIndex = modelIndex.get();
        logger.trace("Round-robin for model '{}': current counter = {}", model, startIndex);

        // Try all instances starting from current position
        for (int i = 0; i < instances.size(); i++) {
            int idx = (startIndex + i) % instances.size();
            Instance instance = instances.get(idx);

            if (instance.hasModel(model)) {
                // Advance this model's counter for next call
                int nextIndex = (idx + 1) % instances.size();
                modelIndex.set(nextIndex);
                logger.trace("Round-robin for model '{}': selected instance {}, next counter will be {}",
                        model, idx, nextIndex);
                return idx;
            }
        }

        // No instance found with this model
        throw new IllegalArgumentException(
                String.format("No instance configured with model '%s'. Available models: %s",
                        model,
                        instances.stream()
                                .flatMap(i -> i.getDeployedModels().stream())
                                .distinct()
                                .collect(java.util.stream.Collectors.toList()))
        );
    }

    /**
     * Normalizes base URL by removing trailing slash.
     */
    private String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    // ==================== CLAUDE/ANTHROPIC API METHODS ====================

    /**
     * Calls Claude/Anthropic API directly via HTTP.
     * Used for Azure Anthropic instances that don't use the OpenAI API format.
     *
     * @param instanceIndex Instance index for Azure Anthropic
     * @param request Claude request object
     * @return ClaudeResponse from the API
     * @throws RuntimeException if API call fails
     */
    private ClaudeResponse callClaudeAPI(int instanceIndex, ClaudeRequest request) {
        try {
            Instance instance = instances.get(instanceIndex);

            // Build URL using ProviderConfig
            String path = ProviderConfig.getPath(Provider.AZURE_ANTHROPIC, ProviderConfig.Endpoint.CHAT_COMPLETIONS);
            String url = instance.getBaseUrl() + path;

            // Serialize request to JSON
            String jsonBody = objectMapper.writeValueAsString(request);

            logger.debug("Calling Claude API: {}", url);
            logger.debug("Request body: {}", jsonBody);

            // Build HTTP request with Anthropic headers from ProviderConfig
            Map<String, String> authHeaders = ProviderConfig.getHeaders(
                    Provider.AZURE_ANTHROPIC,
                    instance.getApiKey(),
                    instance.getAzureApiVersion()
            );

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json");

            // Add auth headers from ProviderConfig
            for (Map.Entry<String, String> header : authHeaders.entrySet()) {
                requestBuilder.header(header.getKey(), header.getValue());
            }

            // Add beta header for structured outputs if output_format is present
            if (request.getOutputFormat() != null) {
                requestBuilder.header("anthropic-beta", "structured-outputs-2025-11-13");
                logger.debug("Added structured outputs beta header");
            }

            HttpRequest httpRequest = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            // Send request
            HttpResponse<String> httpResponse = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString()
            );

            logger.debug("Claude API response code: {}", httpResponse.statusCode());
            logger.debug("Claude API response body: {}", httpResponse.body());

            if (httpResponse.statusCode() != 200) {
                throw new RuntimeException("Claude API error (HTTP " + httpResponse.statusCode() + "): " +
                        httpResponse.body());
            }

            // Parse response
            return objectMapper.readValue(httpResponse.body(), ClaudeResponse.class);

        } catch (Exception e) {
            logger.error("Claude API call failed", e);
            throw new RuntimeException("Claude API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Executes a Claude agent request (oneshot - no conversation history).
     *
     * @param agent Agent configuration
     * @param message User message
     * @param instanceIndex Instance to use
     * @return Response text
     */
    private String executeClaudeRequest(Agent agent, String message, int instanceIndex) {
        try {
            // Build Claude request
            ClaudeRequest.ClaudeRequestBuilder requestBuilder = ClaudeRequest.builder()
                    .model(agent.getModel())
                    .maxTokens(agent.getMaxTokens() != null ? agent.getMaxTokens() : 4096)
                    .system(agent.getInstructions())
                    .messages(List.of(
                            ClaudeRequest.ClaudeMessage.builder()
                                    .role("user")
                                    .content(message)
                                    .build()
                    ))
                    .temperature(agent.getTemperature());

            // Add output_format if agent has resultClass (Claude structured outputs)
            if (agent.getResultClass() != null && !agent.getResultClass().isEmpty() &&
                    config.getAgentResultClassPackage() != null) {
                try {
                    ResponseFormat format = JsonSchemaGenerator.createResponseFormat(
                            agent.getResultClass(),
                            config.getAgentResultClassPackage());
                    requestBuilder.outputFormat(convertToClaudeOutputFormat(format));
                } catch (Exception e) {
                    logger.warn("Failed to create JSON schema for Claude: {}", e.getMessage());
                }
            }

            ClaudeRequest request = requestBuilder.build();

            // Call Claude API
            ClaudeResponse response = callClaudeAPI(instanceIndex, request);
            return response.getTextContent();

        } catch (Exception e) {
            logger.error("Claude request failed for agent: {}", agent.getId(), e);
            throw new RuntimeException("Claude request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Converts OpenAI ResponseFormat to Claude's output_format structure.
     * Claude uses: {"type": "json_schema", "schema": {...}}
     * OpenAI uses: {"type": "json_schema", "json_schema": {"schema": JsonNode}}
     *
     * @param format OpenAI ResponseFormat
     * @return Map suitable for Claude API output_format parameter
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertToClaudeOutputFormat(ResponseFormat format) {
        if (format == null || format.getJsonSchema() == null) {
            return null;
        }

        try {
            ResponseFormat.JsonSchema jsonSchema = format.getJsonSchema();
            com.fasterxml.jackson.databind.JsonNode schemaNode = jsonSchema.getSchema();

            if (schemaNode == null) {
                logger.warn("No schema found in ResponseFormat for Claude");
                return null;
            }

            // Convert JsonNode to Map using ObjectMapper
            Map<String, Object> schemaMap = objectMapper.convertValue(schemaNode,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

            // Claude format: {"type": "json_schema", "schema": {...}}
            return Map.of(
                    "type", "json_schema",
                    "schema", schemaMap
            );
        } catch (Exception e) {
            logger.warn("Failed to convert schema for Claude: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Checks if an instance is a Claude/Anthropic provider.
     *
     * @param instanceIndex Instance index
     * @return true if Azure Anthropic
     */
    private boolean isClaudeInstance(int instanceIndex) {
        if (instanceIndex < 0 || instanceIndex >= instances.size()) {
            return false;
        }
        return instances.get(instanceIndex).getProvider() == Provider.AZURE_ANTHROPIC;
    }

    /**
     * Loads agent definitions from JSON files in the configured folder.
     */
    private void loadAgentDefinitions() throws IOException {
        Path agentFolder = Paths.get(config.getAgentJsonFolderPath());

        if (!Files.exists(agentFolder) || !Files.isDirectory(agentFolder)) {
            logger.warn("Agent JSON folder does not exist or is not a directory: {}", agentFolder);
            return;
        }

        try (Stream<Path> paths = Files.walk(agentFolder)) {
            List<Path> jsonFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .collect(Collectors.toList());

            logger.info("Found {} agent JSON files", jsonFiles.size());

            for (Path jsonFile : jsonFiles) {
                try {
                    String content = Files.readString(jsonFile);
                    AgentDefinition definition = objectMapper.readValue(content, AgentDefinition.class);

                    Agent agent = Agent.builder()
                            .id(definition.getId())
                            .name(definition.getName())
                            .assistantIds(definition.getAssistantIds())
                            .model(definition.getModel())
                            .instructions(definition.getInstructions())
                            .resultClass(definition.getResultClass())
                            .temperature(definition.getTemperature())
                            .responseTimeout(definition.getResponseTimeout() != null ?
                                    definition.getResponseTimeout().longValue() : config.getDefaultResponseTimeout())
                            .retrieval(definition.getRetrieval() != null ? definition.getRetrieval() : false)
                            .isOpenAI(definition.getIsOpenAI() != null ? definition.getIsOpenAI() : true)
                            .maxTokens(definition.getMaxTokens())
                            .build();

                    agents.put(agent.getId(), agent);
                    logger.debug("Loaded agent: {} ({})", agent.getName(), agent.getId());

                } catch (Exception e) {
                    logger.error("Failed to load agent from file: {}", jsonFile, e);
                }
            }

            logger.info("Successfully loaded {} agents", agents.size());
        }
    }

    /**
     * Gets an agent by ID.
     *
     * @param agentId Agent ID
     * @return Agent or null if not found
     */
    public Agent getAgent(String agentId) {
        return agents.get(agentId);
    }

    /**
     * Gets all loaded agents.
     *
     * @return Map of agent ID to Agent
     */
    public Map<String, Agent> getAllAgents() {
        return Collections.unmodifiableMap(agents);
    }

    /**
     * Creates or updates an OpenAI Assistant for an agent on ALL configured instances.
     * This is essential for multi-instance Azure deployments to ensure load balancing works correctly.
     *
     * @param agentId Agent ID
     * @return CompletableFuture with the created/updated Assistant (from first instance)
     */
    public CompletableFuture<Assistant> createAgent(String agentId) {
        Agent agent = agents.get(agentId);
        if (agent == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Agent not found: " + agentId));
        }

        // Skip Claude/Anthropic agents - they don't use OpenAI Assistants
        if (agent.getIsOpenAI() != null && !agent.getIsOpenAI()) {
            logger.info("⏭️  Skipping agent '{}' (isOpenAI=false, no assistant creation needed)", agent.getName());
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Build assistant request
                AssistantRequest.AssistantRequestBuilder requestBuilder = AssistantRequest.builder()
                        .name(agent.getName())
                        .instructions(agent.getInstructions())
                        .model(agent.getModel());

                if (agent.getTemperature() != null) {
                    requestBuilder.temperature(agent.getTemperature());
                }

                // Add response format if result class is specified
                if (agent.getResultClass() != null && !agent.getResultClass().isEmpty() &&
                        config.getAgentResultClassPackage() != null) {
                    ResponseFormat format = JsonSchemaGenerator.createResponseFormat(
                            agent.getResultClass(),
                            config.getAgentResultClassPackage());
                    requestBuilder.responseFormat(format);
                }

                AssistantRequest request = requestBuilder.build();

                // Initialize assistantIds list if null
                if (agent.getAssistantIds() == null) {
                    agent.setAssistantIds(new java.util.ArrayList<>());
                }

                // Ensure list has enough capacity for all instances
                while (agent.getAssistantIds().size() < instances.size()) {
                    agent.getAssistantIds().add(null);
                }

                // Create or update assistant ONLY on instances that have this model deployed
                Assistant firstAssistant = null;
                boolean assistantIdsChanged = false;  // Track if we need to persist changes

                for (int i = 0; i < instances.size(); i++) {
                    Instance instance = instances.get(i);

                    // Skip instances that don't have this model deployed
                    if (!instance.hasModel(agent.getModel())) {
                        continue;
                    }

                    // Create assistant via HttpHelper (global endpoint, no model in path)
                    Assistant assistant;

                    String existingAssistantId = agent.getAssistantIds().get(i);
                    if (existingAssistantId != null && !existingAssistantId.isEmpty()) {
                        // Try to update existing assistant - if fails (404), create new one
                        try {
                            var modifyRequest = io.github.sashirestela.openai.domain.assistant.AssistantModifyRequest.builder()
                                    .name(agent.getName())
                                    .instructions(agent.getInstructions())
                                    .model(agent.getModel())
                                    .temperature(agent.getTemperature())
                                    .responseFormat(request.getResponseFormat())
                                    .build();
                            Map<String, String> pathParams = new HashMap<>();
                            pathParams.put("assistantId", existingAssistantId);
                            assistant = httpHelper.post(instance, ProviderConfig.Endpoint.ASSISTANT,
                                    null, modifyRequest, Assistant.class, pathParams).join();
                            logger.info("✅ Updated assistant on instance {}: {} ({})", i, agent.getName(), assistant.getId());
                        } catch (Exception e) {
                            // If modify fails (404 = assistant doesn't exist), create new assistant
                            logger.warn("⚠️ Failed to modify assistant {} on instance {} ({}), creating new assistant...",
                                    existingAssistantId, i, e.getMessage());
                            assistant = httpHelper.post(instance, ProviderConfig.Endpoint.ASSISTANTS,
                                    null, request, Assistant.class).join();
                            agent.getAssistantIds().set(i, assistant.getId());
                            assistantIdsChanged = true;
                            logger.info("✅ Created new assistant on instance {}: {} ({}) to replace {}",
                                    i, agent.getName(), assistant.getId(), existingAssistantId);
                        }
                    } else {
                        // Create new assistant via HttpHelper
                        assistant = httpHelper.post(instance, ProviderConfig.Endpoint.ASSISTANTS,
                                null, request, Assistant.class).join();
                        agent.getAssistantIds().set(i, assistant.getId());
                        assistantIdsChanged = true;
                        logger.info("✅ Created assistant on instance {}: {} ({})", i, agent.getName(), assistant.getId());
                    }

                    // Keep reference to first assistant to return
                    if (i == 0) {
                        firstAssistant = assistant;
                    }
                }

                // Count instances with this model
                long instancesWithModel = instances.stream()
                        .filter(inst -> inst.hasModel(agent.getModel()))
                        .count();

                // Persist assistant IDs back to JSON if they changed
                if (assistantIdsChanged) {
                    try {
                        saveAgentDefinitionIds(agent);
                        logger.info("💾 Persisted updated assistant IDs for agent: {}", agent.getName());
                    } catch (IOException e) {
                        logger.error("⚠️ Failed to persist assistant IDs to JSON for agent: {}", agent.getName(), e);
                        // Continue anyway - IDs are in memory
                    }
                }

                logger.info("🎯 Agent '{}' (model: {}) successfully created/updated on {} instance(s) that have this model",
                        agent.getName(), agent.getModel(), instancesWithModel);
                return firstAssistant;

            } catch (Exception e) {
                logger.error("Failed to create/update agent: {}", agentId, e);
                throw new RuntimeException("Failed to create/update agent: " + agentId, e);
            }
        });
    }

    /**
     * Creates or updates ALL loaded agents on ALL configured instances.
     * This is useful for initialization or bulk updates.
     *
     * @return CompletableFuture that completes when all agents are created/updated
     */
    public CompletableFuture<Void> createAllAgents() {
        if (agents.isEmpty()) {
            logger.warn("No agents loaded to create/update");
            return CompletableFuture.completedFuture(null);
        }

        logger.info("🚀 Creating/updating {} agents on {} instance(s)...", agents.size(), instances.size());

        return CompletableFuture.supplyAsync(() -> {
            List<CompletableFuture<Assistant>> futures = new ArrayList<>();

            // Create/update all agents
            for (String agentId : agents.keySet()) {
                futures.add(createAgent(agentId));
            }

            // Wait for all to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            logger.info("✅ Successfully created/updated all {} agents on all {} instance(s)", agents.size(), instances.size());
            return null;
        });
    }

    /**
     * Reload agent definitions from JSON files.
     * This is useful when agent JSON files are modified while the application is running.
     *
     * <p><strong>Use case:</strong> Update agent instructions/configuration without restarting the app:</p>
     * <pre>{@code
     * 1. Modify agent JSON file (e.g., agent_500_entity_parser.json)
     * 2. Call agentService.reloadAgents().join()
     * 3. Optionally call agentService.createAllAgents().join() to update on OpenAI/Azure
     * }</pre>
     *
     * @return CompletableFuture that completes when agents are reloaded
     * @throws IllegalStateException if agentJsonFolderPath is not configured
     */
    public CompletableFuture<Void> reloadAgents() {
        if (config.getAgentJsonFolderPath() == null || config.getAgentJsonFolderPath().isEmpty()) {
            throw new IllegalStateException("Cannot reload agents: agentJsonFolderPath is not configured");
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("🔄 Reloading agent definitions from: {}", config.getAgentJsonFolderPath());

                // Clear current agents
                int previousCount = agents.size();
                agents.clear();

                // Reload from JSON files
                loadAgentDefinitions();

                logger.info("✅ Reloaded {} agent definitions (previously: {})", agents.size(), previousCount);
                return null;
            } catch (IOException e) {
                logger.error("❌ Failed to reload agents: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to reload agent definitions", e);
            }
        });
    }

    /**
     * Reload a specific agent definition from its JSON file.
     * This is useful when you modify a single agent file and want to reload only that one.
     *
     * <p><strong>Use case:</strong> Update a single agent without reloading all:</p>
     * <pre>{@code
     * 1. Modify agent_500_entity_parser.json
     * 2. Call agentService.reloadAgent("500").join()
     * 3. Optionally call agentService.createAgent("500").join() to update on OpenAI/Azure
     * }</pre>
     *
     * @param agentId ID of the agent to reload (e.g., "500")
     * @return CompletableFuture that completes when agent is reloaded
     * @throws IllegalArgumentException if agent file not found
     * @throws IllegalStateException if agentJsonFolderPath is not configured
     */
    public CompletableFuture<Void> reloadAgent(String agentId) {
        if (config.getAgentJsonFolderPath() == null || config.getAgentJsonFolderPath().isEmpty()) {
            throw new IllegalStateException("Cannot reload agent: agentJsonFolderPath is not configured");
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("🔄 Reloading agent {} from JSON file", agentId);

                Path agentFolder = Paths.get(config.getAgentJsonFolderPath());

                // Find agent JSON file (try common patterns)
                String[] possibleFilenames = {
                    "agent_" + agentId + ".json",
                    agentId + ".json",
                    "agent_" + agentId + "_*.json"  // For files like agent_500_entity_parser.json
                };

                Path agentFile = null;
                for (String pattern : possibleFilenames) {
                    try (Stream<Path> paths = Files.walk(agentFolder, 1)) {
                        List<Path> matches = paths
                            .filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().matches(pattern.replace("*", ".*")))
                            .collect(java.util.stream.Collectors.toList());

                        if (!matches.isEmpty()) {
                            agentFile = matches.get(0);
                            break;
                        }
                    }
                }

                if (agentFile == null) {
                    throw new IllegalArgumentException("Agent file not found for ID: " + agentId +
                        ". Tried patterns: " + String.join(", ", possibleFilenames));
                }

                // Read and parse JSON
                String jsonContent = Files.readString(agentFile);
                Agent agent = objectMapper.readValue(jsonContent, Agent.class);

                // Update agents map
                agents.put(agentId, agent);

                logger.info("✅ Reloaded agent {} from: {}", agentId, agentFile.getFileName());
                return null;

            } catch (IOException e) {
                logger.error("❌ Failed to reload agent {}: {}", agentId, e.getMessage(), e);
                throw new RuntimeException("Failed to reload agent: " + agentId, e);
            }
        });
    }

    /**
     * Saves updated assistant IDs back to the agent's JSON definition file.
     * Only updates the "assistantIds" field, preserving all other fields.
     *
     * @param agent Agent with updated assistant IDs
     * @throws IOException if file cannot be read/written
     */
    private void saveAgentDefinitionIds(Agent agent) throws IOException {
        if (config.getAgentJsonFolderPath() == null || config.getAgentJsonFolderPath().isEmpty()) {
            logger.warn("Agent JSON folder path not configured, cannot persist assistant IDs");
            return;
        }

        Path agentFolder = Paths.get(config.getAgentJsonFolderPath());

        // Find the agent's JSON file
        try (Stream<Path> paths = Files.walk(agentFolder)) {
            List<Path> matchingFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> {
                        try {
                            String content = Files.readString(path);
                            AgentDefinition def = objectMapper.readValue(content, AgentDefinition.class);
                            return agent.getId().equals(def.getId());
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());

            if (matchingFiles.isEmpty()) {
                logger.warn("No JSON file found for agent ID: {}", agent.getId());
                return;
            }

            Path jsonFile = matchingFiles.get(0);

            // Load existing JSON as Map to preserve all fields
            @SuppressWarnings("unchecked")
            Map<String, Object> existingJson = objectMapper.readValue(jsonFile.toFile(), Map.class);

            // Update only the assistantIds field
            existingJson.put("assistantIds", agent.getAssistantIds());

            // Write back with pretty printing
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(jsonFile.toFile(), existingJson);

            logger.debug("Saved assistant IDs to: {}", jsonFile);

        } catch (IOException e) {
            logger.error("Failed to persist assistant IDs for agent: {}", agent.getId(), e);
            throw e;
        }
    }
    /**
     * Modifies an existing agent's configuration.
     *
     * @param agentId Agent ID
     * @param updates Map of field names to new values
     * @return CompletableFuture with the updated Agent
     */
    public CompletableFuture<Agent> modifyAgent(String agentId, Map<String, Object> updates) {
        Agent agent = agents.get(agentId);
        if (agent == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Agent not found: " + agentId));
        }

        return CompletableFuture.supplyAsync(() -> {
            // Apply updates (simplified - you may want to use reflection for full flexibility)
            if (updates.containsKey("instructions")) {
                agent.setInstructions((String) updates.get("instructions"));
            }
            if (updates.containsKey("temperature")) {
                agent.setTemperature(((Number) updates.get("temperature")).doubleValue());
            }
            if (updates.containsKey("model")) {
                agent.setModel((String) updates.get("model"));
            }

            logger.info("Modified agent: {}", agentId);
            return agent;
        });
    }

    /**
     * Sends a request to an agent and waits for completion.
     *
     * @param agentId     Agent ID
     * @param userMessage User message content
     * @param threadRef   Thread reference (null for oneshot, or from {@link #createThread(String)} for persistent)
     * @return CompletableFuture with the agent's response as AgentResult
     */
    public CompletableFuture<AgentResult> requestAgent(
            String agentId,
            String userMessage,
            String threadRef) {

        Agent agent = agents.get(agentId);
        if (agent == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Agent not found: " + agentId));
        }

        // Get raw response
        return attemptRequest(agent, userMessage, threadRef, new HashMap<>(), 0)
                .thenApply(jsonResponse -> {
                    try {
                        // If no result class configured, return DefaultResult with raw response
                        if (agent.getResultClass() == null || agent.getResultClass().isEmpty()) {
                            return new DefaultResult(jsonResponse);
                        }

                        // If no package configured, return DefaultResult
                        if (config.getAgentResultClassPackage() == null || config.getAgentResultClassPackage().isEmpty()) {
                            logger.warn("Agent {} has resultClass but agentResultClassPackage not configured, returning DefaultResult", agentId);
                            return new DefaultResult(jsonResponse);
                        }

                        // Build full class name: package + resultClass
                        String fullClassName = config.getAgentResultClassPackage() + "." + agent.getResultClass();
                        logger.debug("Deserializing response for agent {} to class: {}", agentId, fullClassName);

                        // Load class dynamically and deserialize
                        Class<?> resultClass = Class.forName(fullClassName);
                        return (AgentResult) objectMapper.readValue(jsonResponse, resultClass);

                    } catch (ClassNotFoundException e) {
                        String fullClassName = config.getAgentResultClassPackage() + "." + agent.getResultClass();
                        throw new RuntimeException("Result class not found: " + fullClassName + " for agent " + agentId, e);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize response for agent " + agentId + ": " + e.getMessage(), e);
                    }
                });
    }

    /**
     * Sends a message to an agent (oneshot - creates temporary thread).
     * Convenience method that calls {@link #requestAgent(String, String, String)} with null threadRef.
     *
     * @param agentId     Agent ID
     * @param userMessage User message content
     * @return CompletableFuture with the agent's response as AgentResult
     */
    public CompletableFuture<AgentResult> requestAgent(String agentId, String userMessage) {
        return requestAgent(agentId, userMessage, null);
    }

    /**
     * Internal method to attempt a request with retry logic.
     */
    private CompletableFuture<String> attemptRequest(
            Agent agent,
            String userMessage,
            String threadId,
            Map<String, Object> additionalParams,
            int attemptNumber) {

        // Rate limiting
        if (!rateLimiter.tryConsume()) {
            logger.debug("Rate limit reached, delaying request");
            return delayedCompletion(100, TimeUnit.MILLISECONDS)
                    .thenCompose(v -> attemptRequest(agent, userMessage, threadId, additionalParams, attemptNumber));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Execute request on instance that has this agent's model
                return executeAgentRequest(agent, userMessage, threadId, additionalParams);

            } catch (Exception e) {
                return handleRequestException(agent, userMessage, threadId, additionalParams, attemptNumber, e);
            }
        });
    }

    /**
     * Executes a request using HttpHelper (replaces SimpleOpenAI client).
     */
    private String executeOpenAIRequest(
            Agent agent,
            String userMessage,
            String threadId,
            Map<String, Object> additionalParams) throws Exception {

        // Get instance that has this agent's model
        int instanceIdx = getNextInstanceForModel(agent.getModel());
        Instance instance = instances.get(instanceIdx);

        // Get or create thread
        String actualThreadId = threadId;
        if (actualThreadId == null || actualThreadId.isEmpty()) {
            io.github.sashirestela.openai.domain.assistant.Thread thread = httpHelper.post(
                    instance, ProviderConfig.Endpoint.THREADS, null,
                    ThreadRequest.builder().build(),
                    io.github.sashirestela.openai.domain.assistant.Thread.class).join();
            actualThreadId = thread.getId();
            logger.debug("Created new thread: {}", actualThreadId);
        }

        // Add message to thread
        var messageRequest = ThreadMessageRequest.builder()
                .role(ThreadMessageRole.USER)
                .content(userMessage)
                .build();
        Map<String, String> threadParams = new HashMap<>();
        threadParams.put("threadId", actualThreadId);
        httpHelper.post(instance, ProviderConfig.Endpoint.THREAD_MESSAGES, null,
                messageRequest, io.github.sashirestela.openai.domain.assistant.ThreadMessage.class, threadParams).join();

        // Get assistant ID for this specific instance
        String assistantId = null;
        if (agent.getAssistantIds() != null && instanceIdx < agent.getAssistantIds().size()) {
            assistantId = agent.getAssistantIds().get(instanceIdx);
        }
        if (assistantId == null) {
            throw new IllegalStateException("No assistant ID configured for instance " + instanceIdx
                    + " of agent: " + agent.getId());
        }
        ThreadRunRequest.ThreadRunRequestBuilder runBuilder = ThreadRunRequest.builder()
                .assistantId(assistantId);

        if (agent.getTemperature() != null) {
            runBuilder.temperature(agent.getTemperature());
        }

        ThreadRunRequest runRequest = runBuilder.build();
        ThreadRun run = httpHelper.post(instance, ProviderConfig.Endpoint.THREAD_RUNS, null,
                runRequest, ThreadRun.class, threadParams).join();

        // Poll for completion using HttpHelper
        ThreadRun completedRun = pollForCompletion(instance, actualThreadId, run.getId(), agent.getResponseTimeout());

        // Check status
        if (completedRun.getStatus() != RunStatus.COMPLETED) {
            throw new RuntimeException("Run failed with status: " + completedRun.getStatus());
        }

        // Get response - need to add a list response type wrapper
        ThreadMessagesResponse messagesResponse = httpHelper.get(instance, ProviderConfig.Endpoint.THREAD_MESSAGES,
                null, ThreadMessagesResponse.class, threadParams).join();
        if (messagesResponse.getData() == null || messagesResponse.getData().isEmpty()) {
            throw new RuntimeException("No messages returned");
        }

        return extractMessageContent(messagesResponse.getData().get(0).getContent());
    }

    /**
     * Executes an agent request using model-aware instance selection.
     * Supports persistent threads via encoded thread IDs (format: "instanceIndex_threadId").
     * Routes to Claude API for Azure Anthropic instances.
     */
    private String executeAgentRequest(
            Agent agent,
            String userMessage,
            String threadId,
            Map<String, Object> additionalParams) throws Exception {

        if (instances.isEmpty()) {
            throw new IllegalStateException("No instances configured");
        }

        // Determine which instance to use
        int instanceIdx;
        String actualThreadId;

        // Check if threadId is encoded with instance index (persistent thread)
        String[] decoded = decodeInstanceId(threadId);
        if (decoded != null) {
            // Persistent thread - MUST use the instance that created it
            instanceIdx = Integer.parseInt(decoded[0]);
            actualThreadId = decoded[1];
            logger.debug("Using persistent thread {} on instance {} (model: {})",
                    actualThreadId, instanceIdx, agent.getModel());
        } else {
            // New thread or non-persistent - use model-aware round-robin
            instanceIdx = getNextInstanceForModel(agent.getModel());
            actualThreadId = threadId;  // null or regular (non-encoded) thread ID
            logger.debug("Using model-aware round-robin for agent '{}': selected instance {} for model '{}'",
                    agent.getName(), instanceIdx, agent.getModel());
        }

        Instance instance = instances.get(instanceIdx);

        // Check if Claude/Anthropic instance - use direct API
        if (instance.getProvider() == Provider.AZURE_ANTHROPIC) {
            logger.debug("Routing to Claude API for agent '{}' (model: {})", agent.getName(), agent.getModel());
            return executeClaudeRequest(agent, userMessage, instanceIdx);
        }

        // Get assistant ID for this specific instance
        String assistantId = null;
        if (agent.getAssistantIds() != null && instanceIdx < agent.getAssistantIds().size()) {
            assistantId = agent.getAssistantIds().get(instanceIdx);
        }
        if (assistantId == null) {
            throw new IllegalStateException("No assistant ID configured for instance " + instanceIdx
                    + " of agent: " + agent.getId());
        }

        logger.debug("Using instance {} (model: {}) with assistant {}", instanceIdx, agent.getModel(), assistantId);

        // Create thread if needed (actualThreadId already set from decoding logic above)
        if (actualThreadId == null || actualThreadId.isEmpty()) {
            io.github.sashirestela.openai.domain.assistant.Thread thread = httpHelper.post(
                    instance, ProviderConfig.Endpoint.THREADS, null,
                    ThreadRequest.builder().build(),
                    io.github.sashirestela.openai.domain.assistant.Thread.class).join();
            actualThreadId = thread.getId();
            logger.debug("Created new thread {} on instance {}", actualThreadId, instanceIdx);
        }

        // Add message to thread
        Map<String, String> threadParams = new HashMap<>();
        threadParams.put("threadId", actualThreadId);
        var messageRequest = ThreadMessageRequest.builder()
                .role(ThreadMessageRole.USER)
                .content(userMessage)
                .build();
        httpHelper.post(instance, ProviderConfig.Endpoint.THREAD_MESSAGES, null,
                messageRequest, io.github.sashirestela.openai.domain.assistant.ThreadMessage.class, threadParams).join();

        // Create and execute run
        ThreadRunRequest.ThreadRunRequestBuilder runBuilder = ThreadRunRequest.builder()
                .assistantId(assistantId);

        if (agent.getTemperature() != null) {
            runBuilder.temperature(agent.getTemperature());
        }

        ThreadRunRequest runRequest = runBuilder.build();
        ThreadRun run = httpHelper.post(instance, ProviderConfig.Endpoint.THREAD_RUNS, null,
                runRequest, ThreadRun.class, threadParams).join();

        // Poll for completion using HttpHelper
        ThreadRun completedRun = pollForCompletion(instance, actualThreadId, run.getId(),
                agent.getResponseTimeout());

        // Check status
        if (completedRun.getStatus() != RunStatus.COMPLETED) {
            throw new RuntimeException("Run failed with status: " + completedRun.getStatus());
        }

        // Get response
        ThreadMessagesResponse messagesResponse = httpHelper.get(instance, ProviderConfig.Endpoint.THREAD_MESSAGES,
                null, ThreadMessagesResponse.class, threadParams).join();
        if (messagesResponse.getData() == null || messagesResponse.getData().isEmpty()) {
            throw new RuntimeException("No messages returned");
        }

        return extractMessageContent(messagesResponse.getData().get(0).getContent());
    }

    /**
     * Polls for run completion using HttpHelper.
     */
    private ThreadRun pollForCompletion(
            Instance instance,
            String threadId,
            String runId,
            long timeoutSeconds) throws Exception {

        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;

        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("threadId", threadId);
        pathParams.put("runId", runId);

        while (true) {
            // GET /threads/{threadId}/runs/{runId}
            ThreadRun run = httpHelper.get(
                    instance,
                    ProviderConfig.Endpoint.THREAD_RUN,
                    null,  // No model needed for this endpoint
                    ThreadRun.class,
                    pathParams
            ).join();

            RunStatus status = run.getStatus();

            if (status == RunStatus.COMPLETED ||
                status == RunStatus.FAILED ||
                status == RunStatus.CANCELLED ||
                status == RunStatus.EXPIRED) {
                return run;
            }

            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw new RuntimeException("Request timeout after " + timeoutSeconds + " seconds");
            }

            Thread.sleep(1000); // Poll every second
        }
    }

    /**
     * Extracts text content from message content parts.
     */
    private String extractMessageContent(List<ContentPart> contentParts) {
        if (contentParts == null || contentParts.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ContentPart part : contentParts) {
            if (part instanceof ContentPartTextAnnotation) {
                ContentPartTextAnnotation textPart = (ContentPartTextAnnotation) part;
                if (textPart.getText() != null && textPart.getText().getValue() != null) {
                    sb.append(textPart.getText().getValue());
                }
            }
        }
        return sb.toString();
    }

    /**
     * Handles request exceptions with retry logic.
     */
    private String handleRequestException(
            Agent agent,
            String userMessage,
            String threadId,
            Map<String, Object> additionalParams,
            int attemptNumber,
            Exception e) {

        if (attemptNumber >= config.getMaxRetries()) {
            logger.error("Max retries reached for agent: {}", agent.getId(), e);
            throw new RuntimeException("Request failed after " + config.getMaxRetries() + " retries", e);
        }

        long delay = calculateDelay(attemptNumber);
        logger.warn("Request failed (attempt {}), retrying in {}ms: {}",
                attemptNumber + 1, delay, e.getMessage());

        try {
            Thread.sleep(delay);
            return attemptRequest(agent, userMessage, threadId, additionalParams, attemptNumber + 1).join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Retry interrupted", ie);
        }
    }

    /**
     * Calculates exponential backoff delay.
     */
    private long calculateDelay(int attemptNumber) {
        return config.getRetryBaseDelayMs() * (long) Math.pow(2, attemptNumber);
    }

    /**
     * Creates a delayed CompletableFuture.
     */
    private CompletableFuture<Void> delayedCompletion(long delay, TimeUnit unit) {
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(unit.toMillis(delay));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ==================== FILE OPERATIONS ====================

    /**
     * Uploads a file to OpenAI/Azure for use with assistants, fine-tuning, etc.
     * Uses round-robin load balancing across configured instances.
     * Returns an encoded file reference (format: "instanceIndex_fileId") for multi-instance tracking.
     *
     * @param filePath Path to the file to upload
     * @param purpose  Purpose of the file (e.g., "assistants", "fine-tune", "batch")
     * @return CompletableFuture with encoded file reference (e.g., "0_file-abc123")
     */
    public CompletableFuture<String> uploadFile(
            java.nio.file.Path filePath,
            String purpose) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Use global round-robin for file uploads
                int instanceIdx = globalInstanceIndex.getAndUpdate(i -> (i + 1) % instances.size());
                Instance instance = instances.get(instanceIdx);

                logger.debug("Uploading file {} to instance {} with purpose: {}",
                        filePath.getFileName(), instanceIdx, purpose);

                Map<String, String> formFields = new HashMap<>();
                formFields.put("purpose", purpose);

                io.github.sashirestela.openai.domain.file.FileResponse response = httpHelper.postMultipart(
                        instance, ProviderConfig.Endpoint.FILES,
                        filePath, formFields,
                        io.github.sashirestela.openai.domain.file.FileResponse.class).join();

                String fileId = response.getId();
                logger.info("File uploaded: {} -> {} on instance {}",
                        filePath.getFileName(), fileId, instanceIdx);

                // Encode instance index for multi-instance tracking (like threads/vector stores)
                return encodeWithInstance(instanceIdx, fileId);

            } catch (Exception e) {
                logger.error("Failed to upload file: {}", filePath, e);
                throw new RuntimeException("File upload failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Uploads a file for use with assistants (convenience method).
     * Returns an encoded file reference (format: "instanceIndex_fileId").
     *
     * @param filePath Path to the file to upload
     * @return CompletableFuture with encoded file reference
     */
    public CompletableFuture<String> uploadFileForAssistants(java.nio.file.Path filePath) {
        return uploadFile(filePath, "assistants");
    }

    /**
     * Extracts the actual file ID from an encoded file reference.
     * Format: "instanceIndex_fileId" → returns fileId
     * Plain ID → returns as-is
     */
    public String extractFileId(String fileRef) {
        return extractVectorStoreId(fileRef); // Same extraction logic
    }

    /**
     * Deletes a file from OpenAI/Azure.
     * Accepts either encoded file reference ("instanceIndex_fileId") or plain file ID.
     *
     * @param fileRef File reference (encoded or plain)
     * @return CompletableFuture that completes when deletion is done
     */
    public CompletableFuture<Boolean> deleteFile(String fileRef) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIndex = extractInstanceIndex(fileRef);
                String actualFileId = extractFileId(fileRef);

                Instance instance = instances.get(instanceIndex);
                Map<String, String> pathParams = new HashMap<>();
                pathParams.put("fileId", actualFileId);

                httpHelper.delete(instance, ProviderConfig.Endpoint.FILE, null, pathParams).join();
                logger.info("File deleted: {} from instance {}", actualFileId, instanceIndex);
                return true;

            } catch (Exception e) {
                logger.error("Failed to delete file: {}", fileRef, e);
                return false;
            }
        });
    }

    // ==================== VECTOR STORE OPERATIONS ====================

    /**
     * Creates a vector store with file attachments.
     * For Azure multi-instance, the returned reference encodes the instance index.
     *
     * @param name    Vector store name
     * @param fileIds List of file references (can be "instanceIndex_fileId" or plain ID).
     *                The vector store will be created on the same instance as the first file.
     * @return CompletableFuture with vector store reference.
     *         Format: "instanceIndex_vectorStoreId" for Azure multi-instance, plain ID otherwise.
     *         Example: "2_vs_abc123" means vector store vs_abc123 on instance 2.
     */
    public CompletableFuture<String> createVectorStore(String name, List<String> fileIds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Extract actual file IDs and determine which instance to use
                // Files must be on the same instance as the vector store
                int instIndex;
                List<String> actualFileIds;

                if (fileIds != null && !fileIds.isEmpty()) {
                    // Use instance from first file reference
                    instIndex = extractInstanceIndex(fileIds.get(0));
                    // Extract actual file IDs from all references
                    actualFileIds = fileIds.stream()
                            .map(this::extractFileId)
                            .collect(java.util.stream.Collectors.toList());
                } else {
                    // No files - use round-robin
                    instIndex = globalInstanceIndex.getAndUpdate(i -> (i + 1) % instances.size());
                    actualFileIds = fileIds;
                }

                VectorStoreRequest request = VectorStoreRequest.builder()
                        .name(name)
                        .fileIds(actualFileIds)
                        .build();

                Instance instance = instances.get(instIndex);
                io.github.sashirestela.openai.domain.assistant.VectorStore vectorStore = httpHelper.post(
                        instance, ProviderConfig.Endpoint.VECTOR_STORES, null,
                        request, io.github.sashirestela.openai.domain.assistant.VectorStore.class).join();
                String vectorStoreId = vectorStore.getId();

                logger.info("Created vector store: {} ({}) on instance {}", name, vectorStoreId, instIndex);

                // Encode instance index for multi-instance tracking
                if (instances.size() > 1) {
                    return instIndex + "_" + vectorStoreId;
                } else {
                    return vectorStoreId;
                }

            } catch (Exception e) {
                logger.error("Failed to create vector store: {}", name, e);
                throw new RuntimeException("Failed to create vector store", e);
            }
        });
    }

    /**
     * Deletes a vector store.
     *
     * @param vectorStoreRef Vector store reference (can be "instanceIndex_vectorStoreId" or plain ID)
     * @return CompletableFuture with deletion result
     */
    public CompletableFuture<Boolean> deleteVectorStore(String vectorStoreRef) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIndex = extractInstanceIndex(vectorStoreRef);
                String actualVectorStoreId = extractVectorStoreId(vectorStoreRef);

                Instance instance = instances.get(instanceIndex);
                Map<String, String> pathParams = new HashMap<>();
                pathParams.put("vectorStoreId", actualVectorStoreId);
                httpHelper.delete(instance, ProviderConfig.Endpoint.VECTOR_STORES, null, pathParams).join();
                logger.info("Deleted vector store: {} from instance {}", actualVectorStoreId, instanceIndex);
                return true;
            } catch (Exception e) {
                logger.error("Failed to delete vector store: {}", vectorStoreRef, e);
                return false;
            }
        });
    }

    /**
     * Extracts instance index from a reference string.
     * Format: "instanceIndex_id" → returns instanceIndex
     * Plain ID → returns 0 (default instance)
     */
    private int extractInstanceIndex(String ref) {
        if (ref == null || !ref.contains("_")) {
            return 0;
        }
        int underscoreIndex = ref.indexOf('_');
        try {
            return Integer.parseInt(ref.substring(0, underscoreIndex));
        } catch (NumberFormatException e) {
            logger.warn("Failed to parse instance index from ref: {}", ref);
            return 0;
        }
    }

    /**
     * Extracts the actual ID from a reference string.
     * Format: "instanceIndex_id" → returns id
     * Plain ID → returns as-is
     */
    private String extractVectorStoreId(String ref) {
        if (ref == null || !ref.contains("_")) {
            return ref;
        }
        int underscoreIndex = ref.indexOf('_');
        return ref.substring(underscoreIndex + 1);
    }

    /**
     * Extracts thread ID from a thread reference.
     * Same logic as extractVectorStoreId, but named for clarity.
     */
    private String extractThreadId(String threadRef) {
        return extractVectorStoreId(threadRef); // Same extraction logic
    }

    /**
     * Extracts response text from thread messages.
     * Gets the first assistant message and extracts its text content.
     */
    private String extractResponseFromMessages(io.github.sashirestela.openai.common.Page<io.github.sashirestela.openai.domain.assistant.ThreadMessage> messages) {
        if (messages == null || messages.getData().isEmpty()) {
            return "";
        }

        // Get first message (most recent)
        var message = messages.getData().get(0);
        if (message.getContent() == null || message.getContent().isEmpty()) {
            return "";
        }

        // Extract text from content parts
        StringBuilder sb = new StringBuilder();
        for (ContentPart part : message.getContent()) {
            if (part instanceof ContentPartTextAnnotation) {
                ContentPartTextAnnotation textPart = (ContentPartTextAnnotation) part;
                if (textPart.getText() != null && textPart.getText().getValue() != null) {
                    sb.append(textPart.getText().getValue());
                }
            }
        }
        return sb.toString();
    }

    // ==================== PERSISTENT THREAD API ====================

    /**
     * Creates a new persistent thread on an instance that has the specified model.
     * Returns an encoded thread ID (format: "instanceIndex_threadId") for persistence.
     * The thread must be explicitly deleted when done using {@link #deleteThread(String)}.
     *
     * For Claude/Anthropic models, creates a virtual thread (in-memory conversation history).
     * For OpenAI models, creates a real OpenAI thread.
     *
     * @param model Model name (e.g., "gpt-4o", "gpt-4o-mini", "claude-sonnet-4-5")
     * @return Encoded thread ID (e.g., "3_thread_abc123" or "2_claude_uuid")
     */
    public CompletableFuture<String> createThread(String model) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Get instance that has this model (model-aware round-robin)
                int instIndex = getNextInstanceForModel(model);
                Instance instance = instances.get(instIndex);

                // Check if this is a Claude/Anthropic instance
                if (instance.getProvider() == Provider.AZURE_ANTHROPIC) {
                    // Create virtual thread (in-memory only)
                    String threadId = "claude_" + java.util.UUID.randomUUID().toString();
                    claudeThreads.put(threadId, new ArrayList<>());

                    logger.debug("Created virtual Claude thread {} on instance {} (model: {})",
                            threadId, instIndex, model);

                    return encodeWithInstance(instIndex, threadId);
                }

                // OpenAI: create real thread via HttpHelper
                io.github.sashirestela.openai.domain.assistant.Thread thread = httpHelper.post(
                        instance, ProviderConfig.Endpoint.THREADS, null,
                        ThreadRequest.builder().build(),
                        io.github.sashirestela.openai.domain.assistant.Thread.class).join();
                String threadId = thread.getId();

                logger.debug("Created thread {} on instance {} (model: {})", threadId, instIndex, model);

                // Encode instance index for persistence across requests
                return encodeWithInstance(instIndex, threadId);
            } catch (Exception e) {
                logger.error("Failed to create thread for model: {}", model, e);
                throw new RuntimeException("Failed to create thread for model: " + model, e);
            }
        });
    }

    /**
     * Sends a message to an existing thread WITHOUT deleting it.
     * Use this for multi-turn conversations where you want to maintain context.
     *
     * For Claude/Anthropic threads, maintains conversation history in-memory.
     * For OpenAI threads, uses the Assistants API.
     *
     * @param agentId   Agent ID
     * @param threadRef Thread reference (from {@link #createThread()})
     * @param message   User message
     * @return CompletableFuture with agent's response
     */
    public CompletableFuture<String> sendMessageToThread(String agentId, String threadRef, String message) {
        Agent agent = agents.get(agentId);
        if (agent == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Agent not found: " + agentId));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIndex = extractInstanceIndex(threadRef);
                String actualThreadId = extractThreadId(threadRef);
                Instance instance = instances.get(instanceIndex);

                // Check if this is a Claude/Anthropic virtual thread
                if (actualThreadId.startsWith("claude_") || instance.getProvider() == Provider.AZURE_ANTHROPIC) {
                    return sendMessageToClaudeThread(agent, actualThreadId, message, instanceIndex);
                }

                // OpenAI: use Assistants API via HttpHelper
                Map<String, String> threadParams = new HashMap<>();
                threadParams.put("threadId", actualThreadId);

                // Add message to thread
                var messageRequest = ThreadMessageRequest.builder()
                        .role(ThreadMessageRole.USER)
                        .content(message)
                        .build();

                httpHelper.post(instance, ProviderConfig.Endpoint.THREAD_MESSAGES, null,
                        messageRequest, io.github.sashirestela.openai.domain.assistant.ThreadMessage.class, threadParams).join();

                // Create run - get assistant ID for this instance
                String assistantId = null;
                if (agent.getAssistantIds() != null && instanceIndex < agent.getAssistantIds().size()) {
                    assistantId = agent.getAssistantIds().get(instanceIndex);
                }
                if (assistantId == null) {
                    throw new IllegalStateException("No assistant ID configured for instance " + instanceIndex
                            + " of agent: " + agent.getId());
                }

                ThreadRunRequest runRequest = ThreadRunRequest.builder()
                        .assistantId(assistantId)
                        .temperature(agent.getTemperature())
                        .build();

                ThreadRun run = httpHelper.post(instance, ProviderConfig.Endpoint.THREAD_RUNS, null,
                        runRequest, ThreadRun.class, threadParams).join();

                // Poll for completion using HttpHelper
                ThreadRun completedRun = pollForCompletion(instance,
                        actualThreadId, run.getId(), agent.getResponseTimeout());

                // Get response
                ThreadMessagesResponse messagesResponse = httpHelper.get(instance, ProviderConfig.Endpoint.THREAD_MESSAGES,
                        null, ThreadMessagesResponse.class, threadParams).join();
                return extractResponseFromMessagesResponse(messagesResponse);

            } catch (Exception e) {
                logger.error("Failed to send message to thread {}", threadRef, e);
                throw new RuntimeException("Failed to send message to thread", e);
            }
        });
    }

    /**
     * Sends a message to a Claude virtual thread.
     * Maintains conversation history in-memory and calls Claude API.
     *
     * @param agent Agent configuration
     * @param threadId Virtual thread ID
     * @param message User message
     * @param instanceIndex Instance to use
     * @return Response text
     */
    private String sendMessageToClaudeThread(Agent agent, String threadId, String message, int instanceIndex) {
        // Get virtual thread history
        List<ClaudeRequest.ClaudeMessage> history = claudeThreads.get(threadId);
        if (history == null) {
            throw new IllegalArgumentException("Claude thread not found: " + threadId);
        }

        // Add user message to history
        history.add(ClaudeRequest.ClaudeMessage.builder()
                .role("user")
                .content(message)
                .build());

        // Build Claude request with full history
        ClaudeRequest request = ClaudeRequest.builder()
                .model(agent.getModel())
                .maxTokens(agent.getMaxTokens() != null ? agent.getMaxTokens() : 4096)
                .system(agent.getInstructions())
                .messages(new ArrayList<>(history))
                .temperature(agent.getTemperature())
                .build();

        // Call Claude API
        ClaudeResponse response = callClaudeAPI(instanceIndex, request);
        String responseText = response.getTextContent();

        // Add assistant response to history
        history.add(ClaudeRequest.ClaudeMessage.builder()
                .role("assistant")
                .content(responseText)
                .build());

        logger.debug("Claude thread {} message exchanged (history: {} messages)",
                threadId, history.size());

        return responseText;
    }

    /**
     * Deletes a persistent thread when conversation is complete.
     * For Claude threads, removes the in-memory conversation history.
     * For OpenAI threads, deletes the thread via API.
     *
     * @param threadRef Thread reference (from {@link #createThread()})
     * @return CompletableFuture with deletion result
     */
    public CompletableFuture<Boolean> deleteThread(String threadRef) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIndex = extractInstanceIndex(threadRef);
                String actualThreadId = extractThreadId(threadRef);

                // Check if Claude virtual thread
                if (actualThreadId.startsWith("claude_")) {
                    boolean removed = claudeThreads.remove(actualThreadId) != null;
                    logger.debug("Deleted virtual Claude thread: {} (success: {})", actualThreadId, removed);
                    return removed;
                }

                // OpenAI: delete via API using HttpHelper
                Instance instance = instances.get(instanceIndex);
                Map<String, String> pathParams = new HashMap<>();
                pathParams.put("threadId", actualThreadId);
                httpHelper.delete(instance, ProviderConfig.Endpoint.THREAD, null, pathParams).join();
                logger.debug("Deleted thread {} from instance {}", actualThreadId, instanceIndex);
                return true;
            } catch (Exception e) {
                logger.error("Failed to delete thread: {}", threadRef, e);
                return false;
            }
        });
    }

    // ==================== VECTOR STORE METHODS ====================

    /**
     * Sends a request to an agent with vector store support.
     *
     * @param agentId       Agent ID
     * @param userMessage   User message
     * @param vectorStoreId Vector store ID
     * @return CompletableFuture with response
     */
    public CompletableFuture<String> requestAgentWithVectorStorage(
            String agentId,
            String userMessage,
            String vectorStoreId) {

        Agent agent = agents.get(agentId);
        if (agent == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Agent not found: " + agentId));
        }

        if (instances.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No OpenAI/Azure instances initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Create thread on instance that has this agent's model
                int instanceIdx = getNextInstanceForModel(agent.getModel());
                Instance instance = instances.get(instanceIdx);
                io.github.sashirestela.openai.domain.assistant.Thread thread = httpHelper.post(
                        instance, ProviderConfig.Endpoint.THREADS, null,
                        ThreadRequest.builder().build(),
                        io.github.sashirestela.openai.domain.assistant.Thread.class).join();
                String threadId = thread.getId();

                // Add message
                Map<String, String> threadParams = new HashMap<>();
                threadParams.put("threadId", threadId);
                var messageRequest = ThreadMessageRequest.builder()
                        .role(ThreadMessageRole.USER)
                        .content(userMessage)
                        .build();
                httpHelper.post(instance, ProviderConfig.Endpoint.THREAD_MESSAGES, null,
                        messageRequest, io.github.sashirestela.openai.domain.assistant.ThreadMessage.class, threadParams).join();

                // Get assistant ID for this specific instance
                String assistantId = null;
                if (agent.getAssistantIds() != null && instanceIdx < agent.getAssistantIds().size()) {
                    assistantId = agent.getAssistantIds().get(instanceIdx);
                }
                if (assistantId == null) {
                    throw new IllegalStateException("No assistant ID configured for instance " + instanceIdx
                            + " of agent: " + agent.getId());
                }
                ThreadRunRequest runRequest = ThreadRunRequest.builder()
                        .assistantId(assistantId)
                        .temperature(agent.getTemperature())
                        .build();

                ThreadRun run = httpHelper.post(instance, ProviderConfig.Endpoint.THREAD_RUNS, null,
                        runRequest, ThreadRun.class, threadParams).join();

                // Poll for completion using HttpHelper
                ThreadRun completedRun = pollForCompletion(instance, threadId, run.getId(),
                        agent.getResponseTimeout());

                if (completedRun.getStatus() != RunStatus.COMPLETED) {
                    throw new RuntimeException("Run failed with status: " + completedRun.getStatus());
                }

                // Get response
                ThreadMessagesResponse messagesResponse = httpHelper.get(instance, ProviderConfig.Endpoint.THREAD_MESSAGES,
                        null, ThreadMessagesResponse.class, threadParams).join();
                if (messagesResponse.getData() == null || messagesResponse.getData().isEmpty()) {
                    throw new RuntimeException("No messages returned");
                }

                return extractMessageContent(messagesResponse.getData().get(0).getContent());

            } catch (Exception e) {
                logger.error("Request with vector storage failed for agent: {}", agentId, e);
                throw new RuntimeException("Request with vector storage failed", e);
            }
        });
    }

    /**
     * Sends a chat completion request (non-Assistant API).
     * Automatically routes to the correct instance based on the model.
     * Supports both OpenAI and Claude/Anthropic models.
     *
     * @param model       Model name (e.g., "gpt-4o", "gpt-4o-mini", "claude-sonnet-4-5")
     * @param messages    List of chat messages
     * @param temperature Temperature (optional, can be null)
     * @return CompletableFuture with response content
     */
    public CompletableFuture<String> requestChatCompletion(
            String model,
            List<ChatMessage> messages,
            Double temperature) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Get instance that has this model deployed
                int instanceIdx = getNextInstanceForModel(model);
                Instance instance = instances.get(instanceIdx);

                // Check if Claude/Anthropic instance
                if (instance.getProvider() == Provider.AZURE_ANTHROPIC) {
                    return executeChatCompletionClaude(model, messages, temperature, instanceIdx);
                }

                // OpenAI: use standard chat completions API via HttpHelper
                ChatRequest.ChatRequestBuilder requestBuilder = ChatRequest.builder()
                        .model(model)
                        .messages(messages);

                if (temperature != null) {
                    requestBuilder.temperature(temperature);
                }

                ChatRequest request = requestBuilder.build();

                // POST to /chat/completions with model in path for Azure
                Chat chatResponse = httpHelper.post(instance, ProviderConfig.Endpoint.CHAT_COMPLETIONS,
                        model, request, Chat.class).join();

                if (chatResponse.getChoices() == null || chatResponse.getChoices().isEmpty()) {
                    throw new RuntimeException("No choices returned in chat completion");
                }

                return chatResponse.getChoices().get(0).getMessage().getContent();

            } catch (Exception e) {
                String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

                // Check for Azure content filter / content policy violation
                if (errorMessage.contains("content_filter") ||
                    errorMessage.contains("content_policy_violation") ||
                    errorMessage.contains("responsibleaipolicyviolation")) {

                    logger.error("❌ Azure content filter blocked the request");
                    logger.error("   Error: {}", e.getMessage());
                    logger.error("   This usually means the prompt triggered Azure's content safety policies");
                    logger.error("   Consider: 1) Rephrasing the prompt, 2) Using a different model, or 3) Reviewing Azure content filter settings");
                    throw new RuntimeException("Content filter violation: " + e.getMessage(), e);
                }

                logger.error("Chat completion request failed", e);
                throw new RuntimeException("Chat completion request failed", e);
            }
        });
    }

    /**
     * Executes a chat completion request for Claude/Anthropic models.
     *
     * @param model Model name (e.g., "claude-sonnet-4-5")
     * @param messages List of chat messages (OpenAI format)
     * @param temperature Temperature
     * @param instanceIndex Instance to use
     * @return Response text
     */
    private String executeChatCompletionClaude(String model, List<ChatMessage> messages,
                                                Double temperature, int instanceIndex) {
        // Extract system prompt (first SystemMessage if exists)
        String systemPrompt = null;
        List<ClaudeRequest.ClaudeMessage> claudeMessages = new ArrayList<>();

        for (ChatMessage msg : messages) {
            if (msg instanceof ChatMessage.SystemMessage && systemPrompt == null) {
                systemPrompt = ((ChatMessage.SystemMessage) msg).getContent().toString();
            } else if (msg instanceof ChatMessage.UserMessage) {
                Object content = ((ChatMessage.UserMessage) msg).getContent();
                claudeMessages.add(ClaudeRequest.ClaudeMessage.builder()
                        .role("user")
                        .content(content != null ? content.toString() : "")
                        .build());
            } else if (msg instanceof ChatMessage.AssistantMessage) {
                Object content = ((ChatMessage.AssistantMessage) msg).getContent();
                claudeMessages.add(ClaudeRequest.ClaudeMessage.builder()
                        .role("assistant")
                        .content(content != null ? content.toString() : "")
                        .build());
            }
        }

        ClaudeRequest request = ClaudeRequest.builder()
                .model(model)
                .maxTokens(4096)
                .system(systemPrompt)
                .messages(claudeMessages)
                .temperature(temperature)
                .build();

        ClaudeResponse response = callClaudeAPI(instanceIndex, request);
        return response.getTextContent();
    }

    /**
     * Sends a structured chat completion request with JSON Schema.
     * Automatically routes to the correct instance based on the model.
     *
     * @param model         Model name (e.g., "gpt-4o")
     * @param messages      List of chat messages
     * @param temperature   Temperature (optional, can be null)
     * @param resultClass   Result class name for schema generation
     * @return CompletableFuture with response content
     */
    public CompletableFuture<String> requestStructuredChatCompletion(
            String model,
            List<ChatMessage> messages,
            Double temperature,
            String resultClass) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                ChatRequest.ChatRequestBuilder requestBuilder = ChatRequest.builder()
                        .model(model)
                        .messages(messages);

                if (temperature != null) {
                    requestBuilder.temperature(temperature);
                }

                // Add response format if result class is specified
                if (resultClass != null && !resultClass.isEmpty() &&
                        config.getAgentResultClassPackage() != null) {
                    ResponseFormat format = JsonSchemaGenerator.createResponseFormat(
                            resultClass,
                            config.getAgentResultClassPackage());
                    requestBuilder.responseFormat(format);
                }

                ChatRequest request = requestBuilder.build();

                // Get instance that has this model deployed
                int instanceIdx = getNextInstanceForModel(model);
                Instance instance = instances.get(instanceIdx);

                // POST to /chat/completions with model in path for Azure
                Chat chatResponse = httpHelper.post(instance, ProviderConfig.Endpoint.CHAT_COMPLETIONS,
                        model, request, Chat.class).join();

                if (chatResponse.getChoices() == null || chatResponse.getChoices().isEmpty()) {
                    throw new RuntimeException("No choices returned in structured chat completion");
                }

                return chatResponse.getChoices().get(0).getMessage().getContent();

            } catch (Exception e) {
                String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

                // Check for Azure content filter / content policy violation
                if (errorMessage.contains("content_filter") ||
                    errorMessage.contains("content_policy_violation") ||
                    errorMessage.contains("responsibleaipolicyviolation")) {

                    logger.error("❌ Azure content filter blocked the structured chat completion request");
                    logger.error("   Error: {}", e.getMessage());
                    logger.error("   This usually means the prompt triggered Azure's content safety policies");
                    logger.error("   Consider: 1) Rephrasing the prompt, 2) Using a different model, or 3) Reviewing Azure content filter settings");
                    throw new RuntimeException("Content filter violation: " + e.getMessage(), e);
                }

                logger.error("Structured chat completion request failed", e);
                throw new RuntimeException("Structured chat completion request failed", e);
            }
        });
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
     * Creates a batch request for processing multiple agent requests asynchronously.
     *
     * @param inputFileId File ID containing batch requests (JSONL format)
     * @param endpoint Endpoint type for the batch
     * @param metadata Optional metadata for the batch
     * @return CompletableFuture with Batch object
     */
    public CompletableFuture<io.github.sashirestela.openai.domain.batch.Batch> createBatch(
            String inputFileId,
            io.github.sashirestela.openai.domain.batch.EndpointType endpoint,
            Map<String, String> metadata) {

        if (instances.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No OpenAI/Azure instances initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                io.github.sashirestela.openai.domain.batch.BatchRequest.BatchRequestBuilder builder =
                        io.github.sashirestela.openai.domain.batch.BatchRequest.builder()
                                .inputFileId(inputFileId)
                                .endpoint(endpoint)
                                .completionWindow(io.github.sashirestela.openai.domain.batch.BatchRequest.CompletionWindowType.T24H);

                if (metadata != null && !metadata.isEmpty()) {
                    builder.metadata(metadata);
                }

                io.github.sashirestela.openai.domain.batch.BatchRequest request = builder.build();
                // Batch API typically uses gpt-4o - route to instance with that model
                int instanceIdx = getNextInstanceForModel("gpt-4o");
                Instance instance = instances.get(instanceIdx);
                io.github.sashirestela.openai.domain.batch.Batch batch = httpHelper.post(
                        instance, ProviderConfig.Endpoint.BATCHES, null,
                        request, io.github.sashirestela.openai.domain.batch.Batch.class).join();

                logger.info("Created batch: {} with status: {}", batch.getId(), batch.getStatus());
                return batch;

            } catch (Exception e) {
                logger.error("Failed to create batch", e);
                throw new RuntimeException("Failed to create batch", e);
            }
        });
    }

    /**
     * Retrieves the status and details of a batch.
     *
     * @param batchId Batch ID
     * @return CompletableFuture with Batch object
     */
    public CompletableFuture<io.github.sashirestela.openai.domain.batch.Batch> getBatch(String batchId) {
        if (instances.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No OpenAI/Azure instances initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIdx = getNextInstanceForModel("gpt-4o");
                Instance instance = instances.get(instanceIdx);
                Map<String, String> pathParams = new HashMap<>();
                pathParams.put("batchId", batchId);
                return httpHelper.get(instance, ProviderConfig.Endpoint.BATCH, null,
                        io.github.sashirestela.openai.domain.batch.Batch.class, pathParams).join();
            } catch (Exception e) {
                logger.error("Failed to get batch: {}", batchId, e);
                throw new RuntimeException("Failed to get batch: " + batchId, e);
            }
        });
    }

    /**
     * Cancels an in-progress batch.
     *
     * @param batchId Batch ID
     * @return CompletableFuture with Batch object showing cancelled status
     */
    public CompletableFuture<io.github.sashirestela.openai.domain.batch.Batch> cancelBatch(String batchId) {
        if (instances.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No OpenAI/Azure instances initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIdx = getNextInstanceForModel("gpt-4o");
                Instance instance = instances.get(instanceIdx);
                Map<String, String> pathParams = new HashMap<>();
                pathParams.put("batchId", batchId);
                // POST to /batches/{batchId}/cancel
                io.github.sashirestela.openai.domain.batch.Batch batch = httpHelper.post(
                        instance, ProviderConfig.Endpoint.BATCH, null,
                        Map.of("action", "cancel"),
                        io.github.sashirestela.openai.domain.batch.Batch.class, pathParams).join();
                logger.info("Cancelled batch: {}", batchId);
                return batch;
            } catch (Exception e) {
                logger.error("Failed to cancel batch: {}", batchId, e);
                throw new RuntimeException("Failed to cancel batch: " + batchId, e);
            }
        });
    }

    /**
     * Lists batches with optional filtering.
     *
     * @param limit Maximum number of batches to return (default 20)
     * @param after Cursor for pagination
     * @return CompletableFuture with list of batches
     */
    public CompletableFuture<io.github.sashirestela.openai.common.Page<io.github.sashirestela.openai.domain.batch.Batch>> listBatches(
            Integer limit,
            String after) {

        if (instances.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No OpenAI/Azure instances initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                int instanceIdx = getNextInstanceForModel("gpt-4o");
                Instance instance = instances.get(instanceIdx);
                // GET /batches - BatchListResponse extends Page directly
                return httpHelper.get(instance, ProviderConfig.Endpoint.BATCHES, null,
                        BatchListResponse.class, null).join();
            } catch (Exception e) {
                logger.error("Failed to list batches", e);
                throw new RuntimeException("Failed to list batches", e);
            }
        });
    }

    /**
     * Polls a batch until it reaches a terminal state (completed, failed, expired, cancelled).
     *
     * @param batchId Batch ID
     * @param pollIntervalSeconds Interval between status checks in seconds
     * @param timeoutSeconds Maximum time to wait in seconds
     * @return CompletableFuture with final Batch object
     */
    public CompletableFuture<io.github.sashirestela.openai.domain.batch.Batch> pollBatchUntilComplete(
            String batchId,
            long pollIntervalSeconds,
            long timeoutSeconds) {

        if (instances.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No OpenAI/Azure instances initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                long timeoutMs = timeoutSeconds * 1000;

                int instanceIdx = getNextInstanceForModel("gpt-4o");
                Instance instance = instances.get(instanceIdx);
                Map<String, String> pathParams = new HashMap<>();
                pathParams.put("batchId", batchId);

                while (true) {
                    io.github.sashirestela.openai.domain.batch.Batch batch = httpHelper.get(
                            instance, ProviderConfig.Endpoint.BATCH, null,
                            io.github.sashirestela.openai.domain.batch.Batch.class, pathParams).join();
                    String status = batch.getStatus();

                    logger.debug("Batch {} status: {}", batchId, status);

                    // Terminal states
                    if ("completed".equals(status) ||
                        "failed".equals(status) ||
                        "expired".equals(status) ||
                        "cancelled".equals(status)) {
                        logger.info("Batch {} reached terminal state: {}", batchId, status);
                        return batch;
                    }

                    // Check timeout
                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        throw new RuntimeException("Batch polling timeout after " + timeoutSeconds + " seconds");
                    }

                    // Wait before next poll
                    Thread.sleep(pollIntervalSeconds * 1000);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Batch polling interrupted", e);
            } catch (Exception e) {
                logger.error("Failed to poll batch: {}", batchId, e);
                throw new RuntimeException("Failed to poll batch: " + batchId, e);
            }
        });
    }

    /**
     * Convenience method to create a batch for chat completion requests.
     * The input file should be in JSONL format with each line containing a batch request.
     *
     * @param inputFileId File ID containing chat completion requests
     * @param metadata Optional metadata
     * @return CompletableFuture with Batch object
     */
    public CompletableFuture<io.github.sashirestela.openai.domain.batch.Batch> createChatCompletionBatch(
            String inputFileId,
            Map<String, String> metadata) {

        return createBatch(
                inputFileId,
                io.github.sashirestela.openai.domain.batch.EndpointType.CHAT_COMPLETIONS,
                metadata);
    }

    /**
     * Generates an image using DALL-E with default settings.
     *
     * @param prompt The text description of the image to generate
     * @return CompletableFuture with base64-encoded image data
     */
    public CompletableFuture<String> generateImage(String prompt) {
        return generateImage(prompt, "dall-e-3", Size.X1024, Quality.STANDARD);
    }

    /**
     * Generates an image using DALL-E with custom settings.
     * Uses round-robin load balancing across configured instances.
     *
     * @param prompt The text description of the image to generate
     * @param model The DALL-E model to use (e.g., "dall-e-3", "dall-e-2")
     * @param size The image size (e.g., Size.X1024, Size.X1792_1024)
     * @param quality The image quality ("standard" or "hd")
     * @return CompletableFuture with base64-encoded image data
     */
    public CompletableFuture<String> generateImage(String prompt, String model, Size size, Quality quality) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Rate limit check
                rateLimiter.tryConsume();

                // Get instance that has this image model deployed
                int instanceIdx = getNextInstanceForModel(model);
                Instance instance = instances.get(instanceIdx);

                // Create image request with b64_json response format
                ImageRequest imageRequest = ImageRequest.builder()
                        .model(model)
                        .prompt(prompt)
                        .size(size)
                        .quality(quality)
                        .n(1)
                        .responseFormat(io.github.sashirestela.openai.domain.image.ImageResponseFormat.B64JSON)
                        .build();

                logger.debug("Generating image with model: {}, size: {}, quality: {}", model, size, quality);

                // Call DALL-E API via HttpHelper
                ImageGenerationResponse response = httpHelper.post(instance, ProviderConfig.Endpoint.IMAGES_GENERATIONS,
                        model, imageRequest, ImageGenerationResponse.class).join();

                if (response == null || response.getData() == null || response.getData().isEmpty()) {
                    throw new RuntimeException("Image generation returned empty response");
                }

                // Extract base64 image data
                String base64Image = response.getData().get(0).getB64Json();
                logger.debug("Image generated successfully (base64 length: {})",
                    base64Image != null ? base64Image.length() : 0);

                return base64Image;

            } catch (Exception e) {
                logger.error("Failed to generate image: {}", e.getMessage());
                throw new RuntimeException("Image generation failed", e);
            }
        });
    }

    // ==================== EMBEDDING GENERATION ====================

    /**
     * Generate embeddings for a given text using the specified model.
     * Supports both OpenAI and Azure OpenAI with automatic load balancing across instances.
     *
     * @param text Text to generate embeddings for
     * @param model Embedding model (e.g., "text-embedding-3-small", "text-embedding-3-large")
     * @return CompletableFuture containing float array of embeddings (e.g., 1536 dimensions)
     */
    public CompletableFuture<float[]> generateEmbedding(String text, String model) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.debug("Generating embedding for text (length: {}) with model: {}", text.length(), model);

                // Find instances that support this model
                List<Instance> compatibleInstances = instances.stream()
                        .filter(i -> i.getDeployedModels().contains(model))
                        .collect(java.util.stream.Collectors.toList());

                if (compatibleInstances.isEmpty()) {
                    throw new IllegalArgumentException(
                            "No instance found with model: " + model + ". Available models: " +
                                    instances.stream()
                                            .flatMap(i -> i.getDeployedModels().stream())
                                            .distinct()
                                            .collect(java.util.stream.Collectors.joining(", ")));
                }

                // Round-robin selection for this model
                AtomicInteger counter = modelIndexes.computeIfAbsent(model, k -> new AtomicInteger(0));
                int index = counter.getAndIncrement() % compatibleInstances.size();
                Instance selectedInstance = compatibleInstances.get(index);

                logger.debug("Selected instance {} for embedding generation (model: {})",
                        selectedInstance.getId(), model);

                // Wait for rate limit (simple spin-wait implementation)
                while (!rateLimiter.tryConsume()) {
                    try {
                        Thread.sleep(10);  // Wait 10ms before retrying
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Rate limiter interrupted", e);
                    }
                }

                // Create embedding request
                io.github.sashirestela.openai.domain.embedding.EmbeddingRequest request =
                        io.github.sashirestela.openai.domain.embedding.EmbeddingRequest.builder()
                                .model(model)
                                .input(text)
                                .build();

                // Call embeddings API via HttpHelper
                logger.debug("Calling embeddings API with model: {} on instance {}",
                        model, selectedInstance.getId());
                EmbeddingResponse response = httpHelper.post(selectedInstance, ProviderConfig.Endpoint.EMBEDDINGS,
                        model, request, EmbeddingResponse.class).join();

                if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                    // Extract embedding vector
                    List<Double> embedding = response.getData().get(0).getEmbedding();

                    // Convert to float array
                    float[] result = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        result[i] = embedding.get(i).floatValue();
                    }

                    logger.debug("Embedding generated successfully ({} dimensions) using instance {}",
                            result.length, selectedInstance.getId());
                    return result;
                } else {
                    throw new RuntimeException("Empty response from embeddings API");
                }

            } catch (Exception e) {
                logger.error("Failed to generate embedding: {}", e.getMessage());
                throw new RuntimeException("Embedding generation failed", e);
            }
        });
    }

    /**
     * Generate embeddings using default model (text-embedding-3-small).
     * Convenience method for common use case.
     *
     * @param text Text to generate embeddings for
     * @return CompletableFuture containing float array of embeddings (1536 dimensions)
     */
    public CompletableFuture<float[]> generateEmbedding(String text) {
        return generateEmbedding(text, "text-embedding-3-small");
    }

    // ==================== INSTANCE ACCESS ====================

    /**
     * Get an instance by ID.
     *
     * @param instanceId Instance ID (e.g., "openai-main", "azure-eastus")
     * @return Instance configuration
     * @throws IllegalArgumentException if instance ID not found
     */
    public Instance getInstance(String instanceId) {
        return instances.stream()
                .filter(i -> i.getId().equals(instanceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Instance not found: " + instanceId + ". Available: " +
                                instances.stream().map(Instance::getId).collect(java.util.stream.Collectors.joining(", "))));
    }

    /**
     * Get the HttpHelper for direct API access.
     * Use this for custom operations not covered by AgentService methods.
     *
     * @return HttpHelper instance
     */
    public HttpHelper getHttpHelper() {
        return httpHelper;
    }

    // ==================== SHUTDOWN ====================

    /**
     * Shuts down the service and releases resources.
     */
    public void shutdown() {
        logger.info("Shutting down AgentService");
        // Add any cleanup logic here if needed
    }

    // ==================== RESPONSE WRAPPER CLASSES ====================
    // These are needed because HttpHelper deserializes directly to POJOs,
    // and the OpenAI API returns list/page structures that need wrappers.

    /**
     * Wrapper for thread messages list response.
     */
    @Data
    public static class ThreadMessagesResponse {
        private List<io.github.sashirestela.openai.domain.assistant.ThreadMessage> data;
        private String firstId;
        private String lastId;
        private boolean hasMore;
    }

    /**
     * Extract response from ThreadMessagesResponse.
     */
    private String extractResponseFromMessagesResponse(ThreadMessagesResponse messagesResponse) {
        if (messagesResponse == null || messagesResponse.getData() == null || messagesResponse.getData().isEmpty()) {
            return "";
        }

        // Get first message (most recent)
        var message = messagesResponse.getData().get(0);
        if (message.getContent() == null || message.getContent().isEmpty()) {
            return "";
        }

        // Extract text from content parts
        StringBuilder sb = new StringBuilder();
        for (ContentPart part : message.getContent()) {
            if (part instanceof ContentPartTextAnnotation) {
                ContentPartTextAnnotation textPart = (ContentPartTextAnnotation) part;
                if (textPart.getText() != null && textPart.getText().getValue() != null) {
                    sb.append(textPart.getText().getValue());
                }
            }
        }
        return sb.toString();
    }

    /**
     * Wrapper for batch list response - extends Page directly.
     */
    @Data
    @lombok.EqualsAndHashCode(callSuper = true)
    public static class BatchListResponse extends io.github.sashirestela.openai.common.Page<io.github.sashirestela.openai.domain.batch.Batch> {
    }

    /**
     * Wrapper for image generation response.
     */
    @Data
    public static class ImageGenerationResponse {
        private List<Image> data;
        private long created;
    }

    /**
     * Wrapper for embedding response.
     */
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
}
