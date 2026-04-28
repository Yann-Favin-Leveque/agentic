# Provider Configuration Examples

This directory contains ready-to-use JSON examples for configuring `OPENAI_INSTANCES` (the multi-provider instance pool used by `agentic-helper`). Each file is a JSON array describing one or more provider instances. You can mix as many providers as you want inside a single array — the instance router will dispatch each agent's call to the right provider based on the `models` field of each instance.

## How to use these examples

`agentic-helper` reads its provider pool from the `OPENAI_INSTANCES` environment variable (or from a file path if `OPENAI_INSTANCES_FILE` is set). The value is a JSON array of instance descriptors.

To use one of these examples:

1. Pick the file matching the provider you want (e.g. `custom-grok.json`).
2. Copy its contents into your `OPENAI_INSTANCES` environment variable (or into a file pointed at by `OPENAI_INSTANCES_FILE`).
3. Replace every `${VAR}` placeholder with your real API key (or set the matching env var if your launcher does the substitution).
4. To run multiple providers in parallel, merge several JSON arrays into one — just drop all the instance objects into the same top-level array.

Example (bash):

```bash
export MISTRAL_API_KEY="sk-..."
export OPENAI_INSTANCES="$(cat examples/providers/mistral-instance.json | envsubst)"
```

## Available examples

| File | Provider | API key env var | apiFormat | Notes |
|---|---|---|---|---|
| `mistral-instance.json` | Mistral La Plateforme | `MISTRAL_API_KEY` | natif | Vision via Pixtral, reasoning via Magistral |
| `azure-mistral-instance.json` | Mistral via Azure AI Foundry | `AZURE_MISTRAL_KEY` | natif | `apiVersion` requise |
| `grok-native.json` | xAI Grok | `XAI_API_KEY` | natif | `Provider.GROK` ; `reasoning_effort` only on grok-4 / grok-3-mini |
| `azure-grok-native.json` | Grok via Azure AI Foundry | `AZURE_GROK_KEY` | natif | `Provider.AZURE_GROK` ; `apiVersion` requise |
| `deepseek-native.json` | DeepSeek | `DEEPSEEK_API_KEY` | natif | `Provider.DEEPSEEK` ; `reasoning_content` prepended as `[REASONING]...[/REASONING]` |
| `gemini-native.json` | Google Gemini (OpenAI shim) | `GEMINI_API_KEY` | natif | `Provider.GEMINI` ; uses `/v1beta/openai/chat/completions` shim |
| `custom-grok.json` | xAI Grok (legacy `custom`) | `XAI_API_KEY` | `openai-chat` | Pre-1.23 fallback. Prefer `grok-native.json` |
| `custom-deepseek.json` | DeepSeek (legacy `custom`) | `DEEPSEEK_API_KEY` | `openai-chat` | Pre-1.23 fallback. Prefer `deepseek-native.json` |
| `custom-groq.json` | Groq | `GROQ_API_KEY` | `openai-chat` | Inférence très rapide (Llama 70B @ 500+ tok/s) |
| `custom-ollama-local.json` | Ollama auto-hébergé | n/a | `openai-chat` | Pas d'API key réelle, modèles locaux |

## Anatomy of a custom provider

When `provider` is set to `"custom"`, the `custom` object describes how to talk to the underlying API. Every field is meaningful:

### `apiFormat`

Tells the client which wire format to use when serialising requests and parsing responses. Currently supported:

- `openai-chat` — POST a `messages: [...]` body to `chat_completions` and parse `choices[0].message`. This works for the vast majority of OpenAI-compatible providers (xAI Grok, DeepSeek, Groq, Together, Fireworks, Ollama, vLLM, LM Studio, etc.).

More formats (e.g. `anthropic-messages`, `gemini`) may be added later. If the provider you target is OpenAI-compatible, `openai-chat` is the right choice.

### `auth`

Describes the authentication header injected into every request. Two fields:

- `header` — name of the HTTP header (usually `Authorization`, sometimes `x-api-key`).
- `format` — template applied to the API key. The literal `{key}` is replaced by the resolved key. Common values: `Bearer {key}`, `{key}`, `ApiKey {key}`.

### `endpoints`

Map of logical endpoint name → URL path appended to `url`. The keys correspond to the operations the client may call:

- `chat_completions` — chat / function-calling endpoint (required for any conversational use).
- `embeddings` — vector embeddings endpoint (only needed if `features.embeddings: true`).
- `images` — image generation endpoint (only needed if `features.image_generation: true`).

You only need to declare the endpoints you want to support. Calling an undeclared endpoint will be handled by `onUnsupportedFeature`.

### `queryParams`

Map of query string parameters appended to every request to this provider. Useful for providers that demand an `api-version=...` or `deployment=...` parameter on the URL itself.

### `extraHeaders`

Map of extra HTTP headers added to every request. Useful for telemetry headers, beta flags, organisation IDs, etc. Example: `{ "anthropic-beta": "tools-2024-05-16" }`.

### `features`

Capability flags. The agent runtime uses these to decide whether to attempt a feature (vision, tool calls, structured output, web search, reasoning, streaming, embeddings, image generation, etc.) on this instance. Setting a flag to `false` doesn't break anything by itself — it only changes how `onUnsupportedFeature` reacts when the agent tries to use that feature.

Typical flags:

- `vision` — the model accepts image inputs.
- `function_calling` — the model supports OpenAI-style tool calls.
- `structured_output` — the model honours JSON schema output.
- `web_search` — built-in web search tool.
- `code_interpreter` — built-in code execution sandbox.
- `responses_api` — the OpenAI Responses API is supported.
- `reasoning` — extended thinking / reasoning tokens are exposed.
- `streaming` — Server-Sent Events streaming.
- `embeddings` — `/embeddings` endpoint available.
- `image_generation` — `/images/generations` endpoint available.

### `onUnsupportedFeature`

Controls what happens when an agent requests a feature the provider has marked as unsupported. See the next section for usage patterns.

## What happens when a feature is missing?

Three lenient modes are supported:

- `throw` (default, recommended for **production**) — the request fails fast with a clear error. You want to know *immediately* that the provider you picked can't do what your agent needs.
- `warn` (recommended for **dev / exploration**) — the call still goes through (without the unsupported feature) but a warning is logged. Great when you're shopping providers and don't want to rewrite your agent every time.
- `ignore` (silent fallback) — the unsupported feature is silently dropped, no warning. Use this only when you knowingly run a partial config and accept that some calls will degrade.

Practical examples:

- You build a vision-heavy agent and accidentally route it to DeepSeek (`vision: false`). With `throw` you get an error at the first request — easy to fix. With `warn`, the image is dropped and the agent still produces some output, useful when exploring fallback strategies.
- You wire up Ollama for cheap local development. You don't care that it lacks `image_generation`, so `ignore` keeps your logs clean.

## Combining multiple providers

The `OPENAI_INSTANCES` array can hold instances from any number of providers. The router will pick the right one based on the `models` field of each instance and the model your agent declares.

```json
[
  {
    "id": "openai-main",
    "url": "https://api.openai.com",
    "key": "${OPENAI_API_KEY}",
    "models": "gpt-4o,gpt-4o-mini,o1-preview",
    "provider": "openai",
    "enabled": true
  },
  {
    "id": "mistral-main",
    "url": "https://api.mistral.ai",
    "key": "${MISTRAL_API_KEY}",
    "models": "mistral-large-latest,pixtral-large-latest",
    "provider": "mistral",
    "enabled": true
  },
  {
    "id": "xai-grok",
    "url": "https://api.x.ai",
    "key": "${XAI_API_KEY}",
    "models": "grok-4,grok-3",
    "provider": "custom",
    "enabled": true,
    "custom": {
      "apiFormat": "openai-chat",
      "auth": { "header": "Authorization", "format": "Bearer {key}" },
      "endpoints": { "chat_completions": "/v1/chat/completions" },
      "features": { "vision": true, "function_calling": true, "structured_output": true },
      "onUnsupportedFeature": "throw"
    }
  }
]
```

With this single configuration you can have one agent declared with `model: "mistral-large-latest"` (routed to Mistral) and another with `model: "grok-4"` (routed to xAI), all running in parallel inside the same JVM. The agent layer doesn't need to know which provider it talks to — that's the whole point of the multi-instance router.
