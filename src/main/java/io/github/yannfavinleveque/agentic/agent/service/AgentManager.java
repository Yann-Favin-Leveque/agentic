package io.github.yannfavinleveque.agentic.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.core.Instance;
import io.github.yannfavinleveque.agentic.agent.core.ProviderConfig;
import io.github.yannfavinleveque.agentic.agent.exception.AgentException;
import io.github.yannfavinleveque.agentic.agent.exception.AgentNotFoundException;
import io.github.yannfavinleveque.agentic.agent.model.AgentDefinition;
import io.github.yannfavinleveque.agentic.common.ResponseFormat;
import io.github.yannfavinleveque.agentic.domain.assistant.Assistant;
import io.github.yannfavinleveque.agentic.domain.assistant.AssistantModifyRequest;
import io.github.yannfavinleveque.agentic.domain.assistant.AssistantRequest;
import io.github.yannfavinleveque.agentic.support.HttpHelper;
import io.github.yannfavinleveque.agentic.support.JsonSchemaGenerator;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages agent lifecycle: creation, modification, persistence, and OpenAI Assistant sync. Uses
 * TRUE NON-BLOCKING async patterns - no .join() calls that block threads.
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
     * Gets an agent by ID, or creates a default agent if the ID is a valid model name.
     * <p>
     * Supports direct model names with optional tool suffixes:
     * <ul>
     *   <li>{@code gpt-4o} - Basic model</li>
     *   <li>{@code gpt-4o-websearch} - Model with web search enabled</li>
     *   <li>{@code gpt-4o-codeinterpreter} - Model with code interpreter enabled</li>
     *   <li>{@code claude-sonnet-4-5} - Claude model</li>
     *   <li>{@code claude-sonnet-4-5-websearch} - Claude with web search</li>
     * </ul>
     * </p>
     *
     * @param agentIdOrModel Agent ID or model name (with optional -websearch/-codeinterpreter suffix)
     * @return Agent instance
     * @throws AgentNotFoundException if not a registered agent and not a valid model name
     */
    public Agent getAgent(String agentIdOrModel) {
        // First check if it's a registered agent
        Agent agent = agents.get(agentIdOrModel);
        if (agent != null) {
            return agent;
        }

        // Try to create a default agent from model name
        Agent defaultAgent = createDefaultAgentFromModel(agentIdOrModel);
        if (defaultAgent != null) {
            return defaultAgent;
        }

        throw new AgentNotFoundException(agentIdOrModel);
    }

    /**
     * Creates a default agent from a model name. Supports tool suffixes like -websearch, -codeinterpreter.
     * Returns null if the model is not available on any instance.
     */
    private Agent createDefaultAgentFromModel(String modelSpec) {
        // Parse model spec: "gpt-4o-websearch" -> model="gpt-4o", webSearch=true
        String model = modelSpec;
        boolean webSearch = false;
        boolean codeInterpreter = false;

        if (modelSpec.endsWith("-websearch")) {
            model = modelSpec.substring(0, modelSpec.length() - "-websearch".length());
            webSearch = true;
        } else if (modelSpec.endsWith("-codeinterpreter")) {
            model = modelSpec.substring(0, modelSpec.length() - "-codeinterpreter".length());
            codeInterpreter = true;
        }

        // Check if this model is available on any instance
        if (!instanceRouter.hasModel(model)) {
            return null;
        }

        // Create ephemeral default agent (not cached)
        logger.debug("Creating default agent for model: {} (webSearch={}, codeInterpreter={})",
                model, webSearch, codeInterpreter);

        return Agent.builder()
                .id("__default_" + modelSpec + "__")
                .name("Default " + model + " Agent")
                .model(model)
                .webSearch(webSearch)
                .codeInterpreter(codeInterpreter)
                .build();
    }

    /**
     * Checks if the given string is a registered agent ID.
     */
    public boolean hasAgent(String agentId) {
        return agents.containsKey(agentId);
    }

    /**
     * Gets all loaded agents.
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
     * Removes a registered agent by ID.
     *
     * @param agentId Agent ID to remove
     */
    public void removeAgent(String agentId) {
        agents.remove(agentId);
        logger.debug("Removed agent: {}", agentId);
    }

    /**
     * Lists all registered agents.
     */
    public List<Agent> listAgents() {
        return List.copyOf(agents.values());
    }

    /**
     * Modifies an existing agent's configuration.
     */
    public CompletableFuture<Agent> modifyAgent(String agentId, Map<String, Object> updates) {
        Agent agent = agents.get(agentId);
        if (agent == null) {
            return CompletableFuture.failedFuture(new AgentNotFoundException(agentId));
        }

        // Apply updates (synchronous, no I/O)
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
        return CompletableFuture.completedFuture(agent);
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
                    .responseTimeout(
                            definition.getResponseTimeout() != null ? definition.getResponseTimeout().longValue()
                                    : config.getDefaultResponseTimeout())
                    .retrieval(definition.getRetrieval() != null ? definition.getRetrieval() : false)
                    .isOpenAI(definition.getIsOpenAI() != null ? definition.getIsOpenAI() : true)
                    .maxTokens(definition.getMaxTokens())
                    // New V2 fields
                    .webSearch(definition.getWebSearch() != null ? definition.getWebSearch() : false)
                    .codeInterpreter(definition.getCodeInterpreter() != null ? definition.getCodeInterpreter() : false)
                    .functions(definition.getFunctions())
                    .description(definition.getDescription())
                    .autonomous(definition.getAutonomous() != null ? definition.getAutonomous() : false)
                    .maxIterations(definition.getMaxIterations() != null ? definition.getMaxIterations() : 25)
                    .maxToolTokenOutput(definition.getMaxToolTokenOutput())
                    .reasoningEffort(definition.getReasoningEffort())
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
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Cannot reload agents: agentJsonFolderPath is not configured"));
        }

        logger.info("🔄 Reloading agent definitions from: {}", config.getAgentJsonFolderPath());

        int previousCount = agents.size();
        agents.clear();

        loadAgentDefinitions();

        logger.info("✅ Reloaded {} agent definitions (previously: {})", agents.size(), previousCount);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Reload a specific agent definition from its JSON file.
     */
    public CompletableFuture<Void> reloadAgent(String agentId) {
        if (config.getAgentJsonFolderPath() == null || config.getAgentJsonFolderPath().isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Cannot reload agent: agentJsonFolderPath is not configured"));
        }

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
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Agent file not found for ID: " + agentId +
                                ". Tried patterns: " + String.join(", ", possibleFilenames)));
            }

            // Read and parse JSON
            String jsonContent = Files.readString(agentFile);
            Agent agent = objectMapper.readValue(jsonContent, Agent.class);

            // Update agents map
            agents.put(agentId, agent);

            logger.info("✅ Reloaded agent {} from: {}", agentId, agentFile.getFileName());
            return CompletableFuture.completedFuture(null);

        } catch (IOException e) {
            logger.error("❌ Failed to reload agent {}: {}", agentId, e.getMessage(), e);
            return CompletableFuture.failedFuture(new RuntimeException("Failed to reload agent: " + agentId, e));
        }
    }

    /**
     * Saves updated assistant IDs back to the agent's JSON definition file.
     * @deprecated No longer needed with stateless Responses API. Kept for backwards compatibility.
     */
    @Deprecated
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
