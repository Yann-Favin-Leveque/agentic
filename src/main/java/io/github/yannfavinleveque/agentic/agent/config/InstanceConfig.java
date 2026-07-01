package io.github.yannfavinleveque.agentic.agent.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Configuration for a single OpenAI or Azure OpenAI instance.
 * Used for JSON-based instance configuration in AgentServiceConfig.
 *
 * <p>Example JSON configuration:</p>
 * <pre>{@code
 * [
 *   {
 *     "id": "openai-main",
 *     "url": "https://api.openai.com/v1",
 *     "key": "sk-xxx",
 *     "models": "gpt-4o,gpt-4o-mini,text-embedding-3-small",
 *     "provider": "openai",
 *     "apiVersion": null
 *   },
 *   {
 *     "id": "azure-eastus",
 *     "url": "https://my-resource.cognitiveservices.azure.com",
 *     "key": "azure-key-xxx",
 *     "models": "gpt-4o,dall-e-3,text-embedding-3-small",
 *     "provider": "azure",
 *     "apiVersion": "2024-08-01-preview"
 *   }
 * ]
 * }</pre>
 *
 * @see AgentServiceConfig
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstanceConfig {

    /**
     * Unique identifier for this instance (used in logs and thread encoding).
     * Example: "openai-main", "azure-eastus"
     */
    @JsonProperty("id")
    private String id;

    /**
     * Base URL for the OpenAI API endpoint.
     * <ul>
     *   <li>OpenAI: "https://api.openai.com/v1"</li>
     *   <li>Azure: "https://{resource}.openai.azure.com" or "https://{resource}.cognitiveservices.azure.com"</li>
     * </ul>
     */
    @JsonProperty("url")
    private String url;

    /**
     * API key for authentication.
     * <ul>
     *   <li>OpenAI: Starts with "sk-"</li>
     *   <li>Azure: Azure OpenAI API key</li>
     * </ul>
     */
    @JsonProperty("key")
    private String key;

    /**
     * Comma-separated list of models deployed on this instance.
     * Examples: "gpt-4o,gpt-4o-mini", "dall-e-3,text-embedding-3-small"
     */
    @JsonProperty("models")
    private String models;

    /**
     * Provider type: "openai", "azure-openai", "azure-anthropic", or "anthropic".
     */
    @JsonProperty("provider")
    private String provider;

    /**
     * API version (required for Azure, optional for OpenAI).
     * Examples: "2024-08-01-preview", "2024-04-01-preview"
     */
    @JsonProperty("apiVersion")
    private String apiVersion;

    /**
     * Whether this instance is enabled and should be loaded.
     * Default: true (for backward compatibility)
     * Set to false to temporarily disable an instance without removing it from configuration.
     */
    @JsonProperty("enabled")
    @Builder.Default
    private boolean enabled = true;

    /**
     * Per-model rate limits (requests per second) for this instance.
     * Keys are model names (e.g., "gpt-5.4-mini") or "*" for default.
     * If not set, falls back to the global requestsPerSecond from AgentServiceConfig.
     *
     * <p>Example:</p>
     * <pre>{@code
     * "rateLimits": {
     *   "gpt-5.4": 40,
     *   "gpt-5.4-mini": 40,
     *   "gpt-4o": 3,
     *   "*": 5
     * }
     * }</pre>
     */
    @JsonProperty("rateLimits")
    private Map<String, Integer> rateLimits;

    /**
     * Custom provider spec. Required when {@code provider == "custom"}, ignored otherwise.
     */
    @JsonProperty("custom")
    private io.github.yannfavinleveque.agentic.agent.custom.CustomProviderSpec custom;

    /**
     * Parse the comma-separated models string into a List.
     * @return List of model names
     */
    public List<String> getModelsList() {
        if (models == null || models.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(models.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Check if this is an Azure OpenAI instance.
     * @return true if provider is "azure", "azure-openai"
     */
    public boolean isAzureOpenAI() {
        return "azure".equalsIgnoreCase(provider) || "azure-openai".equalsIgnoreCase(provider);
    }

    /**
     * Check if this is an Azure Anthropic instance.
     * @return true if provider is "azure-anthropic"
     */
    public boolean isAzureAnthropic() {
        return "azure-anthropic".equalsIgnoreCase(provider);
    }

    /**
     * Check if this is any Azure instance (OpenAI or Anthropic).
     * @return true if provider contains "azure"
     */
    public boolean isAzure() {
        return isAzureOpenAI() || isAzureAnthropic();
    }

    /**
     * Check if this is a standard OpenAI instance.
     * @return true if provider is "openai"
     */
    public boolean isOpenAI() {
        return "openai".equalsIgnoreCase(provider);
    }

    /**
     * Check if this is a direct Anthropic instance (api.anthropic.com).
     * @return true if provider is "anthropic"
     */
    public boolean isAnthropic() {
        return "anthropic".equalsIgnoreCase(provider);
    }

    /**
     * Check if this is a Mistral La Plateforme instance.
     * @return true if provider is "mistral"
     */
    public boolean isMistral() {
        return "mistral".equalsIgnoreCase(provider);
    }

    /**
     * Check if this is a Mistral via Azure AI Foundry instance.
     * @return true if provider is "azure-mistral"
     */
    public boolean isAzureMistral() {
        return "azure-mistral".equalsIgnoreCase(provider);
    }

    /**
     * Check if this is an xAI Grok instance.
     * @return true if provider is "grok"
     */
    public boolean isGrok() {
        return "grok".equalsIgnoreCase(provider);
    }

    /**
     * Check if this is a Grok via Azure AI Foundry instance.
     * @return true if provider is "azure-grok"
     */
    public boolean isAzureGrok() {
        return "azure-grok".equalsIgnoreCase(provider);
    }

    /**
     * Check if this is a DeepSeek instance.
     * @return true if provider is "deepseek"
     */
    public boolean isDeepSeek() {
        return "deepseek".equalsIgnoreCase(provider);
    }

    /**
     * Check if this is a Google Gemini instance (via the OpenAI-compat shim).
     * @return true if provider is "gemini"
     */
    public boolean isGemini() {
        return "gemini".equalsIgnoreCase(provider);
    }

    /**
     * Check if this is an AWS Bedrock instance (Anthropic Claude via InvokeModel).
     * @return true if provider is "bedrock"
     */
    public boolean isBedrock() {
        return "bedrock".equalsIgnoreCase(provider);
    }

    /**
     * Check if this is a fully user-defined custom provider instance.
     * @return true if provider is "custom"
     */
    public boolean isCustom() {
        return "custom".equalsIgnoreCase(provider);
    }

    /**
     * Check if this instance supports a given model.
     * @param model Model name to check
     * @return true if the model is in the models list
     */
    public boolean supportsModel(String model) {
        return getModelsList().stream()
                .anyMatch(m -> m.equalsIgnoreCase(model));
    }

    /**
     * Validate that all required fields are present.
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Instance 'id' is required");
        }
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("Instance 'url' is required for instance: " + id);
        }
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Instance 'key' is required for instance: " + id);
        }
        if (models == null || models.trim().isEmpty()) {
            throw new IllegalArgumentException("Instance 'models' is required for instance: " + id);
        }
        if (provider == null || provider.trim().isEmpty()) {
            throw new IllegalArgumentException("Instance 'provider' is required for instance: " + id);
        }
        if (!isOpenAI() && !isAzure() && !isAnthropic() && !isMistral() && !isAzureMistral()
                && !isGrok() && !isAzureGrok() && !isDeepSeek() && !isGemini() && !isBedrock() && !isCustom()) {
            throw new IllegalArgumentException(
                    "Instance 'provider' must be 'openai', 'azure-openai', 'azure-anthropic', 'anthropic', 'mistral', 'azure-mistral', 'grok', 'azure-grok', 'deepseek', 'gemini', 'bedrock', or 'custom' for instance: " + id + " (got: " + provider + ")");
        }
        if (isAzure() && (apiVersion == null || apiVersion.trim().isEmpty())) {
            throw new IllegalArgumentException("Instance 'apiVersion' is required for Azure instances: " + id);
        }
        if (isAzureMistral() && (apiVersion == null || apiVersion.trim().isEmpty())) {
            throw new IllegalArgumentException("Instance 'apiVersion' is required for Azure Mistral instances: " + id);
        }
        if (isAzureGrok() && (apiVersion == null || apiVersion.trim().isEmpty())) {
            throw new IllegalArgumentException("Instance 'apiVersion' is required for Azure Grok instances: " + id);
        }
        if (isCustom()) {
            if (custom == null) {
                throw new IllegalArgumentException(
                        "Instance 'custom' block is required when provider is 'custom' for instance: " + id);
            }
            custom.validate(id);
        }

        // Normalize URL (remove trailing slash)
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
    }

    @Override
    public String toString() {
        return String.format("Instance[id=%s, provider=%s, url=%s, models=%s]",
                id, provider, url, models);
    }
}
