package io.github.sashirestela.openai.agent.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sashirestela.openai.agent.Agent;
import io.github.sashirestela.openai.agent.AgentDefinition;
import io.github.sashirestela.openai.agent.AgentServiceConfig;
import io.github.sashirestela.openai.agent.HttpHelper;
import io.github.sashirestela.openai.agent.Instance;
import io.github.sashirestela.openai.agent.ProviderConfig;
import io.github.sashirestela.openai.agent.exception.AgentException;
import io.github.sashirestela.openai.agent.exception.AgentNotFoundException;
import io.github.sashirestela.openai.common.ResponseFormat;
import io.github.sashirestela.openai.domain.assistant.Assistant;
import io.github.sashirestela.openai.domain.assistant.AssistantModifyRequest;
import io.github.sashirestela.openai.domain.assistant.AssistantRequest;
import io.github.sashirestela.openai.support.JsonSchemaGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages agent lifecycle: creation, modification, persistence, and OpenAI Assistant sync.
 */
public class AgentManager {

    private static final Logger logger = LoggerFactory.getLogger(AgentManager.class);

    private final AgentServiceConfig config;
    private final HttpHelper httpHelper;
    private final InstanceRouter instanceRouter;
    private final ObjectMapper objectMapper;
    private final Map<String, Agent> agents;

    public AgentManager(AgentServiceConfig config, HttpHelper httpHelper,
                        InstanceRouter instanceRouter, ObjectMapper objectMapper) {
        this.config = config;
        this.httpHelper = httpHelper;
        this.instanceRouter = instanceRouter;
        this.objectMapper = objectMapper;
        this.agents = new ConcurrentHashMap<>();
    }

    // ==================== AGENT CRUD ====================

    /**
     * Gets an agent by ID.
     *
     * @param agentId Agent ID
     * @return Agent
     * @throws AgentNotFoundException if not found
     */
    public Agent getAgent(String agentId) {
        Agent agent = agents.get(agentId);
        if (agent == null) {
            throw new AgentNotFoundException(agentId);
        }
        return agent;
    }

    /**
     * Gets all loaded agents.
     *
     * @return Unmodifiable map of agent ID to Agent
     */
    public Map<String, Agent> getAllAgents() {
        return Collections.unmodifiableMap(agents);
    }

    /**
     * Registers an agent.
     */
    public void registerAgent(Agent agent) {
        agents.put(agent.getId(), agent);
        logger.info("Registered agent: {}", agent.getId());
    }

    /**
     * Lists all registered agents.
     */
    public List<Agent> listAgents() {
        return List.copyOf(agents.values());
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
            return CompletableFuture.failedFuture(new AgentNotFoundException(agentId));
        }

        return CompletableFuture.supplyAsync(() -> {
            // Apply updates
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

    // ==================== AGENT DEFINITION LOADING ====================

    /**
     * Loads agent definitions from JSON files in the configured folder.
     */
    public void loadAgentDefinitions() {
        if (config.getAgentJsonFolderPath() == null || config.getAgentJsonFolderPath().isEmpty()) {
            logger.warn("Agent JSON folder path not configured");
            return;
        }

        try {
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
                    loadAgentFromFile(jsonFile);
                }

                logger.info("Successfully loaded {} agents", agents.size());
            }

        } catch (IOException e) {
            logger.error("Failed to load agent definitions", e);
            throw new AgentException(AgentException.ErrorCode.INVALID_CONFIGURATION,
                    "Failed to load agent definitions: " + e.getMessage(), e);
        }
    }

    private void loadAgentFromFile(Path jsonFile) {
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

    /**
     * Reload all agent definitions from JSON files.
     */
    public CompletableFuture<Void> reloadAgents() {
        if (config.getAgentJsonFolderPath() == null || config.getAgentJsonFolderPath().isEmpty()) {
            throw new IllegalStateException("Cannot reload agents: agentJsonFolderPath is not configured");
        }

        return CompletableFuture.supplyAsync(() -> {
            logger.info("🔄 Reloading agent definitions from: {}", config.getAgentJsonFolderPath());

            int previousCount = agents.size();
            agents.clear();

            loadAgentDefinitions();

            logger.info("✅ Reloaded {} agent definitions (previously: {})", agents.size(), previousCount);
            return null;
        });
    }

    /**
     * Reload a specific agent definition from its JSON file.
     *
     * @param agentId ID of the agent to reload
     * @return CompletableFuture that completes when agent is reloaded
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
                        "agent_" + agentId + "_*.json"
                };

                Path agentFile = null;
                for (String pattern : possibleFilenames) {
                    try (Stream<Path> paths = Files.walk(agentFolder, 1)) {
                        List<Path> matches = paths
                                .filter(Files::isRegularFile)
                                .filter(p -> p.getFileName().toString().matches(pattern.replace("*", ".*")))
                                .collect(Collectors.toList());

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

    // ==================== OPENAI ASSISTANT SYNC ====================

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
            return CompletableFuture.failedFuture(new AgentNotFoundException(agentId));
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
                    agent.setAssistantIds(new ArrayList<>());
                }

                List<Instance> instances = instanceRouter.getInstances();

                // Ensure list has enough capacity for all instances
                while (agent.getAssistantIds().size() < instances.size()) {
                    agent.getAssistantIds().add(null);
                }

                // Create or update assistant ONLY on instances that have this model deployed
                Assistant firstAssistant = null;
                boolean assistantIdsChanged = false;

                for (int i = 0; i < instances.size(); i++) {
                    Instance instance = instances.get(i);

                    // Skip instances that don't have this model deployed
                    if (!instance.hasModel(agent.getModel())) {
                        continue;
                    }

                    Assistant assistant;
                    String existingAssistantId = agent.getAssistantIds().get(i);

                    if (existingAssistantId != null && !existingAssistantId.isEmpty()) {
                        // Try to update existing assistant
                        try {
                            AssistantModifyRequest modifyRequest = AssistantModifyRequest.builder()
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

                            logger.info("✅ Updated assistant on instance {}: {} ({})",
                                    i, agent.getName(), assistant.getId());
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
                        // Create new assistant
                        assistant = httpHelper.post(instance, ProviderConfig.Endpoint.ASSISTANTS,
                                null, request, Assistant.class).join();

                        agent.getAssistantIds().set(i, assistant.getId());
                        assistantIdsChanged = true;

                        logger.info("✅ Created assistant on instance {}: {} ({})",
                                i, agent.getName(), assistant.getId());
                    }

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
                    }
                }

                logger.info("🎯 Agent '{}' (model: {}) successfully created/updated on {} instance(s) that have this model",
                        agent.getName(), agent.getModel(), instancesWithModel);

                return firstAssistant;

            } catch (Exception e) {
                logger.error("Failed to create/update agent: {}", agentId, e);
                throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED,
                        "Failed to create/update agent: " + agentId, e);
            }
        });
    }

    /**
     * Creates or updates ALL loaded agents on ALL configured instances.
     *
     * @return CompletableFuture that completes when all agents are created/updated
     */
    public CompletableFuture<Void> createAllAgents() {
        if (agents.isEmpty()) {
            logger.warn("No agents loaded to create/update");
            return CompletableFuture.completedFuture(null);
        }

        List<Instance> instances = instanceRouter.getInstances();
        logger.info("🚀 Creating/updating {} agents on {} instance(s)...", agents.size(), instances.size());

        return CompletableFuture.supplyAsync(() -> {
            List<CompletableFuture<Assistant>> futures = new ArrayList<>();

            for (String agentId : agents.keySet()) {
                futures.add(createAgent(agentId));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            logger.info("✅ Successfully created/updated all {} agents on all {} instance(s)",
                    agents.size(), instances.size());
            return null;
        });
    }

    /**
     * Saves updated assistant IDs back to the agent's JSON definition file.
     * Only updates the "assistantIds" field, preserving all other fields.
     *
     * @param agent Agent with updated assistant IDs
     * @throws IOException if file cannot be read/written
     */
    @SuppressWarnings("unchecked")
    public void saveAgentDefinitionIds(Agent agent) throws IOException {
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
     * Gets the internal agents map (for AgentServiceV2).
     */
    Map<String, Agent> getAgentsMap() {
        return agents;
    }
}
