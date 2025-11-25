# Agentic-Helper

A Java library for multi-provider AI orchestration (OpenAI, Azure OpenAI, Azure Anthropic).

High-level `AgentService` with rate limiting, retries, and structured outputs.

## Credits

This project was originally forked from [simple-openai](https://github.com/sashirestela/simple-openai) by [Sashir Estela](https://github.com/sashirestela).
The low-level OpenAI client code (`SimpleOpenAI`, domain classes, etc.) comes from that excellent library.

**Agentic-Helper** adds:
- `AgentService` for high-level agent orchestration
- Multi-provider support (OpenAI, Azure OpenAI, Azure Anthropic/Claude)
- JSON-based instance configuration
- Automatic rate limiting and retries
- Structured outputs with typed results
- Vector store RAG integration

## Table of Contents
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Features](#features)
  - [Multi-Provider Support](#multi-provider-support)
  - [Structured Outputs](#structured-outputs)
  - [Chat Completion](#chat-completion)
  - [Vector Store RAG](#vector-store-rag)
- [Environment Variables](#environment-variables)
- [License](#license)

## Installation

Add the dependency to your Maven project:

```xml
<dependency>
    <groupId>io.github.Yann-Favin-Leveque</groupId>
    <artifactId>agentic-helper</artifactId>
    <version>1.0.0</version>
</dependency>
```

Or using Gradle:

```groovy
dependencies {
    implementation 'io.github.Yann-Favin-Leveque:agentic-helper:1.0.0'
}
```

## Quick Start

```java
import io.github.sashirestela.openai.agent.*;

// 1. Configure instances via JSON (from environment variable)
String instancesJson = System.getenv("OPENAI_INSTANCES");

AgentServiceConfig config = AgentServiceConfig.fromJson(instancesJson)
    .agentResultClassPackage("com.example.results")
    .requestsPerSecond(5)
    .maxRetries(3)
    .build();

// 2. Create the service
AgentService service = new AgentService(config);

// 3. Use chat completion
List<ChatMessage> messages = List.of(
    SystemMessage.of("You are a helpful assistant."),
    UserMessage.of("What is the capital of France?")
);

DefaultResult result = service.chatCompletion("gpt-4o", messages, 0.7).join();
System.out.println(result.getResult());
```

## Configuration

### JSON Instance Configuration

Set the `OPENAI_INSTANCES` environment variable with your provider configurations:

```json
[
  {
    "id": "openai-main",
    "url": "https://api.openai.com",
    "key": "sk-xxx",
    "models": "gpt-4o,gpt-4o-mini",
    "provider": "openai",
    "enabled": true
  },
  {
    "id": "azure-1",
    "url": "https://my-resource.openai.azure.com",
    "key": "azure-key",
    "models": "gpt-4o",
    "provider": "azure",
    "apiVersion": "2024-08-01-preview",
    "enabled": true
  },
  {
    "id": "azure-anthropic",
    "url": "https://my-resource.services.ai.azure.com",
    "key": "azure-key",
    "models": "claude-sonnet-4-5",
    "provider": "azure-anthropic",
    "apiVersion": "2023-06-01",
    "enabled": true
  }
]
```

### Configuration Options

```java
AgentServiceConfig config = AgentServiceConfig.fromJson(instancesJson)
    .agentResultClassPackage("com.example.results")  // Package for result classes
    .agentJsonFolderPath("/config/agents")           // Path to agent JSON files
    .requestsPerSecond(5)                            // Rate limit (default: 5)
    .maxRetries(3)                                   // Max retry attempts (default: 3)
    .defaultResponseTimeout(120000L)                 // Timeout in ms (default: 120000)
    .retryBaseDelayMs(10000L)                        // Base retry delay (default: 10000)
    .rateLimitDelayMs(60000L)                        // Rate limit delay (default: 60000)
    .error502DelayMs(300000L)                        // 502 error delay (default: 300000)
    .build();
```

## Features

### Multi-Provider Support

AgentService supports three providers:

| Provider | Description | Authentication |
|----------|-------------|----------------|
| `openai` | OpenAI API | Bearer token |
| `azure` | Azure OpenAI | api-key header |
| `azure-anthropic` | Azure AI (Claude) | x-api-key + anthropic-version |

The service automatically detects the model family (OpenAI vs Anthropic) based on model name and uses the appropriate API format.

### Structured Outputs

Define typed result classes for guaranteed JSON schema compliance:

```java
// Define your result class
public class WeatherResult implements AgentResult {
    public String location;
    public double temperature;
    public String conditions;
    public String recommendation;
}

// Get typed response
WeatherResult result = service.chatCompletion(
    "gpt-4o",
    messages,
    0.7,
    WeatherResult.class
).join();

System.out.println(result.temperature);  // Type-safe access
```

Works with both OpenAI (`response_format`) and Claude (`output_format`)!

### Chat Completion

Simple stateless chat completions:

```java
// Simple (returns DefaultResult)
DefaultResult result = service.chatCompletion("gpt-4o", messages, 0.7).join();
String text = result.getResult();

// Typed (returns your custom class)
MyResult result = service.chatCompletion("gpt-4o", messages, 0.7, MyResult.class).join();
```

### Vector Store RAG

Integrate document search with retrieval-augmented generation:

```java
// 1. Upload document
String fileRef = service.uploadFileForAssistants(Paths.get("document.pdf")).join();

// 2. Create vector store
String vectorStoreRef = service.createVectorStore("Knowledge Base", List.of(fileRef)).join();

// 3. Request with vector store
String response = service.requestAgentWithVectorStorage(
    "research-assistant",
    "Summarize the key findings",
    vectorStoreRef
).join();

// 4. Clean up
service.deleteVectorStore(vectorStoreRef);
```

## Environment Variables

| Variable | Description |
|----------|-------------|
| `OPENAI_INSTANCES` | JSON array of instance configurations |
| `ENABLED_PROVIDERS` | Optional: Comma-separated list of providers to enable (e.g., `openai,azure`) |

### Provider Filtering

Use `ENABLED_PROVIDERS` to limit which providers are loaded:

```bash
# Only use OpenAI
export ENABLED_PROVIDERS=openai

# Only use Azure providers
export ENABLED_PROVIDERS=azure,azure-anthropic

# Use all providers (default)
unset ENABLED_PROVIDERS
```

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [simple-openai](https://github.com/sashirestela/simple-openai) by Sashir Estela - The foundation of this library
- [CleverClient](https://github.com/sashirestela/cleverclient) - HTTP client library
- [Bucket4j](https://github.com/bucket4j/bucket4j) - Rate limiting
