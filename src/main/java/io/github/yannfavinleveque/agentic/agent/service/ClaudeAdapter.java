package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.core.Instance;
import io.github.yannfavinleveque.agentic.agent.core.Provider;
import io.github.yannfavinleveque.agentic.agent.core.ProviderConfig;
import io.github.yannfavinleveque.agentic.agent.exception.AgentException;
import io.github.yannfavinleveque.agentic.agent.exception.ContentFilterException;
import io.github.yannfavinleveque.agentic.agent.exception.RateLimitException;
import io.github.yannfavinleveque.agentic.agent.model.ClaudeRequest;
import io.github.yannfavinleveque.agentic.agent.model.ClaudeResponse;
import io.github.yannfavinleveque.agentic.common.ResponseFormat;
import io.github.yannfavinleveque.agentic.domain.chat.ChatMessage;
import io.github.yannfavinleveque.agentic.support.HttpHelper;
import io.github.yannfavinleveque.agentic.support.JsonSchemaGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter for Claude/Anthropic API calls. Handles message format conversion and structured output
 * support.
 */
public class ClaudeAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeAdapter.class);
    private static final String STRUCTURED_OUTPUTS_BETA_HEADER = "anthropic-beta";
    private static final String STRUCTURED_OUTPUTS_BETA_VALUE = "structured-outputs-2025-11-13";

    private final HttpHelper httpHelper;

    public ClaudeAdapter(HttpHelper httpHelper) {
        this.httpHelper = httpHelper;
    }

    /**
     * Calls Claude API with the given parameters (BLOCKING - legacy). Prefer callClaudeAsync for
     * non-blocking operations.
     *
     * @param instance     Instance to call
     * @param model        Model name
     * @param systemPrompt System prompt (optional)
     * @param messages     Conversation messages
     * @param temperature  Temperature (optional)
     * @param maxTokens    Max tokens (optional, defaults to 4096)
     * @param resultClass  Result class for structured output (optional)
     * @return Claude response
     */
    public ClaudeResponse callClaude(Instance instance, String model, String systemPrompt,
            List<ClaudeRequest.ClaudeMessage> messages,
            Double temperature, Integer maxTokens,
            Class<?> resultClass) {
        return callClaudeAsync(instance, model, systemPrompt, messages, temperature, maxTokens, resultClass).join();
    }

    /**
     * Calls Claude API with the given parameters - FULLY ASYNC. No blocking, returns CompletableFuture
     * immediately.
     *
     * @param instance     Instance to call
     * @param model        Model name
     * @param systemPrompt System prompt (optional)
     * @param messages     Conversation messages
     * @param temperature  Temperature (optional)
     * @param maxTokens    Max tokens (optional, defaults to 4096)
     * @param resultClass  Result class for structured output (optional)
     * @return CompletableFuture with Claude response
     */
    public java.util.concurrent.CompletableFuture<ClaudeResponse> callClaudeAsync(
            Instance instance, String model, String systemPrompt,
            List<ClaudeRequest.ClaudeMessage> messages,
            Double temperature, Integer maxTokens,
            Class<?> resultClass) {
        return callClaudeAsync(instance, model, systemPrompt, messages, temperature, maxTokens, resultClass, null,
                null);
    }

    /**
     * Calls Claude API with tools support - FULLY ASYNC. No blocking, returns CompletableFuture
     * immediately.
     *
     * @param instance     Instance to call
     * @param model        Model name
     * @param systemPrompt System prompt (optional)
     * @param messages     Conversation messages
     * @param temperature  Temperature (optional)
     * @param maxTokens    Max tokens (optional, defaults to 4096)
     * @param resultClass  Result class for structured output (optional)
     * @param tools        List of tools available to the model (optional)
     * @return CompletableFuture with Claude response
     */
    public java.util.concurrent.CompletableFuture<ClaudeResponse> callClaudeAsync(
            Instance instance, String model, String systemPrompt,
            List<ClaudeRequest.ClaudeMessage> messages,
            Double temperature, Integer maxTokens,
            Class<?> resultClass,
            List<ClaudeRequest.ClaudeTool> tools) {
        return callClaudeAsync(instance, model, systemPrompt, messages, temperature, maxTokens, resultClass, tools,
                null);
    }

    /**
     * Calls Claude API with tools and reasoning support - FULLY ASYNC.
     *
     * @param instance        Instance to call
     * @param model           Model name
     * @param systemPrompt    System prompt (optional)
     * @param messages        Conversation messages
     * @param temperature     Temperature (optional)
     * @param maxTokens       Max tokens (optional, defaults to 4096)
     * @param resultClass     Result class for structured output (optional)
     * @param tools           List of tools available to the model (optional)
     * @param reasoningEffort Reasoning effort: null/"none" = disabled, "enabled"/"low"/"medium"/"high"
     *                        = enable thinking
     * @return CompletableFuture with Claude response
     */
    public java.util.concurrent.CompletableFuture<ClaudeResponse> callClaudeAsync(
            Instance instance, String model, String systemPrompt,
            List<ClaudeRequest.ClaudeMessage> messages,
            Double temperature, Integer maxTokens,
            Class<?> resultClass,
            List<ClaudeRequest.ClaudeTool> tools,
            String reasoningEffort) {

        int resolvedMaxTokens = maxTokens != null ? maxTokens : 4096;

        // Prompt caching: only direct Anthropic supports it. Azure Anthropic ignores cache_control
        // markers and would not benefit (and might error on some account configs), so we only flag
        // the system + tools for caching when talking to api.anthropic.com directly.
        boolean cacheable = instance != null && instance.getProvider() == Provider.ANTHROPIC;

        ClaudeRequest.ClaudeRequestBuilder requestBuilder = ClaudeRequest.builder()
                .model(model)
                .maxTokens(resolvedMaxTokens)
                .messages(messages)
                .temperature(temperature);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            if (cacheable) {
                // Wrap the (presumably long, stable) system prompt as a single text block
                // marked ephemeral. Anthropic caches everything up to and including this block.
                requestBuilder.systemBlocks(List.of(ClaudeRequest.ClaudeContentBlock.textCached(systemPrompt)));
            } else {
                requestBuilder.system(systemPrompt);
            }
        }

        // Configure thinking/reasoning if enabled
        // Claude constraints: budget_tokens >= 1024, max_tokens > budget_tokens
        if (reasoningEffort != null && !reasoningEffort.isBlank() && !"none".equalsIgnoreCase(reasoningEffort)) {
            int budgetTokens = Math.max(1024, resolvedMaxTokens);
            // max_tokens must be strictly greater than budget_tokens
            int totalMaxTokens = budgetTokens + resolvedMaxTokens;
            requestBuilder.maxTokens(totalMaxTokens);

            Map<String, Object> thinking = new HashMap<>();
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", budgetTokens);
            requestBuilder.thinking(thinking);
            // When thinking is enabled, temperature must not be set (Claude requirement)
            requestBuilder.temperature(null);
            logger.debug("Enabled Claude thinking: budget_tokens={}, max_tokens={}", budgetTokens, totalMaxTokens);
        }

        // Add tools if provided. For direct Anthropic, mark the last tool with cache_control
        // so the entire tools array is treated as a single cacheable block (Anthropic caches
        // everything from the start of the tools list up to and including the marked entry).
        if (tools != null && !tools.isEmpty()) {
            List<ClaudeRequest.ClaudeTool> toolsToSend = tools;
            if (cacheable) {
                int last = tools.size() - 1;
                ClaudeRequest.ClaudeTool original = tools.get(last);
                if (original.getCacheControl() == null) {
                    ClaudeRequest.ClaudeTool flagged = ClaudeRequest.ClaudeTool.builder()
                            .type(original.getType())
                            .name(original.getName())
                            .description(original.getDescription())
                            .inputSchema(original.getInputSchema())
                            .maxUses(original.getMaxUses())
                            .cacheControl(Map.of("type", "ephemeral"))
                            .build();
                    toolsToSend = new ArrayList<>(tools);
                    toolsToSend.set(last, flagged);
                }
            }
            requestBuilder.tools(toolsToSend);
            logger.debug("Added {} tools to Claude request (cacheable={})", toolsToSend.size(), cacheable);
        }

        // Add output format for structured outputs
        Map<String, String> extraHeaders = null;
        if (resultClass != null) {
            try {
                ResponseFormat format = JsonSchemaGenerator.createResponseFormatFromClass(resultClass);
                requestBuilder.outputFormat(convertToClaudeOutputFormat(format));
                extraHeaders = new HashMap<>();
                extraHeaders.put(STRUCTURED_OUTPUTS_BETA_HEADER, STRUCTURED_OUTPUTS_BETA_VALUE);
                logger.debug("Added structured outputs beta header for class: {}", resultClass.getSimpleName());
            } catch (Exception e) {
                logger.warn("Failed to create JSON schema for Claude: {}", e.getMessage());
            }
        }

        ClaudeRequest request = requestBuilder.build();
        final Map<String, String> headers = extraHeaders;

        return httpHelper.post(
                instance,
                ProviderConfig.Endpoint.CHAT_COMPLETIONS,
                model,
                request,
                ClaudeResponse.class,
                null,
                headers).handle((response, error) -> {
                    if (error != null) {
                        Throwable cause = error instanceof java.util.concurrent.CompletionException
                                ? error.getCause()
                                : error;
                        throw translateException(
                                cause instanceof Exception ? (Exception) cause : new RuntimeException(cause));
                    }
                    return response;
                });
    }

    /**
     * Converts OpenAI-style ChatMessages to Claude messages.
     */
    public List<ClaudeRequest.ClaudeMessage> convertToClaude(List<ChatMessage> messages) {
        String systemPrompt = null;
        List<ClaudeRequest.ClaudeMessage> claudeMessages = new ArrayList<>();

        for (ChatMessage msg : messages) {
            if (msg instanceof ChatMessage.SystemMessage && systemPrompt == null) {
                // First system message becomes the system prompt (handled separately)
                continue;
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

        return claudeMessages;
    }

    /**
     * Extracts system prompt from ChatMessages.
     */
    public String extractSystemPrompt(List<ChatMessage> messages) {
        for (ChatMessage msg : messages) {
            if (msg instanceof ChatMessage.SystemMessage) {
                return ((ChatMessage.SystemMessage) msg).getContent().toString();
            }
        }
        return null;
    }

    /**
     * Converts OpenAI ResponseFormat to Claude output_format structure. Claude uses: {"type":
     * "json_schema", "schema": {...}} OpenAI uses: {"type": "json_schema", "json_schema": {"schema":
     * JsonNode}} Also fixes Map fields: Claude doesn't support additionalProperties with object value,
     * so Maps are converted to string type (JSON serialized).
     *
     * @param format OpenAI ResponseFormat
     * @return Map suitable for Claude API output_format parameter
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> convertToClaudeOutputFormat(ResponseFormat format) {
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
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> schemaMap = mapper.convertValue(schemaNode,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });

            // Fix Map fields for Claude compatibility
            fixSchemaForClaude(schemaMap);

            // Claude format: {"type": "json_schema", "schema": {...}}
            return Map.of(
                    "type", "json_schema",
                    "schema", schemaMap);
        } catch (Exception e) {
            logger.warn("Failed to convert schema for Claude: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Recursively fixes schema for Claude compatibility. Claude doesn't support additionalProperties
     * with object value (like {type: "number"}). Maps are converted to string type (will contain JSON).
     */
    @SuppressWarnings("unchecked")
    private void fixSchemaForClaude(Map<String, Object> schema) {
        if (schema == null)
            return;

        // Check if this is a Map field (has additionalProperties with object value)
        Object additionalProps = schema.get("additionalProperties");
        if (additionalProps instanceof Map) {
            // This is a Map field - convert to string for Claude
            schema.put("type", "string");
            schema.put("additionalProperties", false);
            String desc = (String) schema.get("description");
            if (desc != null) {
                schema.put("description", desc + " (JSON object as string)");
            }
            logger.debug("Converted Map field to string for Claude compatibility");
            return;
        }

        // Recursively process properties
        Object properties = schema.get("properties");
        if (properties instanceof Map) {
            Map<String, Object> propsMap = (Map<String, Object>) properties;
            for (Object value : propsMap.values()) {
                if (value instanceof Map) {
                    fixSchemaForClaude((Map<String, Object>) value);
                }
            }
        }

        // Recursively process array items
        Object items = schema.get("items");
        if (items instanceof Map) {
            fixSchemaForClaude((Map<String, Object>) items);
        }
    }

    /**
     * Translates exceptions to appropriate AgentExceptions.
     */
    private RuntimeException translateException(Exception e) {
        String message = e.getMessage() != null ? e.getMessage() : "";

        // Check for content filter
        if (ContentFilterException.isContentFilterError(message)) {
            return new ContentFilterException(message, "azure-anthropic");
        }

        // Check for rate limit
        if (message.toLowerCase().contains("rate_limit") ||
                message.toLowerCase().contains("429")) {
            return new RateLimitException("Claude API rate limit exceeded: " + message);
        }

        // Generic error
        return new AgentException(AgentException.ErrorCode.CLAUDE_API_ERROR,
                "Claude API call failed: " + message, e);
    }

}
