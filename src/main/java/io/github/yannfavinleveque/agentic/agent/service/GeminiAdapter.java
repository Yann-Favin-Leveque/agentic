package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.core.Agent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapter for Google Gemini API via the OpenAI-compatible shim
 * ({@code https://generativelanguage.googleapis.com/v1beta/openai/chat/completions}).
 *
 * <p>Why the shim and not the native Gemini API? The native API uses a proprietary
 * shape ({@code contents}/{@code parts}, no system role, OAuth for Vertex), which
 * would require its own message-format converter. The shim accepts plain OpenAI
 * Chat Completions payloads with {@code Authorization: Bearer <API_KEY>} and is
 * documented as production-ready by Google. This keeps the implementation aligned
 * with Mistral/Grok/DeepSeek paths.</p>
 *
 * <p>Limitations of the shim (acknowledged trade-offs):</p>
 * <ul>
 *   <li>No access to {@code thinkingConfig.thinkingBudget} (Gemini 2.5 thinking budget).</li>
 *   <li>No access to native multimodal types beyond what OpenAI vision allows
 *       (no inline audio/video, only image_url base64/URL).</li>
 *   <li>{@code safetySettings} cannot be configured via the shim — defaults apply.</li>
 *   <li>Some Gemini-only features (grounded search via {@code google_search}) require
 *       the native API and are not exposed here.</li>
 * </ul>
 *
 * <p>For users who need any of the above, a future {@code Provider.GEMINI_NATIVE}
 * could speak the proprietary format. For now, the shim covers the 80% case
 * (chat + vision + tool calling + structured output + thinking models).</p>
 *
 * <p>This class holds only static helpers. All HTTP calls are performed by
 * {@link UnifiedRequestService}.</p>
 */
public final class GeminiAdapter {

    private GeminiAdapter() {}

    /**
     * Default base URL for the Gemini API OpenAI-compat shim. Users can override
     * via {@code InstanceConfig.url} if Google routes them to a regional endpoint.
     */
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    /** Path of the OpenAI-compat chat completions endpoint on the shim. */
    public static final String CHAT_COMPLETIONS_PATH = "/v1beta/openai/chat/completions";

    /** Path of the OpenAI-compat embeddings endpoint. */
    public static final String EMBEDDINGS_PATH = "/v1beta/openai/embeddings";

    private static final Set<String> GEMINI_MODEL_PREFIXES = new HashSet<>(Arrays.asList(
            "gemini-",
            "text-embedding-004"));

    /**
     * Reasoning / thinking models (Gemini 2.5 line and 2.0-flash-thinking).
     * They accept {@code reasoning_effort} via the shim, mapped internally to
     * {@code thinkingConfig.thinkingBudget}.
     */
    private static final Set<String> GEMINI_REASONING_PREFIXES = new HashSet<>(Arrays.asList(
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-2.0-flash-thinking"));

    public static boolean isGeminiModel(String model) {
        if (model == null) return false;
        String lower = model.toLowerCase();
        for (String prefix : GEMINI_MODEL_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    public static boolean isReasoningModel(String model) {
        if (model == null) return false;
        String lower = model.toLowerCase();
        for (String prefix : GEMINI_REASONING_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Builds the Gemini-via-shim chat completions request body. Mirrors
     * {@link MistralAdapter#buildRequestBody} with the addition of
     * {@code reasoning_effort} for thinking models.
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

        // reasoning_effort only on thinking-capable Gemini models
        if (isReasoningModel(agent.getModel())
                && agent.getReasoningEffort() != null
                && !agent.getReasoningEffort().isEmpty()) {
            body.put("reasoning_effort", agent.getReasoningEffort());
        }

        return body;
    }
}
