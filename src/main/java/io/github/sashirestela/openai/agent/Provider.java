package io.github.sashirestela.openai.agent;

/**
 * Enum representing the API provider type for an instance.
 */
public enum Provider {
    /**
     * Standard OpenAI API (api.openai.com)
     * Typically has all models: gpt-4o, gpt-4o-mini, gpt-3.5-turbo, dall-e-3, etc.
     */
    OPENAI,

    /**
     * Azure OpenAI Service (*.openai.azure.com)
     * OpenAI models deployed on Azure: GPT-4, GPT-3.5, DALL-E, embeddings
     */
    AZURE_OPENAI,

    /**
     * Azure Anthropic Service (*.services.ai.azure.com)
     * Claude models deployed on Azure: Claude Sonnet, Claude Haiku
     */
    AZURE_ANTHROPIC,

    /**
     * Legacy: Alias for AZURE_OPENAI (backward compatibility)
     * Use AZURE_OPENAI or AZURE_ANTHROPIC instead
     */
    @Deprecated
    AZURE
}
