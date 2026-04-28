package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.core.Agent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapter for DeepSeek API (api.deepseek.com). DeepSeek exposes an OpenAI-compatible
 * /v1/chat/completions endpoint, so request shapes mirror OpenAI's. Notable specificity:
 *
 * <ul>
 *   <li>{@code deepseek-reasoner} returns a non-standard {@code reasoning_content} field
 *       on the assistant message, separate from {@code content}. It carries the visible
 *       chain-of-thought. The integration layer should extract it via
 *       {@link #extractReasoningContent(Map)} and either log it, surface it via the
 *       agent result, or strip it before deserialization.</li>
 *   <li>No {@code reasoning_effort} request parameter — reasoning is implicit when
 *       calling {@code deepseek-reasoner}.</li>
 *   <li>Context caching is automatic; cache hits show up in
 *       {@code usage.prompt_cache_hit_tokens}.</li>
 * </ul>
 *
 * <p>This class holds only static helpers and feature flags. All HTTP calls are
 * performed by {@link UnifiedRequestService}.</p>
 */
public final class DeepSeekAdapter {

    private DeepSeekAdapter() {
        // Utility class
    }

    /** Known DeepSeek model name prefixes (lowercased). */
    private static final Set<String> DEEPSEEK_MODEL_PREFIXES = new HashSet<>(Arrays.asList(
            "deepseek-"));

    /** Models that emit a {@code reasoning_content} field on responses. */
    private static final Set<String> DEEPSEEK_REASONING_PREFIXES = new HashSet<>(Arrays.asList(
            "deepseek-reasoner"));

    /**
     * Returns true if the given model name should be routed via DeepSeek
     * (i.e. its prefix matches a known DeepSeek family).
     *
     * @param model Model name, e.g. "deepseek-chat"
     * @return true if this is a DeepSeek-family model
     */
    public static boolean isDeepSeekModel(String model) {
        if (model == null) {
            return false;
        }
        String lower = model.toLowerCase();
        for (String prefix : DEEPSEEK_MODEL_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the given DeepSeek model is a reasoning model
     * (currently only {@code deepseek-reasoner}). Reasoning models emit a
     * {@code reasoning_content} field on responses that carries the visible
     * chain-of-thought.
     */
    public static boolean isReasoningModel(String model) {
        if (model == null) {
            return false;
        }
        String lower = model.toLowerCase();
        for (String prefix : DEEPSEEK_REASONING_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the DeepSeek chat completions request body. Same shape as Mistral/Grok,
     * minus reasoning_effort (DeepSeek doesn't accept it).
     *
     * @param agent          Agent (provides model, temperature, maxTokens, ...)
     * @param messages       Pre-built message list (each map already has role + content)
     * @param tools          Tool definitions in OpenAI format, or null
     * @param responseFormat response_format object (e.g. for json_schema), or null
     * @return mutable request body ready to be POSTed
     */
    public static Map<String, Object> buildRequestBody(
            Agent agent,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            Map<String, Object> responseFormat) {

        Map<String, Object> body = new HashMap<>();
        body.put("model", agent.getModel());
        body.put("messages", messages);

        if (agent.getTemperature() != null) {
            body.put("temperature", agent.getTemperature());
        }

        body.put("max_tokens", agent.getMaxTokens() != null ? agent.getMaxTokens() : 4096);

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        if (responseFormat != null) {
            body.put("response_format", responseFormat);
        }

        return body;
    }

    /**
     * Extracts the {@code reasoning_content} field from an assistant message.
     * Returns null if absent. Use this on the parsed JSON map of
     * {@code response.choices[0].message} to recover the chain-of-thought from
     * {@code deepseek-reasoner}.
     *
     * @param messageMap the assistant message map (Jackson-deserialized)
     * @return reasoning content as a String, or null
     */
    public static String extractReasoningContent(Map<String, Object> messageMap) {
        if (messageMap == null) {
            return null;
        }
        Object v = messageMap.get("reasoning_content");
        return v == null ? null : v.toString();
    }
}
