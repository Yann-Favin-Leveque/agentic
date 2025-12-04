# Agentic-Helper

A Java library for multi-provider AI orchestration (OpenAI, Azure OpenAI, Azure Anthropic/Claude).

High-level `AgentService` with rate limiting, retries, and structured outputs.

## Credits

This project was originally forked from [simple-openai](https://github.com/sashirestela/simple-openai) by [Sashir Estela](https://github.com/sashirestela).

**Agentic-Helper** adds:
- `AgentService` for high-level agent orchestration
- Multi-provider support (OpenAI, Azure OpenAI, Azure Anthropic/Claude)
- JSON-based instance configuration
- Automatic rate limiting and retries
- Structured outputs with typed results
- Vector store RAG integration
- Image generation support

## Table of Contents
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Features](#features)
  - [Multi-Provider Support](#multi-provider-support)
  - [Chat Completion](#chat-completion)
  - [Structured Outputs](#structured-outputs)
  - [Agent-based Requests](#agent-based-requests)
  - [Vector Store RAG](#vector-store-rag)
  - [Image Generation](#image-generation)
- [Environment Variables](#environment-variables)
- [License](#license)

## Installation

### Option 1: Local Install (Recommended for development)

```bash
# Clone and install locally
git clone https://github.com/Yann-Favin-Leveque/agentic-helper.git
cd agentic-helper
mvn clean install -DskipTests
```

Then add to your project's `pom.xml`:

```xml
<dependency>
    <groupId>io.github.Yann-Favin-Leveque</groupId>
    <artifactId>agentic-helper</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Option 2: GitHub Packages

Add the repository to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/Yann-Favin-Leveque/agentic-helper</url>
    </repository>
</repositories>

<dependency>
    <groupId>io.github.Yann-Favin-Leveque</groupId>
    <artifactId>agentic-helper</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

```java
import io.github.sashirestela.openai.agent.*;
import io.github.sashirestela.openai.domain.chat.ChatMessage;

// 1. Configure instances via JSON
String instancesJson = System.getenv("OPENAI_INSTANCES");

AgentServiceConfig config = AgentServiceConfig.fromJson(instancesJson)
    .agentResultClassPackage("com.example.results")
    .requestsPerSecond(5)
    .build();

// 2. Create the service
AgentService service = new AgentService(config);

// 3. Use chat completion
List<ChatMessage> messages = List.of(
    ChatMessage.SystemMessage.of("You are a helpful assistant."),
    ChatMessage.UserMessage.of("What is the capital of France?")
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

### Spring Boot Integration

```java
@Configuration
public class AgentServiceConfiguration {

    @Value("${openai.instances}")
    private String instancesJson;

    @Bean
    public AgentService agentService() {
        AgentServiceConfig config = AgentServiceConfig.fromJson(instancesJson)
            .agentResultClassPackage("com.example.results")
            .agentJsonFolderPath("src/main/resources/agents")
            .requestsPerSecond(15)
            .build();

        return new AgentService(config);
    }
}
```

## Features

### Multi-Provider Support

AgentService supports three providers:

| Provider | Description | Authentication | Models |
|----------|-------------|----------------|--------|
| `openai` | OpenAI API | Bearer token | gpt-4o, gpt-4o-mini, etc. |
| `azure` | Azure OpenAI | api-key header | gpt-4o (deployed) |
| `azure-anthropic` | Azure AI (Claude) | x-api-key + anthropic-version | claude-sonnet-4-5, etc. |

The service automatically detects the model family (OpenAI vs Anthropic) based on model name and uses the appropriate API format.

### Chat Completion

Simple stateless chat completions:

```java
// Simple (returns DefaultResult)
DefaultResult result = service.chatCompletion("gpt-4o", messages, 0.7).join();
String text = result.getResult();

// Typed with Class (compile-time type safety)
WeatherResult result = service.chatCompletion("gpt-4o", messages, 0.7, WeatherResult.class).join();

// Typed with String class name (runtime resolution from agentResultClassPackage)
AgentResult result = service.chatCompletion("gpt-4o", messages, 0.7, "WeatherResult").join();
```

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

### Agent-based Requests

Define agents in JSON files and use them for complex interactions:

**Agent JSON file** (`src/main/resources/agents/weather-agent.json`):
```json
{
  "id": "weather-agent",
  "name": "Weather Assistant",
  "model": "gpt-4o",
  "instructions": "You are a weather expert. Provide accurate weather information.",
  "temperature": 0.7,
  "resultClass": "WeatherResult",
  "retrieval": false
}
```

**Usage:**
```java
// Simple request
String response = service.requestAgent("weather-agent", "What's the weather in Paris?").join();

// With conversation history
String response = service.requestAgent("weather-agent", "What about tomorrow?", threadRef).join();

// With vector store (RAG)
String response = service.requestAgentWithVectorStorage(
    "research-agent",
    "Summarize the key findings",
    vectorStoreRef
).join();
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
service.deleteFile(fileRef);
```

### Image Generation

Generate images using DALL-E:

```java
// Generate image and get URL
String imageUrl = service.generateImage(
    "A beautiful sunset over mountains",
    "dall-e-3",
    Size.X1024,
    Quality.HD
).join();

// Generate and save to file
Path imagePath = service.generateImageToFile(
    "A futuristic city",
    "dall-e-3",
    Size.X1024,
    Quality.STANDARD,
    Paths.get("output.png")
).join();
```

## Environment Variables

| Variable | Description |
|----------|-------------|
| `OPENAI_INSTANCES` | JSON array of instance configurations (required) |
| `ENABLED_PROVIDERS` | Optional: Comma-separated list of providers to enable |

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

## API Reference

### AgentService Methods

| Method | Description |
|--------|-------------|
| `chatCompletion(model, messages, temp)` | Simple chat completion |
| `chatCompletion(model, messages, temp, Class)` | Typed chat completion |
| `chatCompletion(model, messages, temp, String)` | Typed chat completion (class name) |
| `requestAgent(agentId, prompt)` | Request using agent definition |
| `requestAgent(agentId, prompt, threadRef)` | Request with conversation history |
| `requestAgentWithVectorStorage(agentId, prompt, vectorStoreRef)` | Request with RAG |
| `uploadFileForAssistants(path)` | Upload file for assistants |
| `createVectorStore(name, fileRefs)` | Create vector store |
| `deleteVectorStore(ref)` | Delete vector store |
| `deleteFile(ref)` | Delete uploaded file |
| `generateImage(prompt, model, size, quality)` | Generate image |
| `generateImageToFile(prompt, model, size, quality, path)` | Generate and save image |

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [simple-openai](https://github.com/sashirestela/simple-openai) by Sashir Estela - The foundation of this library
- [CleverClient](https://github.com/sashirestela/cleverclient) - HTTP client library
- [Bucket4j](https://github.com/bucket4j/bucket4j) - Rate limiting

## Changelog

### v1.0.4 (2025-12-04)

#### Improvements
- ✅ Added retry logic to embedding and image generation
- ✅ Added retry logic to all chat completion variants
- ✅ Improved retry for rate limits (respects retry-after header)
- ✅ Progressive timeout for consecutive timeout errors
- ✅ Smart retry: skip 4xx client errors (except 429)
- ✅ Compact logs: all logs on single line (Logback config)
- ✅ Improved log levels (TRACE/DEBUG/INFO/WARN/ERROR)

#### Breaking Changes
None - fully backward compatible
