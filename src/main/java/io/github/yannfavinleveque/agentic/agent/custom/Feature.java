package io.github.yannfavinleveque.agentic.agent.custom;

/**
 * Capabilities a custom provider may declare. Used to validate, before sending
 * a request, that the agent does not require something the provider cannot do.
 *
 * <p>Each feature corresponds to a flag the user can set in the
 * {@code custom.features} JSON map of an {@code InstanceConfig}. Unknown
 * feature names in JSON are silently ignored (forward-compat).</p>
 */
public enum Feature {
    /** Multimodal input (image content parts in user messages). */
    VISION,
    /** Tool / function calling (custom user-defined functions). */
    FUNCTION_CALLING,
    /** Structured output via response_format / json_schema. */
    STRUCTURED_OUTPUT,
    /** Native web search tool (e.g. OpenAI web_search). */
    WEB_SEARCH,
    /** Native code interpreter / sandbox tool. */
    CODE_INTERPRETER,
    /** OpenAI Responses API (POST /v1/responses). When false, fall back to chat/completions. */
    RESPONSES_API,
    /** Reasoning / chain-of-thought parameter (reasoning_effort, prompt_mode, ...). */
    REASONING,
    /** Streaming responses (Server-Sent Events). */
    STREAMING,
    /** Embeddings endpoint. */
    EMBEDDINGS,
    /** Image generation endpoint (DALL-E style). */
    IMAGE_GENERATION
}
