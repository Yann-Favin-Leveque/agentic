# AgentService - Configuration Examples

This document provides examples for configuring and using `AgentService` from the `simple-openai` library.

## Table of Contents
- [JSON-Based Configuration (Recommended)](#json-based-configuration-recommended)
- [Spring Boot Integration](#spring-boot-integration)
- [Using Embeddings](#using-embeddings)
- [Using Chat Completions](#using-chat-completions)
- [Using Image Generation](#using-image-generation)
- [Direct Client Access](#direct-client-access)

---

## JSON-Based Configuration (Recommended)

### Environment Variable Format

Create an `OPENAI_INSTANCES` environment variable with JSON array of instances:

```json
[
  {
    "id": "openai-main",
    "url": "https://api.openai.com/v1",
    "key": "sk-proj-xxx",
    "models": "gpt-4o,gpt-4o-mini,text-embedding-3-small,dall-e-3",
    "provider": "openai",
    "apiVersion": null
  },
  {
    "id": "azure-eastus",
    "url": "https://my-resource.cognitiveservices.azure.com",
    "key": "azure-key-xxx",
    "models": "gpt-4o,text-embedding-3-small",
    "provider": "azure",
    "apiVersion": "2024-08-01-preview"
  },
  {
    "id": "azure-dalle-australia",
    "url": "https://robin-mgkxyzvq-australiaeast.cognitiveservices.azure.com",
    "key": "azure-key-yyy",
    "models": "dall-e-3,text-embedding-3-small",
    "provider": "azure",
    "apiVersion": "2024-04-01-preview"
  }
]
```

### Instance Configuration Fields

| Field | Required | Description | Example |
|-------|----------|-------------|---------|
| `id` | ✅ | Unique identifier for the instance | `"openai-main"` |
| `url` | ✅ | Base URL of the API endpoint | `"https://api.openai.com/v1"` |
| `key` | ✅ | API Key for authentication | `"sk-proj-xxx"` |
| `models` | ✅ | Comma-separated list of deployed models | `"gpt-4o,text-embedding-3-small"` |
| `provider` | ✅ | Provider type: `"openai"` or `"azure"` | `"azure"` |
| `apiVersion` | 🟡 | API version (required for Azure, optional for OpenAI) | `"2024-08-01-preview"` |
| `enabled` | 🟢 | Whether instance should be loaded (default: `true`) | `true` or `false` |

### Disabling Instances (v3.31.0+)

You can temporarily disable instances without removing them from the configuration:

```json
[
  {
    "id": "openai-main",
    "url": "https://api.openai.com/v1",
    "key": "sk-proj-xxx",
    "models": "gpt-4o",
    "provider": "openai",
    "enabled": false
  },
  {
    "id": "azure-eastus",
    "url": "https://my-resource.cognitiveservices.azure.com",
    "key": "azure-key-xxx",
    "models": "gpt-4o",
    "provider": "azure",
    "apiVersion": "2024-08-01-preview",
    "enabled": true
  }
]
```

In this example, only the Azure instance will be loaded. The OpenAI instance remains in the configuration for easy re-enabling later.

**Logs will show:** `Loaded 1 instance(s) from JSON configuration (2 total, 1 enabled)`

---

## Spring Boot Integration

### 1. Application Properties

```properties
# application.properties
openai.instances=${OPENAI_INSTANCES}
```

### 2. Spring Configuration Class

```java
package com.example.config;

import io.github.sashirestela.openai.agent.AgentService;
import io.github.sashirestela.openai.agent.AgentServiceConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class AgentServiceConfiguration {

    @Value("${openai.instances}")
    private String instancesJson;

    @Bean
    public AgentService agentService() throws IOException {
        AgentServiceConfig config = AgentServiceConfig.builder()
                .instancesJson(instancesJson)
                .requestsPerSecond(15)  // Rate limiting
                .agentJsonFolderPath("src/main/resources/agents")  // Optional: agent definitions
                .agentResultClassPackage("com.example.agents.results")  // Optional: agent result classes
                .build();

        return new AgentService(config);
    }
}
```

### 3. Environment Variable (.env)

```bash
# Single-line format for .env file
OPENAI_INSTANCES='[{"id":"openai-main","url":"https://api.openai.com/v1","key":"sk-proj-xxx","models":"gpt-4o,gpt-4o-mini,text-embedding-3-small,dall-e-3","provider":"openai","apiVersion":null},{"id":"azure-eastus","url":"https://my-resource.cognitiveservices.azure.com","key":"azure-key-xxx","models":"gpt-4o,text-embedding-3-small","provider":"azure","apiVersion":"2024-08-01-preview"}]'
```

---

## Using Embeddings

### Basic Usage

```java
@Service
public class EmbeddingService {

    @Autowired
    private AgentService agentService;

    public CompletableFuture<float[]> generateEmbedding(String text) {
        // Use default model (text-embedding-3-small)
        return agentService.generateEmbedding(text);
    }

    public CompletableFuture<float[]> generateEmbeddingWithModel(String text, String model) {
        // Specify custom model
        return agentService.generateEmbedding(text, "text-embedding-3-large");
    }
}
```

### Example with Error Handling

```java
public float[] generateEmbeddingSync(String text) {
    try {
        return agentService.generateEmbedding(text, "text-embedding-3-small")
                .exceptionally(ex -> {
                    logger.error("Embedding generation failed: {}", ex.getMessage());
                    return new float[1536];  // Return empty vector on error
                })
                .join();  // Block until complete
    } catch (Exception e) {
        logger.error("Failed to generate embedding", e);
        throw new RuntimeException("Embedding generation error", e);
    }
}
```

### Supported Embedding Models

- `text-embedding-3-small` - 1536 dimensions (default, fast, economical)
- `text-embedding-3-large` - 3072 dimensions (higher quality)
- `text-embedding-ada-002` - 1536 dimensions (legacy)

---

## Using Chat Completions

### Simple Chat Request

```java
CompletableFuture<String> response = agentService.requestChatCompletion(
    "What is the capital of France?",
    "gpt-4o",
    1000  // max tokens
);
```

### Structured Output

```java
CompletableFuture<String> jsonResponse = agentService.requestStructuredChatCompletion(
    "Extract person info from: John Doe, age 30, lives in Paris",
    "gpt-4o",
    PersonInfo.class,  // Your POJO class
    1000
);
```

---

## Using Image Generation

### Generate Image with DALL-E

```java
// Simple generation
CompletableFuture<String> base64Image = agentService.generateImage(
    "A futuristic city with flying cars"
);

// With custom parameters
CompletableFuture<String> customImage = agentService.generateImage(
    "A cat wearing sunglasses",
    "dall-e-3",
    Size.SIZE_1024_1024,
    Quality.HD
);
```

---

## Direct Client Access

### Get SimpleOpenAI Client

```java
// Get client from first available instance
SimpleOpenAI client = agentService.getSimpleOpenAI();

// Use for custom operations
var embeddingRequest = EmbeddingRequest.builder()
    .model("text-embedding-3-small")
    .input("my text")
    .build();

var result = client.embeddings().create(embeddingRequest).join();
```

### Get Client from Specific Instance

```java
// Get client from specific instance by ID
SimpleOpenAI azureClient = agentService.getSimpleOpenAI("azure-eastus");

// Use for operations specific to that instance
var chatRequest = ChatRequest.builder()
    .model("gpt-4o")
    .messages(List.of(ChatMessage.builder().content("Hello").build()))
    .build();

var chat = azureClient.chatCompletions().create(chatRequest).join();
```

**⚠️ Warning:** When using `getSimpleOpenAI()`, you bypass AgentService's rate limiting and load balancing. Use for advanced scenarios only.

---

## Complete Example

### Full Application Example

```java
package com.example.demo;

import io.github.sashirestela.openai.agent.AgentService;
import io.github.sashirestela.openai.agent.AgentServiceConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.IOException;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public AgentService agentService() throws IOException {
        String instancesJson = System.getenv("OPENAI_INSTANCES");

        AgentServiceConfig config = AgentServiceConfig.builder()
                .instancesJson(instancesJson)
                .requestsPerSecond(15)
                .build();

        return new AgentService(config);
    }
}
```

### Service Using AgentService

```java
package com.example.service;

import io.github.sashirestela.openai.agent.AgentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class AiService {

    @Autowired
    private AgentService agentService;

    public CompletableFuture<float[]> embedText(String text) {
        return agentService.generateEmbedding(text, "text-embedding-3-small");
    }

    public CompletableFuture<String> chat(String message) {
        return agentService.requestChatCompletion(message, "gpt-4o", 1000);
    }

    public CompletableFuture<String> generateImage(String prompt) {
        return agentService.generateImage(prompt);
    }
}
```

---

## Advanced Configuration

### Custom Rate Limiting

```java
AgentServiceConfig config = AgentServiceConfig.builder()
        .instancesJson(instancesJson)
        .requestsPerSecond(50)  // 50 requests per second
        .maxRetries(5)           // Retry failed requests 5 times
        .build();
```

### Agent Result Classes

If you're using Agent API with structured outputs:

```java
AgentServiceConfig config = AgentServiceConfig.builder()
        .instancesJson(instancesJson)
        .agentJsonFolderPath("src/main/resources/agents")
        .agentResultClassPackage("com.example.agents.results")
        .build();
```

---

## Migration from Legacy Configuration

If you're migrating from the old configuration format (separate fields for OpenAI/Azure):

### Old Format (Deprecated)
```java
AgentServiceConfig config = AgentServiceConfig.builder()
        .openAiApiKeys(List.of("sk-xxx"))
        .openAiBaseUrls(List.of("https://api.openai.com/v1"))
        .openAiModels("gpt-4o,text-embedding-3-small")
        .azureApiKeys(List.of("azure-key"))
        .azureBaseUrls(List.of("https://my-resource.openai.azure.com"))
        .azureApiVersion("2024-08-01-preview")
        .azureModels("gpt-4o")
        .build();
```

### New Format (Recommended)
```java
String json = """
[
  {
    "id": "openai-main",
    "url": "https://api.openai.com/v1",
    "key": "sk-xxx",
    "models": "gpt-4o,text-embedding-3-small",
    "provider": "openai"
  },
  {
    "id": "azure-main",
    "url": "https://my-resource.openai.azure.com",
    "key": "azure-key",
    "models": "gpt-4o",
    "provider": "azure",
    "apiVersion": "2024-08-01-preview"
  }
]
""";

AgentServiceConfig config = AgentServiceConfig.builder()
        .instancesJson(json)
        .build();
```

---

## Troubleshooting

### No instances configured
```
IllegalStateException: No OpenAI instances configured
```
**Solution:** Ensure `OPENAI_INSTANCES` environment variable is set and contains valid JSON.

### Model not found
```
IllegalArgumentException: No instance found with model: text-embedding-3-small
```
**Solution:** Verify that at least one instance in your JSON config includes the model in its `models` field.

### JSON parsing error
```
IllegalArgumentException: Failed to parse instancesJson
```
**Solution:** Validate your JSON syntax. Use a JSON validator tool.

---

## Additional Resources

- [OpenAI API Documentation](https://platform.openai.com/docs/api-reference)
- [Azure OpenAI Service Documentation](https://learn.microsoft.com/en-us/azure/ai-services/openai/)
- [simple-openai GitHub Repository](https://github.com/Yann-Favin-Leveque/simple-openai)

---

**Version:** 3.31.0+
**Last Updated:** November 2025
