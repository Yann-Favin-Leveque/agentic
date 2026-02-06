package io.github.yannfavinleveque.agentic.agent.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralized configuration for all API providers. Contains endpoint paths, authentication headers,
 * and query parameters for each provider.
 * <p>
 * To add a new provider:
 * </p>
 * <ol>
 * <li>Add the provider to {@link Provider} enum</li>
 * <li>Add cases in {@link #getPath}, {@link #getHeaders}, {@link #getQueryParams}</li>
 * <li>Update {@link #supportsEndpoint} if the provider doesn't support all endpoints</li>
 * </ol>
 * <p>
 * To add a new endpoint:
 * </p>
 * <ol>
 * <li>Add the endpoint to {@link Endpoint} enum</li>
 * <li>Add the path in each provider's switch case</li>
 * </ol>
 */
public final class ProviderConfig {

    private ProviderConfig() {
        // Utility class - no instantiation
    }

    // ==================== MODEL FAMILY ====================

    /**
     * Model families determine API format (request/response structure). This is independent of the
     * provider - e.g., Azure can host both OpenAI and Anthropic models.
     */
    public enum ModelFamily {
        OPENAI,     // gpt-*, text-embedding-*, dall-e-*, o1-*, etc.
        ANTHROPIC   // claude-*
    }

    /**
     * Known Anthropic model prefixes. Models starting with these prefixes use Anthropic API format.
     */
    private static final Set<String> ANTHROPIC_MODEL_PREFIXES = new HashSet<>(Arrays.asList(
            "claude-"));

    /**
     * Determines the model family based on the model name. Used to select the correct API format
     * (response_format vs output_format, etc.)
     *
     * @param model Model name (e.g., "gpt-4o", "claude-sonnet-4-5")
     * @return ModelFamily for the model
     */
    public static ModelFamily getModelFamily(String model) {
        if (model == null) {
            return ModelFamily.OPENAI;
        }
        String lowerModel = model.toLowerCase();
        for (String prefix : ANTHROPIC_MODEL_PREFIXES) {
            if (lowerModel.startsWith(prefix)) {
                return ModelFamily.ANTHROPIC;
            }
        }
        return ModelFamily.OPENAI; // Default to OpenAI format
    }

    /**
     * Checks if a model uses Anthropic API format.
     *
     * @param model Model name
     * @return true if the model uses Anthropic format
     */
    public static boolean isAnthropicModel(String model) {
        return getModelFamily(model) == ModelFamily.ANTHROPIC;
    }

    // ==================== ENDPOINT ENUM ====================

    /**
     * All supported API endpoints. Each endpoint maps to a specific API path depending on the provider.
     */
    public enum Endpoint {
        // Chat & Completions
        CHAT_COMPLETIONS,
        COMPLETIONS,  // Legacy
        RESPONSES,    // New stateless API (POST /v1/responses)

        // Embeddings
        EMBEDDINGS,

        // Images
        IMAGES_GENERATIONS,
        IMAGES_EDITS,
        IMAGES_VARIATIONS,

        // Audio
        AUDIO_SPEECH,
        AUDIO_TRANSCRIPTIONS,
        AUDIO_TRANSLATIONS,

        // Assistants API (Beta)
        ASSISTANTS,
        ASSISTANT,            // /assistants/{assistantId} - single assistant
        THREADS,
        THREAD,               // /threads/{threadId} - single thread
        THREAD_MESSAGES,      // /threads/{threadId}/messages
        THREAD_MESSAGE,       // /threads/{threadId}/messages/{messageId} - single message
        THREAD_RUNS,          // /threads/{threadId}/runs
        THREAD_RUN,           // /threads/{threadId}/runs/{runId} - single run
        THREAD_RUN_STEPS,     // /threads/{threadId}/runs/{runId}/steps

        // Vector Stores
        VECTOR_STORES,
        VECTOR_STORE_FILES,   // /vector_stores/{vectorStoreId}/files

        // Files & Models
        FILES,
        FILE,                 // /files/{fileId} - single file
        MODELS,

        // Vector Store single
        VECTOR_STORE,         // /vector_stores/{vectorStoreId} - single vector store

        // Other
        BATCHES,
        BATCH,                // /batches/{batchId} - single batch
        FINE_TUNING,
        MODERATIONS,
        UPLOADS
    }

    // Endpoints that require model in path for Azure OpenAI
    private static final Set<Endpoint> AZURE_MODEL_REQUIRED_ENDPOINTS = new HashSet<>(Arrays.asList(
            Endpoint.CHAT_COMPLETIONS,
            Endpoint.COMPLETIONS,
            Endpoint.EMBEDDINGS,
            Endpoint.IMAGES_GENERATIONS,
            Endpoint.AUDIO_SPEECH,
            Endpoint.AUDIO_TRANSCRIPTIONS,
            Endpoint.AUDIO_TRANSLATIONS));

    // Endpoints NOT supported on Azure OpenAI
    private static final Set<Endpoint> AZURE_UNSUPPORTED_ENDPOINTS = new HashSet<>(Arrays.asList(
            Endpoint.IMAGES_EDITS,
            Endpoint.IMAGES_VARIATIONS));

    // ==================== ENDPOINT PATHS ====================

    /**
     * Gets the API path for a given provider and endpoint.
     *
     * @param provider The API provider
     * @param endpoint The endpoint to call
     * @param model    Model name (required for Azure OpenAI deployments, can be null otherwise)
     * @return The API path (without base URL)
     * @throws UnsupportedOperationException if the provider doesn't support this endpoint
     */
    public static String getPath(Provider provider, Endpoint endpoint, String model) {
        switch (provider) {
            case OPENAI:
                return getOpenAIPath(endpoint);
            case AZURE_OPENAI:
            case AZURE:
                return getAzureOpenAIPath(endpoint, model);
            case AZURE_ANTHROPIC:
                return getAzureAnthropicPath(endpoint);
            case ANTHROPIC:
                return getAnthropicPath(endpoint);
            default:
                throw new IllegalArgumentException("Unknown provider: " + provider);
        }
    }

    /**
     * Gets the API path for endpoints that don't require a model parameter.
     */
    public static String getPath(Provider provider, Endpoint endpoint) {
        return getPath(provider, endpoint, null);
    }

    private static String getOpenAIPath(Endpoint endpoint) {
        switch (endpoint) {
            // Chat & Completions
            case CHAT_COMPLETIONS:
                return "/v1/chat/completions";
            case COMPLETIONS:
                return "/v1/completions";
            case RESPONSES:
                return "/v1/responses";

            // Embeddings
            case EMBEDDINGS:
                return "/v1/embeddings";

            // Images
            case IMAGES_GENERATIONS:
                return "/v1/images/generations";
            case IMAGES_EDITS:
                return "/v1/images/edits";
            case IMAGES_VARIATIONS:
                return "/v1/images/variations";

            // Audio
            case AUDIO_SPEECH:
                return "/v1/audio/speech";
            case AUDIO_TRANSCRIPTIONS:
                return "/v1/audio/transcriptions";
            case AUDIO_TRANSLATIONS:
                return "/v1/audio/translations";

            // Assistants API
            case ASSISTANTS:
                return "/v1/assistants";
            case ASSISTANT:
                return "/v1/assistants/{assistantId}";
            case THREADS:
                return "/v1/threads";
            case THREAD:
                return "/v1/threads/{threadId}";
            case THREAD_MESSAGES:
                return "/v1/threads/{threadId}/messages";
            case THREAD_MESSAGE:
                return "/v1/threads/{threadId}/messages/{messageId}";
            case THREAD_RUNS:
                return "/v1/threads/{threadId}/runs";
            case THREAD_RUN:
                return "/v1/threads/{threadId}/runs/{runId}";
            case THREAD_RUN_STEPS:
                return "/v1/threads/{threadId}/runs/{runId}/steps";

            // Vector Stores
            case VECTOR_STORES:
                return "/v1/vector_stores";
            case VECTOR_STORE:
                return "/v1/vector_stores/{vectorStoreId}";
            case VECTOR_STORE_FILES:
                return "/v1/vector_stores/{vectorStoreId}/files";

            // Files & Models
            case FILES:
                return "/v1/files";
            case FILE:
                return "/v1/files/{fileId}";
            case MODELS:
                return "/v1/models";

            // Other
            case BATCHES:
                return "/v1/batches";
            case BATCH:
                return "/v1/batches/{batchId}";
            case FINE_TUNING:
                return "/v1/fine_tuning/jobs";
            case MODERATIONS:
                return "/v1/moderations";
            case UPLOADS:
                return "/v1/uploads";

            default:
                throw new IllegalArgumentException("Unknown endpoint: " + endpoint);
        }
    }

    private static String getAzureOpenAIPath(Endpoint endpoint, String model) {
        // Check if endpoint is supported
        if (AZURE_UNSUPPORTED_ENDPOINTS.contains(endpoint)) {
            throw new UnsupportedOperationException("Azure OpenAI doesn't support: " + endpoint);
        }

        // Azure OpenAI uses /openai prefix and /deployments/{model} for model-specific endpoints
        switch (endpoint) {
            // Model-specific endpoints (require deployment name in path)
            case CHAT_COMPLETIONS:
                return "/openai/deployments/" + requireModel(model) + "/chat/completions";
            case COMPLETIONS:
                return "/openai/deployments/" + requireModel(model) + "/completions";
            case RESPONSES:
                // Responses API uses /openai/v1/responses (model in body, not path)
                return "/openai/v1/responses";
            case EMBEDDINGS:
                return "/openai/deployments/" + requireModel(model) + "/embeddings";
            case IMAGES_GENERATIONS:
                return "/openai/deployments/" + requireModel(model) + "/images/generations";

            // Audio (also model-specific on Azure)
            case AUDIO_SPEECH:
                return "/openai/deployments/" + requireModel(model) + "/audio/speech";
            case AUDIO_TRANSCRIPTIONS:
                return "/openai/deployments/" + requireModel(model) + "/audio/transcriptions";
            case AUDIO_TRANSLATIONS:
                return "/openai/deployments/" + requireModel(model) + "/audio/translations";

            // Assistants API (NOT model-specific - global endpoints)
            case ASSISTANTS:
                return "/openai/assistants";
            case ASSISTANT:
                return "/openai/assistants/{assistantId}";
            case THREADS:
                return "/openai/threads";
            case THREAD:
                return "/openai/threads/{threadId}";
            case THREAD_MESSAGES:
                return "/openai/threads/{threadId}/messages";
            case THREAD_MESSAGE:
                return "/openai/threads/{threadId}/messages/{messageId}";
            case THREAD_RUNS:
                return "/openai/threads/{threadId}/runs";
            case THREAD_RUN:
                return "/openai/threads/{threadId}/runs/{runId}";
            case THREAD_RUN_STEPS:
                return "/openai/threads/{threadId}/runs/{runId}/steps";

            // Vector Stores (global)
            case VECTOR_STORES:
                return "/openai/vector_stores";
            case VECTOR_STORE:
                return "/openai/vector_stores/{vectorStoreId}";
            case VECTOR_STORE_FILES:
                return "/openai/vector_stores/{vectorStoreId}/files";

            // Files & Models (global)
            case FILES:
                return "/openai/files";
            case FILE:
                return "/openai/files/{fileId}";
            case MODELS:
                return "/openai/models";

            // Other (global)
            case BATCHES:
                return "/openai/batches";
            case BATCH:
                return "/openai/batches/{batchId}";
            case FINE_TUNING:
                return "/openai/fine_tuning/jobs";
            case MODERATIONS:
                return "/openai/moderations";
            case UPLOADS:
                return "/openai/uploads";

            default:
                throw new IllegalArgumentException("Unknown endpoint: " + endpoint);
        }
    }

    private static String getAzureAnthropicPath(Endpoint endpoint) {
        // Azure Anthropic only supports messages endpoint
        if (endpoint == Endpoint.CHAT_COMPLETIONS) {
            return "/anthropic/v1/messages";
        }
        throw new UnsupportedOperationException(
                "Azure Anthropic doesn't support: " + endpoint + ". Only CHAT_COMPLETIONS is available.");
    }

    private static String getAnthropicPath(Endpoint endpoint) {
        // Direct Anthropic API only supports messages endpoint
        if (endpoint == Endpoint.CHAT_COMPLETIONS) {
            return "/v1/messages";
        }
        throw new UnsupportedOperationException(
                "Anthropic doesn't support: " + endpoint + ". Only CHAT_COMPLETIONS is available.");
    }

    private static String requireModel(String model) {
        if (model == null || model.trim().isEmpty()) {
            throw new IllegalArgumentException("Model is required for Azure OpenAI deployment endpoints");
        }
        return model;
    }

    // ==================== AUTHENTICATION HEADERS ====================

    /**
     * Gets the authentication headers for a provider.
     *
     * @param provider   The API provider
     * @param apiKey     The API key
     * @param apiVersion API version (required for Azure Anthropic)
     * @return Map of header name to value
     */
    public static Map<String, String> getHeaders(Provider provider, String apiKey, String apiVersion) {
        Map<String, String> headers = new HashMap<>();

        switch (provider) {
            case OPENAI:
                headers.put("Authorization", "Bearer " + apiKey);
                break;
            case AZURE_OPENAI:
            case AZURE:
                headers.put("api-key", apiKey);
                break;
            case AZURE_ANTHROPIC:
                headers.put("x-api-key", apiKey);
                headers.put("anthropic-version", apiVersion != null ? apiVersion : "2023-06-01");
                break;
            case ANTHROPIC:
                headers.put("x-api-key", apiKey);
                headers.put("anthropic-version", apiVersion != null ? apiVersion : "2023-06-01");
                break;
            default:
                throw new IllegalArgumentException("Unknown provider: " + provider);
        }

        return headers;
    }

    /**
     * Gets authentication headers without API version (for OpenAI).
     */
    public static Map<String, String> getHeaders(Provider provider, String apiKey) {
        return getHeaders(provider, apiKey, null);
    }

    // ==================== QUERY PARAMETERS ====================

    /**
     * Gets required query parameters for a provider.
     *
     * @param provider   The API provider
     * @param apiVersion API version (required for Azure)
     * @return Map of query parameter name to value (empty if none required)
     */
    public static Map<String, String> getQueryParams(Provider provider, String apiVersion) {
        return getQueryParams(provider, apiVersion, null);
    }

    /**
     * Gets required query parameters for a provider and endpoint.
     *
     * @param provider   The API provider
     * @param apiVersion API version (required for Azure)
     * @param endpoint   The endpoint being called (can be null)
     * @return Map of query parameter name to value (empty if none required)
     */
    public static Map<String, String> getQueryParams(Provider provider, String apiVersion, Endpoint endpoint) {
        Map<String, String> params = new HashMap<>();

        switch (provider) {
            case AZURE_OPENAI:
            case AZURE:
                // Responses API on Azure requires 'preview' api-version
                if (endpoint == Endpoint.RESPONSES) {
                    params.put("api-version", "preview");
                } else {
                    if (apiVersion == null || apiVersion.trim().isEmpty()) {
                        throw new IllegalArgumentException("API version is required for Azure OpenAI");
                    }
                    params.put("api-version", apiVersion);
                }
                break;
            case AZURE_ANTHROPIC:
                if (apiVersion != null && !apiVersion.trim().isEmpty()) {
                    params.put("api-version", apiVersion);
                }
                break;
            case OPENAI:
            case ANTHROPIC:
                // No query params needed
                break;
            default:
                throw new IllegalArgumentException("Unknown provider: " + provider);
        }

        return params;
    }

    // ==================== PROVIDER CAPABILITIES ====================

    /**
     * Checks if a provider supports a specific endpoint.
     *
     * @param provider The API provider
     * @param endpoint The endpoint to check
     * @return true if the provider supports this endpoint
     */
    public static boolean supportsEndpoint(Provider provider, Endpoint endpoint) {
        switch (provider) {
            case OPENAI:
                return true;  // OpenAI supports everything
            case AZURE_OPENAI:
            case AZURE:
                return !AZURE_UNSUPPORTED_ENDPOINTS.contains(endpoint);
            case AZURE_ANTHROPIC:
            case ANTHROPIC:
                return endpoint == Endpoint.CHAT_COMPLETIONS;
            default:
                return false;
        }
    }

    /**
     * Checks if an endpoint requires a model parameter for URL building.
     *
     * @param provider The API provider
     * @param endpoint The endpoint
     * @return true if model is required in the URL path
     */
    public static boolean requiresModelInPath(Provider provider, Endpoint endpoint) {
        if (provider != Provider.AZURE_OPENAI && provider != Provider.AZURE) {
            return false;
        }
        return AZURE_MODEL_REQUIRED_ENDPOINTS.contains(endpoint);
    }

    // ==================== URL BUILDING HELPERS ====================

    /**
     * Builds a complete URL for an API call.
     *
     * @param baseUrl    Base URL of the instance (e.g., "https://api.openai.com" or
     *                   "https://myresource.openai.azure.com")
     * @param provider   The API provider
     * @param endpoint   The endpoint to call
     * @param model      Model name (can be null for non-model-specific endpoints)
     * @param apiVersion API version (required for Azure)
     * @return Complete URL with query parameters
     */
    public static String buildUrl(String baseUrl, Provider provider, Endpoint endpoint,
            String model, String apiVersion) {
        // Normalize base URL (remove trailing slash)
        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;

        // Get path
        String path = getPath(provider, endpoint, model);

        // Build URL with query params
        StringBuilder url = new StringBuilder(normalizedBase).append(path);

        Map<String, String> queryParams = getQueryParams(provider, apiVersion);
        if (!queryParams.isEmpty()) {
            url.append("?");
            boolean first = true;
            for (Map.Entry<String, String> param : queryParams.entrySet()) {
                if (!first) {
                    url.append("&");
                }
                url.append(param.getKey()).append("=").append(param.getValue());
                first = false;
            }
        }

        return url.toString();
    }

    /**
     * Replaces path parameters in a URL template. Example: "/threads/{threadId}/messages" with
     * threadId="abc" -> "/threads/abc/messages"
     *
     * @param pathTemplate Path template with {param} placeholders
     * @param params       Map of parameter names to values
     * @return Path with parameters replaced
     */
    public static String replacePathParams(String pathTemplate, Map<String, String> params) {
        String result = pathTemplate;
        for (Map.Entry<String, String> param : params.entrySet()) {
            result = result.replace("{" + param.getKey() + "}", param.getValue());
        }
        return result;
    }

}
