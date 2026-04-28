package io.github.yannfavinleveque.agentic.agent.core;

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
     * Direct Anthropic API (api.anthropic.com)
     * Claude models via Anthropic's own API: Claude Opus, Claude Sonnet, Claude Haiku
     */
    ANTHROPIC,

    /**
     * Mistral La Plateforme (api.mistral.ai).
     * OpenAI-compatible Chat Completions API. No Responses API.
     * Models: mistral-large-latest, pixtral-large-latest, codestral-latest, magistral-medium-latest, ...
     */
    MISTRAL,

    /**
     * Mistral via Azure AI Foundry (*.services.ai.azure.com).
     * OpenAI-compatible Chat Completions, served under /models/chat/completions
     * with an api-version query parameter and api-key header.
     */
    AZURE_MISTRAL,

    /**
     * Fully user-defined provider, configured via {@link io.github.yannfavinleveque.agentic.agent.custom.CustomProviderSpec}
     * inside the instance JSON. Endpoints, auth, headers, supported features are
     * all data-driven. Wire format must be one of: openai-chat, openai-responses,
     * anthropic-messages.
     */
    CUSTOM,

    /**
     * Legacy: Alias for AZURE_OPENAI (backward compatibility)
     * Use AZURE_OPENAI or AZURE_ANTHROPIC instead
     */
    @Deprecated
    AZURE
}
