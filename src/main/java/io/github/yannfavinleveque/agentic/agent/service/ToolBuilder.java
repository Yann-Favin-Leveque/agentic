package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.core.ProviderConfig;
import io.github.yannfavinleveque.agentic.agent.model.ClaudeRequest;
import io.github.yannfavinleveque.agentic.agent.model.FunctionConfig;
import io.github.yannfavinleveque.agentic.domain.responses.ResponsesRequest;
import io.github.yannfavinleveque.agentic.support.JsonSchemaGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds tool configurations for different LLM providers. Handles conversion between agent
 * configuration and provider-specific tool formats.
 * <p>
 * Supported tools:
 * </p>
 * <ul>
 * <li>Web Search: OpenAI (web_search_preview) / Claude (web_search_20250305)</li>
 * <li>Code Interpreter: OpenAI only</li>
 * <li>File Search: OpenAI (file_search) / mapped from retrieval</li>
 * <li>Custom Functions: Both providers with different schema formats</li>
 * </ul>
 */
public class ToolBuilder {

    /**
     * Name of the auto-injected termination tool for autonomous agent mode.
     * When an autonomous agent calls this function, the loop ends and the arguments
     * are deserialized as the final structured result.
     */
    public static final String TASK_OVER_FUNCTION_NAME = "task_over";

    private static final Logger logger = LoggerFactory.getLogger(ToolBuilder.class);

    /**
     * Builds tools for OpenAI Responses API.
     *
     * @param agent Agent configuration
     * @return List of OpenAI tools, or null if no tools
     */
    public static List<ResponsesRequest.Tool> buildOpenAITools(Agent agent) {
        List<ResponsesRequest.Tool> tools = new ArrayList<>();

        // Web search
        if (Boolean.TRUE.equals(agent.getWebSearch())) {
            tools.add(ResponsesRequest.Tool.webSearch());
            logger.debug("Added web_search_preview tool for agent {}", agent.getId());
        }

        // Code interpreter
        if (Boolean.TRUE.equals(agent.getCodeInterpreter())) {
            tools.add(ResponsesRequest.Tool.codeInterpreter());
            logger.debug("Added code_interpreter tool for agent {}", agent.getId());
        }

        // File search (from retrieval flag)
        if (Boolean.TRUE.equals(agent.getRetrieval())) {
            tools.add(ResponsesRequest.Tool.fileSearch());
            logger.debug("Added file_search tool for agent {}", agent.getId());
        }

        // Custom functions
        if (agent.getFunctions() != null && !agent.getFunctions().isEmpty()) {
            for (FunctionConfig func : agent.getFunctions()) {
                if (!isFunctionEnabledForAgent(func, agent)) {
                    logger.debug("Filtered out function '{}' (group={}) — not in enabledToolGroups {}",
                            func.getName(), func.getGroup(), agent.getEnabledToolGroups());
                    continue;
                }
                try {
                    Map<String, Object> schema = buildFunctionSchema(func);
                    tools.add(ResponsesRequest.Tool.function(
                            func.getName(),
                            func.getDescription(),
                            schema));
                    logger.debug("Added function tool '{}' for agent {}", func.getName(), agent.getId());
                } catch (Exception e) {
                    logger.warn("Failed to build function tool '{}': {}", func.getName(), e.getMessage());
                }
            }
        }

        return tools.isEmpty() ? null : tools;
    }

    /**
     * Shared enabledToolGroups filter. A function is enabled when its group is null / blank /
     * "default", OR the agent's enabledToolGroups set is null (legacy / unset), OR the group
     * is explicitly present in enabledToolGroups. Used by both the OpenAI Responses and Claude
     * Messages tool-array builders so single-shot (non-autonomous) callers get the same gating
     * as the autonomous-loop path in {@code AutonomousAgentRunner.applyGroupFilter}.
     */
    public static boolean isFunctionEnabledForAgent(FunctionConfig func, Agent agent) {
        String g = func.getGroup();
        if (g == null || g.isBlank() || "default".equals(g)) return true;
        java.util.Set<String> enabledGroups = agent.getEnabledToolGroups();
        if (enabledGroups == null) return true; // no filter configured
        return enabledGroups.contains(g);
    }

    /**
     * Builds tools for Claude Messages API.
     *
     * @param agent Agent configuration
     * @return List of Claude tools, or null if no tools
     */
    public static List<ClaudeRequest.ClaudeTool> buildClaudeTools(Agent agent) {
        List<ClaudeRequest.ClaudeTool> tools = new ArrayList<>();

        // Web search
        if (Boolean.TRUE.equals(agent.getWebSearch())) {
            tools.add(ClaudeRequest.ClaudeTool.webSearch());
            logger.debug("Added web_search_20250305 tool for agent {}", agent.getId());
        }

        // Code interpreter - not supported by Claude
        if (Boolean.TRUE.equals(agent.getCodeInterpreter())) {
            logger.warn("Code interpreter not supported by Claude, ignoring for agent {}", agent.getId());
        }

        // Custom functions
        if (agent.getFunctions() != null && !agent.getFunctions().isEmpty()) {
            for (FunctionConfig func : agent.getFunctions()) {
                if (!isFunctionEnabledForAgent(func, agent)) {
                    logger.debug("Filtered out function '{}' (group={}) — not in enabledToolGroups {}",
                            func.getName(), func.getGroup(), agent.getEnabledToolGroups());
                    continue;
                }
                try {
                    Map<String, Object> schema = buildFunctionSchema(func);
                    tools.add(ClaudeRequest.ClaudeTool.function(
                            func.getName(),
                            func.getDescription(),
                            schema));
                    logger.debug("Added function tool '{}' for agent {}", func.getName(), agent.getId());
                } catch (Exception e) {
                    logger.warn("Failed to build Claude function tool '{}': {}", func.getName(), e.getMessage());
                }
            }
        }

        return tools.isEmpty() ? null : tools;
    }

    /**
     * Builds tools based on the model's provider.
     *
     * @param agent Agent configuration
     * @return Object representing tools (provider-specific type)
     */
    public static Object buildToolsForModel(Agent agent) {
        if (ProviderConfig.isAnthropicModel(agent.getModel())) {
            return buildClaudeTools(agent);
        } else {
            return buildOpenAITools(agent);
        }
    }

    /**
     * Checks if an agent has any tools configured.
     *
     * @param agent Agent configuration
     * @return true if agent has tools
     */
    public static boolean hasTools(Agent agent) {
        return Boolean.TRUE.equals(agent.getWebSearch())
                || Boolean.TRUE.equals(agent.getCodeInterpreter())
                || Boolean.TRUE.equals(agent.getRetrieval())
                || (agent.getFunctions() != null && !agent.getFunctions().isEmpty());
    }

    // ==================== PRIVATE METHODS ====================

    /**
     * Builds JSON schema for a function from its configuration.
     * Uses null for parameterClassPackage (FQCN only).
     *
     * @param func Function configuration
     * @return JSON schema map
     */
    private static Map<String, Object> buildFunctionSchema(FunctionConfig func) {
        return buildFunctionSchema(func, null);
    }

    /**
     * Builds JSON schema for a function from its configuration.
     * Supports both FQCN and simple class names with package resolution.
     *
     * @param func                  Function configuration
     * @param parameterClassPackage Optional package for simple class names (can be null)
     * @return JSON schema map
     */
    public static Map<String, Object> buildFunctionSchema(FunctionConfig func, String parameterClassPackage) {
        // If parameterClass is specified, generate schema from class (takes precedence)
        if (func.getParameterClass() != null && !func.getParameterClass().isEmpty()) {
            String resolvedClassName = AgentServiceConfig.resolveClassName(
                    func.getParameterClass(), parameterClassPackage);

            if (resolvedClassName != null) {
                try {
                    Class<?> paramClass = Class.forName(resolvedClassName);
                    return JsonSchemaGenerator.createFunctionSchemaFromClass(paramClass);
                } catch (ClassNotFoundException e) {
                    logger.warn("Parameter class not found for function '{}': {} (resolved: {})",
                            func.getName(), func.getParameterClass(), resolvedClassName);
                }
            } else {
                logger.warn("Cannot resolve parameter class '{}' for function '{}' - " +
                                "use FQCN or configure functionParameterClassPackage",
                        func.getParameterClass(), func.getName());
            }
        }

        // If inline parameters schema is specified, use it
        if (func.getParameters() != null && !func.getParameters().isEmpty()) {
            return func.getParameters();
        }

        // Default: empty object schema (no parameters)
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of(),
                "additionalProperties", false);
    }

}
