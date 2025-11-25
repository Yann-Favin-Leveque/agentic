package io.github.sashirestela.openai.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.List;

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
     * Provider type: "openai" or "azure".
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
        if (!isOpenAI() && !isAzure()) {
            throw new IllegalArgumentException(
                    "Instance 'provider' must be 'openai' or 'azure' for instance: " + id + " (got: " + provider + ")");
        }
        if (isAzure() && (apiVersion == null || apiVersion.trim().isEmpty())) {
            throw new IllegalArgumentException("Instance 'apiVersion' is required for Azure instances: " + id);
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
