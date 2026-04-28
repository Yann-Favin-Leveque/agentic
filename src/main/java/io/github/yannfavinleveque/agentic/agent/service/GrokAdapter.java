package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.core.Agent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapter for xAI Grok API. Grok exposes an OpenAI-compatible /v1/chat/completions
 * endpoint at api.x.ai, so request/response shapes mirror OpenAI's chat completions API.
 * This adapter centralizes the small differences:
 * <ul>
 *   <li>{@code reasoning_effort} is only valid for reasoning-capable models
 *       ({@code grok-3-mini}, {@code grok-4*}). Sending it on other models
 *       triggers HTTP 400 -&gt; we filter it.</li>
 *   <li>Live Search (xAI proprietary {@code search_parameters}) is NOT supported
 *       in this version.</li>
 *   <li>Grok also speaks Anthropic Messages format on {@code /v1/messages}, but
 *       we standardize on the OpenAI shape to keep one code path.</li>
 * </ul>
 *
 * <p>This class holds only static helpers and feature flags. All HTTP calls are
 * performed by {@link UnifiedRequestService}.</p>
 */
public final class GrokAdapter {

    private GrokAdapter() {
        // Utility class
    }

    /** Known Grok model name prefixes (lowercased). */
    private static final Set<String> GROK_MODEL_PREFIXES = new HashSet<>(Arrays.asList(
            "grok-"));

    /**
     * Reasoning-capable Grok models. Other Grok models reject {@code reasoning_effort}
     * with HTTP 400.
     */
    private static final Set<String> GROK_REASONING_PREFIXES = new HashSet<>(Arrays.asList(
            "grok-3-mini",
            "grok-4"));

    /**
     * Returns true if the given model name should be routed via Grok
     * (i.e. its prefix matches a known Grok family).
     *
     * @param model Model name, e.g. "grok-4-fast"
     * @return true if this is a Grok-family model
     */
    public static boolean isGrokModel(String model) {
        if (model == null) {
            return false;
        }
        String lower = model.toLowerCase();
        for (String prefix : GROK_MODEL_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the given Grok model accepts the {@code reasoning_effort}
     * parameter (currently {@code grok-3-mini} and {@code grok-4*}). Other Grok
     * models reject it with HTTP 400.
     */
    public static boolean isReasoningModel(String model) {
        if (model == null) {
            return false;
        }
        String lower = model.toLowerCase();
        for (String prefix : GROK_REASONING_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the Grok chat completions request body from agent + messages.
     * Output shape mirrors OpenAI's /v1/chat/completions.
     *
     * <p>Behavior parallels {@link MistralAdapter#buildRequestBody} but adds
     * {@code reasoning_effort} only for reasoning-capable models.</p>
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

        body.put("max_tokens", agent.getMaxTokens() != null ? agent.getMaxTokens() : 32768);

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        if (responseFormat != null) {
            body.put("response_format", responseFormat);
        }

        // reasoning_effort only on reasoning-capable models
        if (isReasoningModel(agent.getModel())
                && agent.getReasoningEffort() != null
                && !agent.getReasoningEffort().isEmpty()) {
            body.put("reasoning_effort", agent.getReasoningEffort());
        }

        return body;
    }
}
