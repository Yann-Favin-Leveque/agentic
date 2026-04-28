package io.github.yannfavinleveque.agentic.agent.custom;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.yannfavinleveque.agentic.common.ModelPricing;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * JSON-driven specification for a custom provider. Allows users of the
 * agentic-helper library to declare a brand-new LLM provider without modifying
 * the library source code.
 *
 * <p>Used as an optional field {@code custom} inside
 * {@link io.github.yannfavinleveque.agentic.agent.config.InstanceConfig}, when
 * {@code provider == "custom"}.</p>
 *
 * <p>Example JSON:</p>
 * <pre>{@code
 * {
 *   "id": "my-mistral",
 *   "url": "https://api.mistral.ai",
 *   "key": "...",
 *   "models": "mistral-large-latest,pixtral-large-latest",
 *   "provider": "custom",
 *   "custom": {
 *     "apiFormat": "openai-chat",
 *     "auth": { "header": "Authorization", "format": "Bearer {key}" },
 *     "endpoints": {
 *       "chat_completions": "/v1/chat/completions",
 *       "embeddings": "/v1/embeddings"
 *     },
 *     "queryParams": {},
 *     "extraHeaders": {},
 *     "features": {
 *       "vision": true, "function_calling": true, "structured_output": true,
 *       "web_search": false, "code_interpreter": false,
 *       "responses_api": false, "reasoning": false,
 *       "streaming": false, "embeddings": true, "image_generation": false
 *     },
 *     "onUnsupportedFeature": "throw"
 *   }
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomProviderSpec {

    /**
     * Wire format the provider speaks. Determines how the request body is built
     * and how the response is parsed. Currently supported values:
     * <ul>
     *   <li>{@code "openai-chat"} - POST /chat/completions, OpenAI-compatible</li>
     *   <li>{@code "openai-responses"} - POST /responses, OpenAI Responses API</li>
     *   <li>{@code "anthropic-messages"} - POST /messages, Anthropic Messages API</li>
     * </ul>
     */
    @JsonProperty("apiFormat")
    private String apiFormat;

    @JsonProperty("auth")
    private AuthSpec auth;

    /**
     * Map from logical endpoint name (lowercase, snake_case) to URL path
     * (without base URL). Recognized keys: {@code chat_completions}, {@code responses},
     * {@code embeddings}, {@code images_generations}.
     */
    @JsonProperty("endpoints")
    @Builder.Default
    private Map<String, String> endpoints = new HashMap<>();

    /** Query parameters to append to every request (e.g. {@code "api-version"} for Azure-style providers). */
    @JsonProperty("queryParams")
    @Builder.Default
    private Map<String, String> queryParams = new HashMap<>();

    /** Extra headers added to every request, in addition to the auth header. */
    @JsonProperty("extraHeaders")
    @Builder.Default
    private Map<String, String> extraHeaders = new HashMap<>();

    /**
     * Feature flags. Keys are {@link Feature} names (case- and underscore-insensitive),
     * values are booleans. Unknown keys are ignored on read.
     */
    @JsonProperty("features")
    @Builder.Default
    private Map<String, Boolean> features = new HashMap<>();

    /** What to do when the agent requests an unsupported feature. */
    @JsonProperty("onUnsupportedFeature")
    private String onUnsupportedFeature;

    /**
     * Optional per-model pricing for the provider's models. Each entry maps a
     * model name (or prefix) to {@link ModelPricing.PriceEntry input/output prices per 1M tokens}.
     *
     * <p>When set, lookup order at runtime:</p>
     * <ol>
     *   <li>Static {@link ModelPricing} table (covers OpenAI/Anthropic/Mistral/Grok/DeepSeek/Gemini).</li>
     *   <li>This map if no static match.</li>
     *   <li>If still no match, {@code estimatedCostUsd} on TokenUsage stays {@code null} -
     *       no error, the request still succeeds.</li>
     * </ol>
     *
     * <p>JSON example:</p>
     * <pre>{@code
     * "modelPricing": {
     *   "my-private-llm-v2":      { "input": 1.50, "output": 5.00 },
     *   "my-private-llm-v2-mini": { "input": 0.20, "output": 0.80 }
     * }
     * }</pre>
     */
    @JsonProperty("modelPricing")
    @Builder.Default
    private Map<String, ModelPricing.PriceEntry> modelPricing = new HashMap<>();

    // ------------------------------------------------------------------
    // Convenience accessors
    // ------------------------------------------------------------------

    /**
     * Returns true if the provider declares the given feature as supported.
     * Unknown / unset features default to {@code false}.
     */
    public boolean supports(Feature feature) {
        if (features == null || feature == null) {
            return false;
        }
        // Accept "vision", "VISION", "function_calling", "functionCalling" indifferently.
        String enumKey = feature.name().toLowerCase(Locale.ROOT);
        Boolean v = features.get(enumKey);
        if (v != null) return v;
        // Also try without underscores (camelCase keys)
        String camel = toCamelCase(enumKey);
        Boolean v2 = features.get(camel);
        return v2 != null && v2;
    }

    /** Returns the lenient mode (default {@link LenientMode#THROW}). */
    public LenientMode getLenientMode() {
        return LenientMode.fromString(onUnsupportedFeature);
    }

    /**
     * Returns the path declared for the given endpoint name, or null if not declared.
     * @param endpointKey logical endpoint name, e.g. "chat_completions"
     */
    public String getEndpointPath(String endpointKey) {
        if (endpoints == null || endpointKey == null) {
            return null;
        }
        return endpoints.get(endpointKey.toLowerCase(Locale.ROOT));
    }

    /** Read-only view of declared endpoints (never null). */
    public Map<String, String> getEndpointsView() {
        return endpoints == null ? Collections.emptyMap() : Collections.unmodifiableMap(endpoints);
    }

    /** Read-only view of query params (never null). */
    public Map<String, String> getQueryParamsView() {
        return queryParams == null ? Collections.emptyMap() : Collections.unmodifiableMap(queryParams);
    }

    /** Read-only view of extra headers (never null). */
    public Map<String, String> getExtraHeadersView() {
        return extraHeaders == null ? Collections.emptyMap() : Collections.unmodifiableMap(extraHeaders);
    }

    /** Read-only view of declared model pricing (never null). */
    public Map<String, ModelPricing.PriceEntry> getModelPricingView() {
        return modelPricing == null ? Collections.emptyMap() : Collections.unmodifiableMap(modelPricing);
    }

    /**
     * Validates that mandatory fields are present. Throws
     * {@link IllegalArgumentException} on first missing field.
     *
     * @param instanceId instance id used for error messages
     */
    public void validate(String instanceId) {
        if (apiFormat == null || apiFormat.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Custom provider 'apiFormat' is required for instance: " + instanceId);
        }
        String fmt = apiFormat.trim().toLowerCase(Locale.ROOT);
        if (!fmt.equals("openai-chat") && !fmt.equals("openai-responses") && !fmt.equals("anthropic-messages")) {
            throw new IllegalArgumentException(
                    "Custom provider 'apiFormat' must be one of [openai-chat, openai-responses, anthropic-messages]"
                            + " for instance: " + instanceId + " (got: " + apiFormat + ")");
        }
        if (auth == null || auth.getHeader() == null || auth.getHeader().trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Custom provider 'auth.header' is required for instance: " + instanceId);
        }
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException(
                    "Custom provider 'endpoints' map must declare at least one endpoint for instance: " + instanceId);
        }
    }

    private static String toCamelCase(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (int i = 0; i < snake.length(); i++) {
            char c = snake.charAt(i);
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
