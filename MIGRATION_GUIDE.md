# Migration Guide: Stateless API (V2)

This guide explains how to migrate from the Assistants-based API to the new stateless API in agentic-helper.

## Overview

The new V2 API uses:
- **OpenAI Responses API** (`POST /v1/responses`) for OpenAI models
- **Claude Messages API** (`POST /v1/messages`) for Anthropic models

This provides a unified, stateless architecture where:
- No threads or assistants need to be created
- Conversation history is passed with each request
- Provider is auto-detected from the model name

## Quick Migration

### Before (V1 - Assistants API)

```java
// 1. Create agent on startup (sync assistants)
agentService.createAgent("my-agent").join();

// 2. Create thread
String threadId = agentService.createThread("gpt-4o").join();

// 3. Send message with thread
AgentResult result = agentService.requestAgent("my-agent", "Hello", threadId).join();

// 4. Continue conversation
AgentResult result2 = agentService.requestAgent("my-agent", "Follow up", threadId).join();

// 5. Delete thread when done
agentService.deleteThread(threadId).join();
```

### After (V2 - Stateless API)

```java
// No assistant creation needed!

// 1. Send message (single-turn)
AgentResult result = agentService.requestAgentV2("my-agent", "Hello").join();

// 2. Continue conversation with history
List<Message> history = List.of(
    Message.user("Hello"),
    Message.assistant(result.getContent())
);
AgentResult result2 = agentService.requestAgentV2("my-agent", "Follow up", history).join();
```

## Agent Definition Changes

### New Fields

```json
{
  "id": "101",
  "name": "My Agent",
  "model": "gpt-4.1",
  "instructions": "You are a helpful assistant.",
  "resultClass": "MyResult",

  // NEW FIELDS
  "webSearch": true,           // Enable web search (OpenAI: web_search_preview, Claude: web_search_20250305)
  "codeInterpreter": true,     // Enable code interpreter (OpenAI only)
  "functions": [               // Custom functions
    {
      "name": "get_weather",
      "description": "Get weather for a location",
      "methodClass": "com.example.WeatherFunction",
      "parameterClass": "com.example.WeatherParams"
    }
  ],
  "description": "Optional description"
}
```

### Deprecated Fields

These fields are still supported for backward compatibility but will be removed in a future version:

```json
{
  "isOpenAI": true,           // DEPRECATED: Auto-detected from model name
  "assistantIds": [...],       // DEPRECATED: No assistants needed
  "createOnAppStart": true     // DEPRECATED: No assistants needed
}
```

## API Method Changes

### Deprecated Methods

| Method | Replacement |
|--------|-------------|
| `requestAgent(agentId, message, threadRef)` | `requestAgentV2(agentId, message, history)` |
| `requestAgent(agentId, message)` | `requestAgentV2(agentId, message)` |
| `createThread(model)` | Not needed - use history |
| `sendMessageToThread(...)` | Not needed - use history |
| `deleteThread(threadRef)` | Not needed |
| `createAgent(agentId)` | Not needed |
| `createAllAgents()` | Not needed |

### New Methods

```java
// Single-turn request
CompletableFuture<AgentResult> requestAgentV2(String agentId, String userMessage);

// Multi-turn request with history
CompletableFuture<AgentResult> requestAgentV2(String agentId, String userMessage, List<Message> history);
```

## Message History

The new `Message` class provides a unified format for conversation history:

```java
import io.github.yannfavinleveque.agentic.agent.model.Message;

// Create messages
Message userMsg = Message.user("What's the weather?");
Message assistantMsg = Message.assistant("The weather is sunny.");
Message systemMsg = Message.system("You are helpful.");

// Build history
List<Message> history = new ArrayList<>();
history.add(Message.user("Hello"));
history.add(Message.assistant("Hi there!"));
history.add(Message.user("How are you?"));
history.add(Message.assistant("I'm doing well!"));

// Use in request
AgentResult result = agentService.requestAgentV2("my-agent", "New question", history).join();
```

## Tool Support

### Web Search

```json
{
  "webSearch": true
}
```

- **OpenAI**: Uses `web_search_preview` tool
- **Claude**: Uses `web_search_20250305` tool

### Code Interpreter

```json
{
  "codeInterpreter": true
}
```

- **OpenAI only**: Enables code execution sandbox
- **Claude**: Not supported (ignored)

### Custom Functions

```json
{
  "functions": [
    {
      "name": "get_weather",
      "description": "Get current weather for a location",
      "methodClass": "com.example.functions.WeatherFunction",
      "parameterClass": "com.example.functions.WeatherParams"
    }
  ]
}
```

## Provider Detection

The provider is now automatically detected from the model name:

| Model Prefix | Provider | API Used |
|--------------|----------|----------|
| `claude-*` | Anthropic | Messages API |
| `gpt-*`, `o1-*`, etc. | OpenAI | Responses API |

You no longer need to set `isOpenAI` - it's determined automatically.

## Example: Complete Migration

### Before

```java
@Service
public class ChatService {

    @Autowired
    private AgentService agentService;

    private String currentThreadId;

    @PostConstruct
    public void init() {
        // Create assistants on startup
        agentService.createAllAgents().join();
    }

    public String startConversation(String model) {
        currentThreadId = agentService.createThread(model).join();
        return currentThreadId;
    }

    public String chat(String message) {
        AgentResult result = agentService.requestAgent("chat-agent", message, currentThreadId).join();
        return result.getContent();
    }

    public void endConversation() {
        agentService.deleteThread(currentThreadId).join();
        currentThreadId = null;
    }
}
```

### After

```java
@Service
public class ChatService {

    @Autowired
    private AgentService agentService;

    private List<Message> conversationHistory = new ArrayList<>();

    // No @PostConstruct needed!

    public String chat(String message) {
        // Add user message to history
        conversationHistory.add(Message.user(message));

        // Request with history
        AgentResult result = agentService.requestAgentV2("chat-agent", message, conversationHistory).join();

        // Add assistant response to history
        conversationHistory.add(Message.assistant(result.getContent()));

        return result.getContent();
    }

    public void clearConversation() {
        conversationHistory.clear();
    }
}
```

## Benefits of V2 API

1. **Simpler setup**: No assistant creation, no thread management
2. **Stateless**: No server-side state to manage
3. **Faster cold starts**: No sync needed on application startup
4. **Unified API**: Same pattern for OpenAI and Claude
5. **Built-in tools**: Easy web search and code interpreter support
6. **Better scalability**: No thread limits, no assistant quotas

## Backward Compatibility

The V1 API is still available and functional. You can migrate gradually:

1. Start using `requestAgentV2()` for new code
2. Remove `createAgent()`/`createAllAgents()` calls
3. Replace thread-based conversations with history-based ones
4. Update agent JSON definitions to use new fields
5. Remove deprecated fields from agent definitions

## Questions?

If you encounter issues during migration, please open an issue on GitHub.
