package io.github.yannfavinleveque.agentic.agent.core;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Represents an OpenAI/Azure instance with its configuration and capabilities.
 * Each instance tracks which models are deployed and available.
 *
 * Note: This class no longer holds a SimpleOpenAI client. All HTTP calls
 * are made directly via HttpHelper using the instance's configuration.
 */
@Getter
@Builder
public class Instance {

    /**
     * Unique identifier for this instance (e.g., "openai-0", "azure-chat-0", "azure-dalle-0")
     */
    private final String id;

    /**
     * Base URL for this instance (clean URL without provider-specific paths).
     * Examples:
     * - OpenAI: "https://api.openai.com"
     * - Azure OpenAI: "https://myresource.openai.azure.com"
     * - Azure Anthropic: "https://myresource.services.ai.azure.com"
     */
    private final String baseUrl;

    /**
     * API key for authentication
     */
    private final String apiKey;

    /**
     * Provider type (OPENAI, AZURE_OPENAI, AZURE_ANTHROPIC)
     */
    private final Provider provider;

    /**
     * API version (required for Azure providers)
     * Example: "2024-08-01-preview" for Azure OpenAI, "2023-06-01" for Azure Anthropic
     */
    private final String azureApiVersion;

    /**
     * List of model names deployed on this instance
     * Examples: ["gpt-4o", "gpt-4o-mini"], ["dall-e-3"], ["claude-sonnet-4-5"]
     */
    private final List<String> deployedModels;

    /**
     * Check if this instance has a specific model deployed
     *
     * @param model Model name to check
     * @return true if this instance has the model
     */
    public boolean hasModel(String model) {
        return deployedModels != null && deployedModels.contains(model);
    }

    @Override
    public String toString() {
        return String.format("Instance{id='%s', provider=%s, models=%s}",
                id, provider, deployedModels);
    }
}
