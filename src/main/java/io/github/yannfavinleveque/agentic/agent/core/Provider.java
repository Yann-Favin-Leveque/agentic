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
     * xAI Grok (api.x.ai). OpenAI-compatible /v1/chat/completions endpoint.
     * Models: grok-4, grok-3-mini, grok-2-vision-1212, grok-code-fast-1, ...
     */
    GROK,

    /**
     * Grok via Azure AI Foundry (*.services.ai.azure.com).
     * OpenAI-compatible Chat Completions on /models/chat/completions
     * with api-version query parameter and api-key header.
     */
    AZURE_GROK,

    /**
     * DeepSeek (api.deepseek.com). OpenAI-compatible /v1/chat/completions endpoint.
     * Models: deepseek-chat, deepseek-reasoner.
     * Note: deepseek-reasoner returns a 'reasoning_content' field separate from 'content'.
     */
    DEEPSEEK,

    /**
     * Google Gemini via the OpenAI-compat shim
     * (generativelanguage.googleapis.com/v1beta/openai/chat/completions).
     * Models: gemini-2.5-pro, gemini-2.5-flash, gemini-2.0-flash, ...
     * Vertex AI (OAuth2) is not supported — use Provider.CUSTOM if needed.
     */
    GEMINI,

    /**
     * AWS Bedrock runtime for Anthropic Claude models
     * (bedrock-runtime.{region}.amazonaws.com).
     * <p>
     * Uses the Anthropic-native InvokeModel wire format: {@code POST
     * {url}/model/{modelId}/invoke}, where {@code modelId} is the Bedrock model id (e.g.
     * {@code anthropic.claude-opus-4-8} or an EU inference-profile id
     * {@code eu.anthropic.claude-opus-4-8}). The request body is the standard Anthropic
     * Messages payload EXCEPT that {@code model} is omitted (it is in the URL) and
     * {@code anthropic_version} (e.g. {@code bedrock-2023-05-31}) is carried IN the body.
     * The response is the standard Anthropic Messages JSON.
     * <p>
     * Authentication: a long-lived Bedrock API key sent as
     * {@code Authorization: Bearer <key>} (no SigV4). For SigV4 / IAM-role auth, use
     * {@link #CUSTOM} or a future dedicated signing path (see ProviderConfig TODO).
     */
    BEDROCK,

    /**
     * Legacy: Alias for AZURE_OPENAI (backward compatibility)
     * Use AZURE_OPENAI or AZURE_ANTHROPIC instead
     */
    @Deprecated
    AZURE
}
