package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.model.Message;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapter for Mistral AI API. Mistral exposes an OpenAI-compatible
 * /v1/chat/completions endpoint, so request/response shapes mirror OpenAI's
 * chat completions API. This adapter centralizes the small differences:
 * - No /v1/responses (Responses API) endpoint -> always route to chat/completions
 * - No native web_search or code_interpreter tools
 * - 'developer' role is not supported -> map to 'system'
 * - Reasoning ('magistral-*' family) uses 'prompt_mode: "reasoning"' rather
 *   than OpenAI's reasoning_effort
 *
 * <p>This class holds only static helpers and feature flags. All HTTP calls are
 * performed by {@link UnifiedRequestService}.</p>
 */
public final class MistralAdapter {

    private MistralAdapter() {
        // Utility class
    }

    /** Known Mistral model name prefixes (lowercased). */
    private static final Set<String> MISTRAL_MODEL_PREFIXES = new HashSet<>(Arrays.asList(
            "mistral-",
            "pixtral-",
            "codestral-",
            "magistral-",
            "ministral-",
            "open-mistral-",
            "open-mixtral-"));

    /** Reasoning model prefixes inside the Mistral family. */
    private static final Set<String> MISTRAL_REASONING_PREFIXES = new HashSet<>(Arrays.asList(
            "magistral-"));

    /**
     * Returns true if the given model name should be routed via Mistral
     * (i.e. its prefix matches a known Mistral family).
     *
     * @param model Model name, e.g. "mistral-large-latest"
     * @return true if this is a Mistral-family model
     */
    public static boolean isMistralModel(String model) {
        if (model == null) {
            return false;
        }
        String lower = model.toLowerCase();
        for (String prefix : MISTRAL_MODEL_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the given Mistral model is a reasoning model (Magistral).
     * Reasoning models accept a 'prompt_mode' parameter and may emit thinking
     * traces in the response.
     */
    public static boolean isReasoningModel(String model) {
        if (model == null) {
            return false;
        }
        String lower = model.toLowerCase();
        for (String prefix : MISTRAL_REASONING_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sanitizes a message role for Mistral. Mistral supports
     * {@code system}, {@code user}, {@code assistant}, {@code tool}.
     * Maps the OpenAI-introduced {@code developer} role back to {@code system}.
     *
     * @param role Original role, may be null
     * @return Mistral-compatible role (or "user" if null)
     */
    public static String sanitizeRole(String role) {
        if (role == null) {
            return "user";
        }
        if ("developer".equalsIgnoreCase(role)) {
            return "system";
        }
        return role.toLowerCase();
    }

    /**
     * Builds the Mistral chat completions request body from agent + messages.
     * Output shape mirrors OpenAI's /v1/chat/completions (model + messages +
     * temperature + max_tokens + tools + response_format).
     *
     * <p>The caller is responsible for adding the {@code instructions} as a
     * leading {@code system} message and for converting {@link Message} content
     * parts (text/image) into Mistral-compatible content arrays.</p>
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

        java.util.Map<String, Object> body = new java.util.HashMap<>();
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

        if (isReasoningModel(agent.getModel())) {
            body.put("prompt_mode", "reasoning");
        }

        return body;
    }
}
