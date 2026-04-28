# Agentic-Helper

A Java library for multi-provider AI orchestration. Out of the box: OpenAI, Azure OpenAI, Anthropic/Claude, Azure Anthropic, Mistral, Azure Mistral, xAI Grok, Azure Grok, DeepSeek, Google Gemini — plus a JSON-driven `Provider.CUSTOM` for any other OpenAI-compatible endpoint.

High-level `AgentService` with rate limiting, retries, structured outputs, and per-provider feature gating.

## Credits

This project was originally forked from [simple-openai](https://github.com/sashirestela/simple-openai) by [Sashir Estela](https://github.com/sashirestela).

**Agentic-Helper** adds:
- `AgentService` for high-level agent orchestration
- **11 built-in providers** + a JSON-spec `Provider.CUSTOM` for anything else
- JSON-based instance configuration with per-instance rate limiting
- Automatic retries with exponential backoff
- Structured outputs with typed results (JSON Schema)
- Stateless API on top of OpenAI Responses API + Anthropic Messages API + Chat Completions
- **Autonomous Agent Mode** — agents run multi-step tool loops independently
- Web search, code interpreter, and function calling tools
- Vision (multimodal) support, image generation (DALL-E), embeddings
- Reasoning models support (o-series, Magistral, Grok-3-mini/4, DeepSeek-reasoner, Gemini-2.5-thinking)
- Custom per-model pricing for unknown models (`CustomProviderSpec.modelPricing`)

## Table of Contents
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Agent Requests](#agent-requests)
  - [Simple Request](#simple-request)
  - [With System Prompt](#with-system-prompt)
  - [Multi-turn Conversations](#multi-turn-conversations)
  - [Vision (Images)](#vision-images)
  - [Web Search](#web-search)
  - [Function Calling](#function-calling)
  - [Code Interpreter](#code-interpreter)
- [Autonomous Agent Mode](#autonomous-agent-mode)
  - [Overview](#overview)
  - [How It Works](#how-it-works)
  - [Basic Usage](#basic-usage)
  - [ToolExecutor Interface](#toolexecutor-interface)
  - [Structured Results with resultClass](#structured-results-with-resultclass)
  - [Conversation Management](#conversation-management)
  - [Tool Output Trimming](#tool-output-trimming)
  - [Agent Reflection (Thinking Aloud)](#agent-reflection-thinking-aloud)
  - [Ending the Turn: `endsTurn` and `endTurnOnPlainReply`](#ending-the-turn-endsturn-and-endturnonplainreply)
  - [Tool Groups](#tool-groups)
  - [Context Compaction](#context-compaction)
  - [Infinite / Observer Loops](#infinite--observer-loops)
  - [JSON Configuration](#json-configuration)
  - [Full Example](#full-example)
- [Embeddings](#embeddings)
- [Image Generation](#image-generation)
- [Agent JSON Schema](#agent-json-schema)
- [Environment Variables](#environment-variables)
- [License](#license)

## Installation

### Option 1: Local Install (Recommended for development)

```bash
# Clone and install locally
git clone https://github.com/Yann-Favin-Leveque/agentic.git
cd agentic
mvn clean install -DskipTests
```

Then add to your project's `pom.xml`:

```xml
<dependency>
    <groupId>io.github.yann-favin-leveque</groupId>
    <artifactId>agentic-helper</artifactId>
    <version>1.23.0</version>
</dependency>
```

### Option 2: Maven Central

The library is published on Maven Central. No extra repository configuration needed:

```xml
<dependency>
    <groupId>io.github.yann-favin-leveque</groupId>
    <artifactId>agentic-helper</artifactId>
    <version>1.23.0</version>
</dependency>
```

## Quick Start

```java
import io.github.yannfavinleveque.agentic.agent.service.AgentService;
import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;

// 1. Configure instances via JSON
String instancesJson = System.getenv("LLM_INSTANCES");

AgentServiceConfig config = AgentServiceConfig.builder()
    .instancesJson(instancesJson)
    .requestsPerSecond(5)
    .build();

// 2. Create the service
AgentService service = new AgentService(config);

// 3. Register an agent programmatically
service.registerAgent(Agent.builder()
    .id("assistant")
    .name("My Assistant")
    .model("gpt-4o")
    .instructions("You are a helpful assistant.")
    .build());

// 4. Make a request
AgentResult result = service.requestAgent("assistant", "What is the capital of France?")
    .get(60, TimeUnit.SECONDS);

System.out.println(result.getContent());
// Output: The capital of France is Paris.

// OR: Use a model directly (no agent registration needed)
AgentResult result2 = service.requestModel("gpt-4o", "What is 2+2?")
    .get(60, TimeUnit.SECONDS);
```

### Direct Model Usage (No Agent Registration)

Use `requestModel()` to call any model directly without registering an agent:

```java
// Simple request with model name
AgentResult result = service.requestModel("gpt-4o", "Hello!")
    .get(60, TimeUnit.SECONDS);

// With options (web search, structured output, images, etc.)
AgentResult result = service.requestModel("gpt-4o", "What is today's date?",
    ModelRequestOptions.withWebSearch())
    .get(60, TimeUnit.SECONDS);

// With code interpreter
AgentResult result = service.requestModel("gpt-4o", "Calculate factorial of 10",
    ModelRequestOptions.withCodeInterpreter())
    .get(60, TimeUnit.SECONDS);

// With structured output
AgentResult result = service.requestModel("gpt-4o", "Analyze this data",
    ModelRequestOptions.withResultClass(MyResult.class))
    .get(60, TimeUnit.SECONDS);

// With multiple options
AgentResult result = service.requestModel("gpt-4o", "Research and analyze",
    ModelRequestOptions.builder()
        .webSearch(true)
        .temperature(0.7)
        .maxTokens(2000)
        .instructions("You are a research assistant")
        .build())
    .get(120, TimeUnit.SECONDS);
```

## Configuration

### JSON Instance Configuration

Set the `LLM_INSTANCES` environment variable with your provider configurations:

```json
[
  {
    "id": "openai-main",
    "url": "https://api.openai.com",
    "key": "sk-xxx",
    "models": "gpt-4o,gpt-4o-mini,text-embedding-3-small,dall-e-3",
    "provider": "openai",
    "enabled": true
  },
  {
    "id": "azure-1",
    "url": "https://my-resource.openai.azure.com",
    "key": "azure-key",
    "models": "gpt-4o,gpt-5.1-chat",
    "provider": "azure-openai",
    "apiVersion": "2024-08-01-preview",
    "enabled": true
  },
  {
    "id": "anthropic-main",
    "url": "https://api.anthropic.com",
    "key": "sk-ant-xxx",
    "models": "claude-opus-4-7,claude-sonnet-4-7,claude-haiku-4-7",
    "provider": "anthropic",
    "enabled": true
  },
  {
    "id": "azure-anthropic",
    "url": "https://my-resource.services.ai.azure.com",
    "key": "azure-key",
    "models": "claude-sonnet-4-7,claude-haiku-4-7",
    "provider": "azure-anthropic",
    "apiVersion": "2023-06-01",
    "enabled": true
  }
]
```

**Instance Configuration Fields:**

| Field | Required | Description |
|-------|----------|-------------|
| `id` | Yes | Unique identifier for the instance |
| `url` | Yes | Base URL of the API endpoint |
| `key` | Yes | API Key for authentication |
| `models` | Yes | Comma-separated list of deployed models |
| `provider` | Yes | Provider type: `openai`, `azure-openai`, `anthropic`, or `azure-anthropic` |
| `apiVersion` | Azure only | API version (required for Azure providers) |
| `enabled` | No | Whether instance should be loaded (default: `true`) |

### Configuration Options

```java
AgentServiceConfig config = AgentServiceConfig.builder()
    .instancesJson(instancesJson)              // Required: JSON string with instances
    .requestsPerSecond(5)                      // Rate limit per instance (default: 5)
    .maxRetries(3)                             // Max retry attempts (default: 3)
    .defaultResponseTimeout(120000L)           // Timeout in ms (default: 120000)
    .build();
```

### Spring Boot Integration

```java
@Configuration
public class AgentServiceConfiguration {

    @Value("${llm.instances}")
    private String instancesJson;

    @Bean
    public AgentService agentService() {
        AgentServiceConfig config = AgentServiceConfig.builder()
            .instancesJson(instancesJson)
            .requestsPerSecond(15)
            .build();

        return new AgentService(config);
    }
}
```

## Agent Requests

### Simple Request

```java
// Register agent
service.registerAgent(Agent.builder()
    .id("simple")
    .name("Simple Agent")
    .model("gpt-4o")
    .build());

// Make request
AgentResult result = service.requestAgent("simple", "What is 2+2?")
    .get(60, TimeUnit.SECONDS);

System.out.println(result.getContent()); // "4"
```

### With System Prompt

```java
service.registerAgent(Agent.builder()
    .id("pirate")
    .name("Pirate Agent")
    .model("gpt-4o")
    .instructions("You are a pirate. Always respond like a pirate would.")
    .build());

AgentResult result = service.requestAgent("pirate", "Hello!")
    .get(60, TimeUnit.SECONDS);

System.out.println(result.getContent());
// "Ahoy, matey! Welcome aboard!"
```

### Multi-turn Conversations

#### Automatic History Management (Recommended)

Use `createConversation()` for automatic history management:

```java
// Create a conversation
String convId = service.createConversation();

// First turn
AgentResult result1 = service.requestAgent("assistant", "My name is Alice.", convId)
    .get(60, TimeUnit.SECONDS);

// Second turn - history is managed automatically!
AgentResult result2 = service.requestAgent("assistant", "What is my name?", convId)
    .get(60, TimeUnit.SECONDS);

System.out.println(result2.getContent()); // "Your name is Alice."

// Clean up when done
service.deleteConversation(convId);
```

#### Manual History Management

You can also manage history manually if needed:

```java
import io.github.yannfavinleveque.agentic.agent.model.Message;

List<Message> history = new ArrayList<>();

// First turn
AgentResult result1 = service.requestAgent("assistant", "My name is Alice.")
    .get(60, TimeUnit.SECONDS);

// Add to history manually
history.add(Message.user("My name is Alice."));
history.add(Message.assistant(result1.getContent()));

// Second turn - with manual history
AgentResult result2 = service.requestAgent("assistant", "What is my name?", history)
    .get(60, TimeUnit.SECONDS);

System.out.println(result2.getContent()); // "Your name is Alice."
```

### Vision (Images)

Send images for analysis using multimodal messages:

```java
service.registerAgent(Agent.builder()
    .id("vision")
    .name("Vision Agent")
    .model("gpt-4o")  // or claude-haiku-4-5
    .instructions("You are an image analyst.")
    .build());

// Create message with image
List<Message> history = new ArrayList<>();
history.add(Message.builder()
    .role("user")
    .content(List.of(
        Message.ContentPart.text("What color is this?"),
        Message.ContentPart.pngBase64(imageBase64)  // Base64 encoded PNG
    ))
    .build());

AgentResult result = service.requestAgent("vision", "Analyze the image.", history)
    .get(60, TimeUnit.SECONDS);
```

**Supported image formats:**
- `Message.ContentPart.pngBase64(base64)` - PNG image
- `Message.ContentPart.jpegBase64(base64)` - JPEG image
- `Message.ContentPart.imageUrl(url)` - Image from URL

### Web Search

Enable web search for real-time information:

```java
service.registerAgent(Agent.builder()
    .id("searcher")
    .name("Web Search Agent")
    .model("gpt-4o")  // or claude-haiku-4-5
    .instructions("Use web search to find current information.")
    .webSearch(true)  // Enable web search
    .build());

AgentResult result = service.requestAgent("searcher", "What is today's weather in Paris?")
    .get(120, TimeUnit.SECONDS);
```

### Function Calling

Define custom functions for the agent to call:

```java
import io.github.yannfavinleveque.agentic.agent.model.FunctionConfig;

service.registerAgent(Agent.builder()
    .id("weather-bot")
    .name("Weather Bot")
    .model("gpt-4o")
    .instructions("Use the get_weather function when asked about weather.")
    .functions(List.of(
        FunctionConfig.builder()
            .name("get_weather")
            .description("Get current weather for a location")
            .parameters(Map.of(
                "type", "object",
                "properties", Map.of(
                    "location", Map.of("type", "string", "description", "City name")
                ),
                "required", List.of("location")
            ))
            .build()
    ))
    .build());

AgentResult result = service.requestAgent("weather-bot", "What's the weather in London?")
    .get(60, TimeUnit.SECONDS);

// Check if function was called
if (result.getContent().contains("Function call:")) {
    // Handle function call and continue conversation
}
```

**`FunctionConfig` advanced fields** (used by autonomous agents — see
[Ending the Turn](#ending-the-turn-endsturn-and-endturnonplainreply) and
[Tool Groups](#tool-groups)):

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `endsTurn` | boolean | `false` | When `true`, calling this tool ends the autonomous turn after the tool result is stored. Replaces the legacy hardcoded `task_over` with any custom end-of-turn tool (e.g. `ask_user`, `task_complete`). |
| `group` | string | `null` | Tool-group tag. When the agent defines `enabledToolGroups`, only functions whose `group` is `null` / `"default"` / in the enabled set are exposed to the LLM. Hidden functions stay registered so the caller can still execute them. |
| `executorClass` | string | `null` | Fully qualified (or simple) class name implementing `ToolExecutor`, used as a fallback executor when no lambda executor is supplied at call time. Lambda takes priority. |

```java
FunctionConfig askUser = FunctionConfig.builder()
    .name("ask_user")
    .description("Ask the user a clarifying question")
    .parameters(Map.of(
        "type", "object",
        "properties", Map.of(
            "question", Map.of("type", "string", "description", "The question to ask")),
        "required", List.of("question"),
        "additionalProperties", false))
    .endsTurn(true)   // calling this ends the autonomous loop
    .group("chat")    // only exposed when "chat" is in enabledToolGroups (or when group filtering is disabled)
    .build();
```

### Code Interpreter

Enable code execution for complex calculations:

```java
service.registerAgent(Agent.builder()
    .id("calculator")
    .name("Code Interpreter Agent")
    .model("gpt-4o")
    .instructions("Use code interpreter to solve math problems.")
    .codeInterpreter(true)  // Enable code interpreter
    .build());

AgentResult result = service.requestAgent("calculator", "Calculate the factorial of 20")
    .get(120, TimeUnit.SECONDS);

System.out.println(result.getContent());
// "The factorial of 20 is 2,432,902,008,176,640,000"
```

## Autonomous Agent Mode

### Overview

Autonomous mode enables agents to independently execute multi-step tasks using tools, without the caller manually managing the tool-calling loop. The agent decides which tools to call, processes results, and repeats until the turn ends.

This is ideal for complex workflows where the agent needs to:
- Search for data, analyze it, and produce a summary
- Make multiple API calls in sequence with decision-making between them
- Execute a plan with conditional branching based on tool results
- Run as a long-lived conversational or observer agent (see [Infinite / Observer Loops](#infinite--observer-loops))

### How It Works

1. **You register an agent** with `autonomous(true)` and define its tools via `functions()`
2. **You call `requestAgent()`** with a `ToolExecutor` that knows how to execute each tool
3. **The library manages the loop internally:**
   - Filters the tool list by `enabledToolGroups` (see [Tool Groups](#tool-groups)) before sending it to the LLM
   - Sends the user message to the LLM
   - If the LLM calls tools → executes them via your `ToolExecutor`, sends results back
   - If a called tool has `endsTurn=true` (or is the auto-injected `task_over`) → the loop ends after the tool result is stored
   - If the LLM responds with text only:
     - by default (`endTurnOnPlainReply=false`) → nudge and continue the loop
     - if `endTurnOnPlainReply=true` → return the text to the caller and stop (use for conversational agents)
4. **The loop terminates** when an `endsTurn` tool is called, when the agent returns plain text with `endTurnOnPlainReply=true`, or when `maxIterations` is reached

**`task_over` auto-injection.** If no function declares `endsTurn=true` AND `infiniteLoop` / `disableTaskOver` are both `false`, the library auto-injects a `task_over` function as a backwards-compatible end-of-turn mechanism. Its parameter schema is generated from `resultClass`, so the LLM returns structured data that maps directly to your Java class. If you declare your own `endsTurn=true` tool, `task_over` is NOT injected — you own termination.

**Context management.** Long autonomous loops can be kept under control with [Tool Output Trimming](#tool-output-trimming) (per-result cap) and [Context Compaction](#context-compaction) (strip old tool-result bodies and/or enforce a total token budget).

### Basic Usage

```java
// 1. Define tools
FunctionConfig searchFunc = FunctionConfig.builder()
    .name("search_database")
    .description("Search a database for information")
    .parameters(Map.of(
        "type", "object",
        "properties", Map.of(
            "query", Map.of("type", "string", "description", "Search query")),
        "required", List.of("query"),
        "additionalProperties", false))
    .build();

FunctionConfig analyzeFunc = FunctionConfig.builder()
    .name("analyze_data")
    .description("Analyze data and return insights")
    .parameters(Map.of(
        "type", "object",
        "properties", Map.of(
            "data", Map.of("type", "string", "description", "Data to analyze")),
        "required", List.of("data"),
        "additionalProperties", false))
    .build();

// 2. Register autonomous agent
service.registerAgent(Agent.builder()
    .id("researcher")
    .name("Research Agent")
    .model("gpt-5.1-chat")  // or "claude-sonnet-4-5"
    .instructions("You are a research assistant. Search for data, analyze it, "
        + "then call task_over with a structured summary.")
    .resultClass("ResearchResult")
    .autonomous(true)
    .maxIterations(10)
    .functions(List.of(searchFunc, analyzeFunc))
    .build());

// 3. Provide a ToolExecutor and call
AgentResult result = service.requestAgent("researcher",
    "Research the current state of renewable energy.",
    call -> {
        switch (call.getName()) {
            case "search_database":
                return myDatabase.search(call.getArgumentsAsMap().get("query").toString());
            case "analyze_data":
                return myAnalyzer.analyze(call.getArgumentsAsMap().get("data").toString());
            default:
                return "Unknown tool: " + call.getName();
        }
    }
).get(180, TimeUnit.SECONDS);

// result is a ResearchResult instance
ResearchResult research = (ResearchResult) result;
System.out.println(research.getTopic());
System.out.println(research.getFindings());
```

### ToolExecutor Interface

`ToolExecutor` is a functional interface that you implement to execute tool calls:

```java
@FunctionalInterface
public interface ToolExecutor {
    String execute(FunctionCall functionCall) throws Exception;
}
```

- **Input**: A `FunctionCall` with `getName()`, `getArguments()` (raw JSON string), `getArgumentsAsMap()`, and `getArgumentsAs(Class<T>)` for typed deserialization
- **Output**: A `String` result that gets sent back to the LLM
- **Errors**: If your executor throws an exception, the error message is sent to the LLM as the tool result (e.g., `"Error executing search_database: Connection timeout"`), and the loop continues - the agent can decide to retry or proceed differently

```java
// Using a lambda
ToolExecutor executor = call -> {
    if ("get_weather".equals(call.getName())) {
        WeatherParams params = call.getArgumentsAs(WeatherParams.class);
        return weatherService.getWeather(params.getLocation());
    }
    return "Unknown tool";
};

// Using a method reference
ToolExecutor executor = this::handleToolCall;
```

### Structured Results with resultClass

The `resultClass` field determines the schema of the `task_over` function and the return type. Your class must implement `AgentResult`:

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResearchResult implements AgentResult {

    @JsonProperty("topic")
    private String topic;

    @JsonProperty("findings")
    private List<String> findings;

    @JsonProperty("conclusion")
    private String conclusion;

    @Override
    public String getContent() {
        return "Topic: " + topic + ", Findings: " + findings + ", Conclusion: " + conclusion;
    }
}
```

The library automatically:
1. Generates a JSON schema from this class
2. Injects it as the `task_over` function's parameter schema
3. Deserializes the LLM's `task_over` call arguments into your class

If no `resultClass` is configured, `task_over` accepts an empty object and returns a `DefaultResult` with the raw JSON arguments.

### Conversation Management

**Without conversationId** (internal cleanup):
```java
// Library creates and deletes the conversation internally
AgentResult result = service.requestAgent("researcher", "Research AI trends",
    this::executeToolCall
).get(180, TimeUnit.SECONDS);
// Conversation is automatically cleaned up after completion
```

**With conversationId** (external management):
```java
// You manage the conversation lifecycle
String convId = service.createConversation();

try {
    // First task
    AgentResult result1 = service.requestAgent("researcher",
        "Research solar energy.", convId, this::executeToolCall
    ).get(180, TimeUnit.SECONDS);

    // Second task - agent remembers the first conversation
    AgentResult result2 = service.requestAgent("researcher",
        "Now compare with wind energy based on your previous research.",
        convId, this::executeToolCall
    ).get(180, TimeUnit.SECONDS);
} finally {
    service.deleteConversation(convId);
}
```

When using an external `conversationId`, the conversation history accumulates across calls, giving the agent full context from previous interactions.

### Tool Output Trimming

For agents that call tools returning large outputs (e.g., database queries, API responses), you can limit the token size of tool results stored in conversation history:

```java
service.registerAgent(Agent.builder()
    .id("researcher")
    .model("gpt-5.1-chat")
    .autonomous(true)
    .maxToolTokenOutput(200)  // ~800 characters max per tool output
    .functions(List.of(searchFunc))
    .build());
```

- Uses an estimate of ~4 characters per token
- Outputs exceeding the limit are truncated with a `[trimmed]` notice
- `null` (default) = no trimming
- Only applies to autonomous mode tool results

This prevents conversation history from growing too large when tools return verbose data, keeping API costs and context window usage under control.

### Agent Reflection (Thinking Aloud)

During the autonomous loop, the agent may respond with text only (no tool calls). This happens when the agent wants to "think aloud" - reasoning about what to do next before calling a tool.

The library handles this automatically:
1. Stores the agent's text in conversation history
2. Sends a nudge message: *"Continue with the task. When you are done, call the 'task_over' function with the final result."*
3. Continues the loop

You can encourage this behavior in your instructions:

```java
.instructions("Before each tool call, think step by step about what "
    + "information you still need and why. After each tool result, "
    + "reflect on what you learned before deciding your next action.")
```

Claude models tend to think aloud naturally. GPT models are more direct by default but will reflect if instructed to.

### Ending the Turn: `endsTurn` and `endTurnOnPlainReply`

In v1.18+, you have two complementary knobs for deciding when an autonomous turn ends.

**`FunctionConfig.endsTurn`** (boolean, default `false`) — when `true`, calling this tool ends the autonomous loop once the tool result is stored in the conversation. This replaces the legacy hardcoded `task_over` with any custom end-of-turn tool.

```java
FunctionConfig askUser = FunctionConfig.builder()
    .name("ask_user")
    .description("Ask the user a clarifying question and pause")
    .parameters(Map.of(
        "type", "object",
        "properties", Map.of(
            "question", Map.of("type", "string", "description", "The question")),
        "required", List.of("question"),
        "additionalProperties", false))
    .endsTurn(true)
    .build();
```

If NO function on the agent declares `endsTurn=true` (and `infiniteLoop` is off), the library auto-injects `task_over` so pre-v1.18 agents keep working unchanged.

**`Agent.endTurnOnPlainReply`** (boolean, default `false`) — controls what happens when the LLM returns text without any tool calls.

- `false` (default, legacy): nudge the agent (*"Continue with the task. When done, call `task_over`…"*) and run another iteration.
- `true`: stop the loop and return the plain-text reply to the caller. The natural-language reply IS the end of the turn.

Use `endTurnOnPlainReply=true` for **conversational agents** — the agent loops over its tools and then stops cleanly when it is ready to speak to the user.

```java
service.registerAgent(Agent.builder()
    .id("chat-agent")
    .model("claude-sonnet-4-5")
    .instructions("You are a helpful assistant. Use tools to look things up, "
        + "then answer the user in natural language.")
    .autonomous(true)
    .endTurnOnPlainReply(true)   // plain text → end turn
    .maxIterations(60)
    .functions(List.of(searchFunc, askUser))   // askUser has endsTurn=true
    .build());
```

Combine both:
- `endsTurn` tools handle explicit end-of-turn actions (ask_user, handoff, task_complete)
- `endTurnOnPlainReply=true` handles the "I'm done reasoning, here is my reply" case

### Tool Groups

Tool groups enable dynamic toolbox management. Instead of exposing every tool to the LLM at every turn (wasting tokens), you can tag tools with groups and selectively enable subsets.

**How it works:**
1. Tag `FunctionConfig`s with `.group("group_name")`. Functions with `group=null`, empty, or `"default"` are always-on.
2. Set `Agent.builder().enabledToolGroups(Set.of("group1", "group2"))` to gate the rest.
3. Before each LLM call, the runner filters the function list: only always-on tools and tools whose group is in `enabledToolGroups` are sent to the LLM. Hidden tools stay registered — your `ToolExecutor` can still execute them if the LLM somehow calls them via another path.
4. When `enabledToolGroups` is `null` (default), the group field is ignored and all functions are exposed (legacy behavior).

```java
FunctionConfig think = FunctionConfig.builder().name("think").description("…")
    .parameters(thinkSchema).build();                          // always-on (group=null)

FunctionConfig writeFile = FunctionConfig.builder().name("write_file").description("…")
    .parameters(writeSchema).group("fs_write").build();         // gated

FunctionConfig runShell = FunctionConfig.builder().name("run_shell").description("…")
    .parameters(shellSchema).group("shell").build();            // gated

Agent.builder()
    .id("coder")
    .autonomous(true)
    .functions(List.of(think, writeFile, runShell))
    .enabledToolGroups(Set.of("fs_write"))   // only "think" and "write_file" are exposed this turn
    // ...
    .build();
```

**Common pattern:** start with a minimal set of groups, and expose a meta-tool like `enable_tool_group` so the agent itself can request more capabilities as the task progresses. Rebuild / re-register the agent with an updated `enabledToolGroups` between turns.

### Context Compaction

Long autonomous loops accumulate bulky tool results. Two complementary controls keep the conversation lean:

**`compactToolResultsAfterIteration`** (Integer, default `null` = disabled) — starting at this iteration number, the library strips the content of old tool-result messages from the conversation (keeping the `[Tool call: name(args)]` summary so the agent still sees what it already did). Bulky response bodies go away.

**`compactKeepLastNIterations`** (Integer, default `1`) — how many most recent iterations are left untouched by compaction. All tool results from those iterations — including parallel tool calls — are preserved.

**`maxConversationTokens`** (Integer, default `null` = disabled) — before each iteration, if the estimated conversation size is over this token budget, the library drops the oldest whole messages until it fits. Runs AFTER compaction so the cheap compaction step gets first shot.

```java
Agent.builder()
    .id("long-running-agent")
    .autonomous(true)
    .maxIterations(60)
    .compactToolResultsAfterIteration(30)   // start compacting at iteration 30
    .compactKeepLastNIterations(5)          // always keep the last 5 iterations intact
    .maxConversationTokens(40_000)          // hard ceiling on total context
    // ...
    .build();
```

For immortal / observer agents whose rate must be bounded, see also `minIterationIntervalMs` (enforces a minimum start-to-start interval between iterations — the loop sleeps on its own worker thread without holding permits).

### Infinite / Observer Loops

Some agents — e.g. NPCs in a simulation, observer agents fed by `AgentService.insertMessage`, background monitors — should never end on their own. Two fields enable this:

**`infiniteLoop`** (boolean, default `false`) — when `true`, the library does NOT auto-inject `task_over`, and any hallucinated `task_over` call from the LLM is rejected with an error tool result. The loop ends only on external cancellation, error, or when `maxIterations` is reached.

**`maxIterationsUnlimited`** (boolean, default `false`) — when `true`, the `maxIterations` safety check is skipped. Combine with `infiniteLoop=true` for a truly immortal loop.

The older `disableTaskOver` field is a deprecated alias for `infiniteLoop`; both are honored for backwards compatibility.

```java
Agent.builder()
    .id("observer-agent")
    .autonomous(true)
    .infiniteLoop(true)                 // no task_over injection, no self-termination
    .maxIterationsUnlimited(true)       // no iteration ceiling either
    .minIterationIntervalMs(2_000)      // but throttle to ≤ 1 iteration / 2s
    .maxConversationTokens(40_000)      // and keep context bounded
    // ...
    .build();
```

### JSON Configuration

Autonomous agents can also be defined in JSON files:

```json
{
  "id": "researcher",
  "name": "Research Agent",
  "model": "gpt-5.1-chat",
  "instructions": "You are a research assistant...",
  "resultClass": "ResearchResult",
  "autonomous": true,
  "maxIterations": 15,
  "maxToolTokenOutput": 200,
  "functions": [
    {
      "name": "search_database",
      "description": "Search for information",
      "parameters": {
        "type": "object",
        "properties": {
          "query": { "type": "string", "description": "Search query" }
        },
        "required": ["query"],
        "additionalProperties": false
      }
    }
  ]
}
```

### Full Example

A complete example with two tools and structured output:

```java
// Result class
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AnalysisResult implements AgentResult {
    @JsonProperty("summary") private String summary;
    @JsonProperty("key_points") private List<String> keyPoints;
    @JsonProperty("confidence") private double confidence;

    @Override
    public String getContent() {
        return summary;
    }
}

// Setup
AgentServiceConfig config = AgentServiceConfig.builder()
    .instancesJson(System.getenv("LLM_INSTANCES"))
    .agentResultClassPackage("com.myapp.model")
    .build();
AgentService service = new AgentService(config);

// Register agent
service.registerAgent(Agent.builder()
    .id("analyst")
    .name("Data Analyst")
    .model("claude-sonnet-4-5")
    .instructions(
        "You are a data analyst. To complete an analysis:\n"
        + "1. Use fetch_data to retrieve relevant datasets\n"
        + "2. Use run_query to execute analytical queries\n"
        + "3. When done, call task_over with your analysis")
    .resultClass("AnalysisResult")
    .autonomous(true)
    .maxIterations(20)
    .maxToolTokenOutput(500)
    .functions(List.of(fetchDataFunc, runQueryFunc))
    .build());

// Execute
String convId = service.createConversation();
try {
    AnalysisResult result = (AnalysisResult) service.requestAgent(
        "analyst",
        "Analyze customer churn patterns for Q4 2025",
        convId,
        call -> {
            if ("fetch_data".equals(call.getName())) {
                return dataService.fetch(call.getArgumentsAs(FetchParams.class));
            } else if ("run_query".equals(call.getName())) {
                return queryEngine.execute(call.getArgumentsAs(QueryParams.class));
            }
            return "Unknown tool: " + call.getName();
        }
    ).get(300, TimeUnit.SECONDS);

    System.out.println("Summary: " + result.getSummary());
    System.out.println("Key points: " + result.getKeyPoints());
    System.out.println("Confidence: " + result.getConfidence());
} finally {
    service.deleteConversation(convId);
}
```

## Embeddings

Generate text embeddings for semantic search:

```java
// Single text
float[] embedding = service.requestEmbedding("Hello world", "text-embedding-3-small")
    .get(30, TimeUnit.SECONDS);

// Default model
float[] embedding = service.requestEmbedding("Hello world")
    .get(30, TimeUnit.SECONDS);

System.out.println("Dimensions: " + embedding.length); // 1536

// Batch embeddings
List<String> texts = List.of("Hello", "World", "Test");
List<float[]> embeddings = service.requestEmbeddings(texts, "text-embedding-3-small")
    .get(60, TimeUnit.SECONDS);
```

## Image Generation

Generate images using DALL-E:

```java
import io.github.yannfavinleveque.agentic.domain.image.Size;
import io.github.yannfavinleveque.agentic.domain.image.ImageRequest.Quality;

// Simple (returns base64)
String imageBase64 = service.requestImage("A cat in space")
    .get(120, TimeUnit.SECONDS);

// With options
String imageBase64 = service.requestImage(
    "A beautiful sunset over mountains",
    "dall-e-3",
    Size.X1024,
    Quality.HD
).get(120, TimeUnit.SECONDS);

// Edit an existing image
String edited = service.requestImageEdit(existingImageBase64, "Add sunglasses to the cat")
    .get(120, TimeUnit.SECONDS);
```

## Agent JSON Schema

Agents can be defined in JSON files or registered programmatically.

**JSON file** (`src/main/resources/agents/my-agent.json`):

```json
{
  "id": "my-agent",
  "name": "My Assistant",
  "model": "gpt-4o",
  "instructions": "You are a helpful assistant.",
  "temperature": 0.7,
  "webSearch": false,
  "codeInterpreter": false,
  "functions": []
}
```

**Schema:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | Yes | Unique agent identifier |
| `name` | string | Yes | Human-readable agent name |
| `model` | string | Yes | Model to use (e.g., `gpt-4o`, `claude-sonnet-4-5`) |
| `instructions` | string | No | System prompt / instructions |
| `temperature` | number | No | Randomness 0.0-2.0 (default: model default) |
| `webSearch` | boolean | No | Enable web search tool (default: `false`) |
| `codeInterpreter` | boolean | No | Enable code interpreter (default: `false`) |
| `functions` | array | No | Custom function definitions |
| `responseTimeout` | number | No | Max response time in ms (default: `120000`) |
| `maxTokens` | number | No | Maximum tokens in response |
| `resultClass` | string | No | Class name for structured outputs |
| `autonomous` | boolean | No | Enable autonomous tool loop mode (default: `false`) |
| `maxIterations` | number | No | Max loop iterations for autonomous mode (default: `25`) |
| `maxIterationsUnlimited` | boolean | No | Skip the `maxIterations` ceiling (default: `false`). Pairs with `infiniteLoop=true` for immortal loops. |
| `maxToolTokenOutput` | number | No | Max tokens per tool output in autonomous mode (null = no limit) |
| `endTurnOnPlainReply` | boolean | No | If `true`, a plain-text reply (no tool calls) ends the turn. Use for conversational agents (default: `false`). |
| `enabledToolGroups` | array | No | Set of tool-group names currently enabled. Null = all functions exposed (legacy). See [Tool Groups](#tool-groups). |
| `compactToolResultsAfterIteration` | number | No | Strip old tool-result bodies starting at this iteration (default: `null` = disabled). |
| `compactKeepLastNIterations` | number | No | How many recent iterations are never compacted (default: `1`). |
| `maxConversationTokens` | number | No | Hard ceiling on total estimated conversation tokens before each iteration (default: `null` = disabled). |
| `minIterationIntervalMs` | number | No | Minimum start-to-start interval between iterations, in ms. For rate-bounded immortal loops (default: `null` = disabled). |
| `infiniteLoop` | boolean | No | No `task_over` auto-injection; loop ends only on cancel/error/`maxIterations` (default: `false`). |
| `disableTaskOver` | boolean | No | Deprecated alias for `infiniteLoop`. |
| `reasoningEffort` | string | No | Reasoning effort: `low` / `medium` / `high` / `enabled` / `none`. |

**Function definition:**

```json
{
  "functions": [
    {
      "name": "get_weather",
      "description": "Get weather for a location",
      "parameters": {
        "type": "object",
        "properties": {
          "location": {
            "type": "string",
            "description": "City name"
          }
        },
        "required": ["location"]
      },
      "endsTurn": false,
      "group": "weather",
      "executorClass": "com.example.tools.WeatherExecutor"
    }
  ]
}
```

**Function fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Unique function name within the agent |
| `description` | string | Yes | Sent to the LLM to help it decide when to call the tool |
| `parameters` | object | No | Inline JSON schema for arguments |
| `parameterClass` | string | No | Fully qualified (or simple) class name used to generate the parameter schema |
| `endsTurn` | boolean | No | If `true`, calling this tool ends the autonomous turn (default: `false`). See [Ending the Turn](#ending-the-turn-endsturn-and-endturnonplainreply). |
| `group` | string | No | Tool-group tag; filtered by `Agent.enabledToolGroups`. See [Tool Groups](#tool-groups). |
| `executorClass` | string | No | FQCN (or simple name) of a `ToolExecutor` implementation used when no lambda executor is provided. |
| `methodClass` | string | No | Legacy: FQCN of a Java class implementing the function |
| `methodName` | string | No | Legacy: method to invoke on `methodClass` |

## Environment Variables

| Variable | Description |
|----------|-------------|
| `LLM_INSTANCES` | JSON array of instance configurations (required) |
| `ENABLED_PROVIDERS` | Comma-separated list of providers to enable (optional) |

### Provider Filtering

Use `ENABLED_PROVIDERS` to limit which providers are loaded:

```bash
# Only use OpenAI direct
export ENABLED_PROVIDERS=openai

# Only use Azure providers
export ENABLED_PROVIDERS=azure-openai,azure-anthropic

# Only use Anthropic direct
export ENABLED_PROVIDERS=anthropic

# Use all providers (default)
unset ENABLED_PROVIDERS
```

## Multi-Provider Support

AgentService supports eleven built-in providers plus a JSON-driven CUSTOM provider, all with automatic routing:

| Provider | Description | Models |
|----------|-------------|--------|
| `openai` | OpenAI API direct | gpt-5.5, gpt-5.4, gpt-5.2, gpt-5.1, gpt-5, gpt-4.1, gpt-4o, o1/o3/o4 series, dall-e-3, text-embedding-3-* |
| `azure-openai` | Azure OpenAI | OpenAI models deployed on Azure |
| `anthropic` | Anthropic API direct | claude-opus-4-7, claude-sonnet-4-7, claude-haiku-4-7, claude-*-4-6, claude-*-4-5, claude-3-* |
| `azure-anthropic` | Azure AI (Claude) | Same Claude models, deployed on Azure AI Foundry |
| `mistral` | Mistral La Plateforme | mistral-large-latest, pixtral-large-latest, codestral-latest, magistral-medium-latest, ministral-* |
| `azure-mistral` | Mistral via Azure AI Foundry | mistral-large-* (deployed) |
| `grok` | xAI Grok (api.x.ai) | grok-4, grok-4-fast, grok-3, grok-3-mini, grok-2-vision-1212, grok-code-fast-1 |
| `azure-grok` | Grok via Azure AI Foundry | grok-3, grok-3-mini (deployed) |
| `deepseek` | DeepSeek (api.deepseek.com) | deepseek-chat, deepseek-reasoner |
| `gemini` | Google Gemini (OpenAI-compat shim) | gemini-2.5-pro, gemini-2.5-flash, gemini-2.0-flash, gemini-2.0-flash-thinking, text-embedding-004 |
| `custom` | User-defined provider via JSON spec | Any (Together, Groq, OpenRouter, Ollama, ...) |

The service automatically:
- Routes requests to instances that have the requested model
- Load-balances across multiple instances
- Handles rate limiting per instance
- Retries on transient failures

Routing precedence inside the request executor: **custom > anthropic > mistral > grok > deepseek > gemini > openai**. An instance whose `provider` is `custom` short-circuits everything (no model-name sniffing); for the other built-in providers, the model name (`claude-*`, `mistral-*`, `pixtral-*`, `codestral-*`, `magistral-*`, `ministral-*`, `grok-*`, `deepseek-*`, `gemini-*`) selects the right adapter.

### Mistral support

Mistral models talk OpenAI-compatible chat/completions, so configuration mirrors the OpenAI block — only the base URL, key and model list change:

```json
[
  {
    "id": "mistral-main",
    "url": "https://api.mistral.ai",
    "key": "${MISTRAL_API_KEY}",
    "models": "mistral-large-latest,pixtral-large-latest,codestral-latest,magistral-medium-latest",
    "provider": "mistral"
  },
  {
    "id": "azure-mistral-eastus",
    "url": "https://my-foundry.services.ai.azure.com",
    "key": "${AZURE_MISTRAL_KEY}",
    "models": "mistral-large-2411",
    "provider": "azure-mistral",
    "apiVersion": "2024-05-01-preview"
  }
]
```

Notes:
- Mistral does **not** expose OpenAI's `/v1/responses`. The library always routes Mistral requests to `/v1/chat/completions` (or `/models/chat/completions` on Azure Mistral).
- The OpenAI-introduced `developer` role is automatically rewritten to `system` for Mistral.
- `magistral-*` reasoning models receive `prompt_mode: "reasoning"` automatically.
- Native `web_search` and `code_interpreter` tools are not available — set `webSearch=false` / `codeInterpreter=false` on agents pinned to Mistral instances. Use the `instances` allow-list on the agent (see `AgentDefinition.instances`) to keep tool-heavy agents on OpenAI/Claude only.

### xAI Grok support

Grok exposes an OpenAI-compatible `/v1/chat/completions` endpoint at `api.x.ai`. Use `provider: "grok"` (or `provider: "azure-grok"` for the Azure AI Foundry deployment, which serves at `/models/chat/completions` with an `api-version` query parameter and `api-key` header):

```json
[
  {
    "id": "xai-grok-main",
    "url": "https://api.x.ai",
    "key": "${XAI_API_KEY}",
    "models": "grok-4,grok-4-fast,grok-3,grok-3-mini,grok-2-vision-1212,grok-code-fast-1",
    "provider": "grok"
  },
  {
    "id": "azure-grok-eastus",
    "url": "https://my-foundry.services.ai.azure.com",
    "key": "${AZURE_GROK_KEY}",
    "models": "grok-3,grok-3-mini",
    "provider": "azure-grok",
    "apiVersion": "2024-05-01-preview"
  }
]
```

Notes:
- `reasoning_effort` is only emitted on reasoning-capable models (`grok-4*`, `grok-3-mini`). On other Grok models the field is silently stripped — xAI returns HTTP 400 if you send it on `grok-3` or `grok-2-*`.
- xAI's proprietary **Live Search** (`search_parameters`) is not yet exposed — use `Provider.CUSTOM` if you need it today.
- xAI did not officially expose embeddings as of 2026-01; only `CHAT_COMPLETIONS` is supported.
- Grok also speaks Anthropic Messages on `/v1/messages`, but we standardize on the OpenAI shape to keep one code path.

### DeepSeek support

DeepSeek exposes an OpenAI-compatible `/v1/chat/completions` endpoint at `api.deepseek.com`. Use `provider: "deepseek"`:

```json
[
  {
    "id": "deepseek-main",
    "url": "https://api.deepseek.com",
    "key": "${DEEPSEEK_API_KEY}",
    "models": "deepseek-chat,deepseek-reasoner",
    "provider": "deepseek"
  }
]
```

Notes:
- `deepseek-reasoner` returns a non-standard `reasoning_content` field on the assistant message (visible chain-of-thought). The library extracts it and **prepends it to the parsed text** wrapped in `[REASONING]\n...\n[/REASONING]\n\n` markers, so the chain-of-thought is preserved without losing the final content. Callers who only want the final answer can split on the closing tag.
- `reasoning_effort` is **not** sent — DeepSeek picks reasoning implicitly when you call `deepseek-reasoner`.
- DeepSeek's automatic context caching surfaces in `usage.prompt_cache_hit_tokens` (visible in the raw JSON; not yet exposed on `TokenUsage`).
- Only `CHAT_COMPLETIONS` is supported (no native embeddings endpoint via this adapter).

### Google Gemini support (OpenAI-compat shim)

Gemini is wired through Google's OpenAI-compatibility shim at `generativelanguage.googleapis.com/v1beta/openai/chat/completions`. Use `provider: "gemini"`:

```json
[
  {
    "id": "gemini-main",
    "url": "https://generativelanguage.googleapis.com",
    "key": "${GEMINI_API_KEY}",
    "models": "gemini-2.5-pro,gemini-2.5-flash,gemini-2.0-flash,text-embedding-004",
    "provider": "gemini"
  }
]
```

Why the shim and not the native Gemini API?
- The native API uses a proprietary shape (`contents`/`parts`, no system role, OAuth for Vertex), which would require a dedicated message-format converter.
- The shim accepts plain OpenAI Chat Completions payloads with `Authorization: Bearer <API_KEY>` and is documented as production-ready by Google. This keeps the implementation aligned with Mistral / Grok / DeepSeek paths.

Limitations of the shim (acknowledged trade-offs — pass through `Provider.CUSTOM` for any of these):
- No access to `thinkingConfig.thinkingBudget` (Gemini 2.5 thinking budget is implicit; only `reasoning_effort` low/medium/high is passed through, mapped server-side).
- No access to native multimodal types beyond what OpenAI vision allows (no inline audio/video; only `image_url` base64/URL).
- `safetySettings` cannot be configured via the shim — Google defaults apply.
- Some Gemini-only features (grounded search via `google_search`) require the native API and are not exposed here.
- **Vertex AI** (OAuth2-authenticated, regional) is not supported by `Provider.GEMINI`. If you need Vertex, declare it as a `custom` provider with your OAuth bearer token mechanism, or open an issue.

### Custom Provider

When you need a provider that the library does not natively support (Grok / xAI, DeepSeek, Together AI, Groq, OpenRouter, Ollama, a private internal LLM gateway...), declare it as a `custom` instance and describe its wire format in JSON.

#### When to use it
- The provider speaks one of: **OpenAI Chat Completions**, OpenAI Responses, or Anthropic Messages.
- You want to swap providers without rebuilding the library.
- You want strict declared-capability checking (the library will refuse — or warn, or silently strip — agent features the provider has not declared).

#### JSON schema of a custom block

```json
{
  "id": "grok-main",
  "url": "https://api.x.ai",
  "key": "${XAI_API_KEY}",
  "models": "grok-4,grok-4-fast",
  "provider": "custom",
  "custom": {
    "apiFormat": "openai-chat",
    "auth": { "header": "Authorization", "format": "Bearer {key}" },
    "endpoints": {
      "chat_completions": "/v1/chat/completions"
    },
    "queryParams": {},
    "extraHeaders": {},
    "features": {
      "vision": true,
      "function_calling": true,
      "structured_output": true,
      "web_search": false,
      "code_interpreter": false,
      "responses_api": false,
      "reasoning": true,
      "streaming": false,
      "embeddings": false,
      "image_generation": false
    },
    "onUnsupportedFeature": "throw"
  }
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `apiFormat` | yes | `openai-chat` (implemented), `openai-responses` (deferred to v1.22), `anthropic-messages` (deferred to v1.22) |
| `auth.header` | yes | Header name, e.g. `Authorization`, `x-api-key`, `api-key` |
| `auth.format` | no | Value template; `{key}` is substituted with `InstanceConfig.key`. If null, the key is sent verbatim |
| `endpoints.<name>` | yes (≥1) | Logical endpoint → URL path. Recognized: `chat_completions`, `responses`, `embeddings`, `images_generations` |
| `queryParams` | no | Appended verbatim to every request URL (e.g. `api-version`) |
| `extraHeaders` | no | Sent on every request (e.g. `OpenAI-Organization`, `User-Agent`) |
| `features.<name>` | no | Capability flags. Keys are case-insensitive and accept either `snake_case` or `camelCase` |
| `onUnsupportedFeature` | no | `throw` (default), `warn`, `ignore` — see "lenient modes" below |

#### Supported `apiFormat` values (v1.21.0)

| Value | Status | Behavior |
|-------|--------|----------|
| `openai-chat` | **Implemented** | Builds OpenAI-compat chat/completions wire format. Covers Mistral, Grok, DeepSeek, Together, Groq, Ollama, OpenRouter, and any other OpenAI-compat endpoint. |
| `openai-responses` | Deferred to v1.22 | Throws `UnsupportedOperationException` on first request. Workaround: use `openai-chat` if the provider also exposes chat/completions (most do). |
| `anthropic-messages` | Deferred to v1.22 | Throws `UnsupportedOperationException` on first request. Workaround: use `provider: "anthropic"` or `provider: "azure-anthropic"` for Claude — they reuse the dedicated `ClaudeAdapter`. |

#### Recognized `Feature` flags

`vision`, `function_calling`, `structured_output`, `web_search`, `code_interpreter`, `responses_api`, `reasoning`, `streaming`, `embeddings`, `image_generation`. Unknown keys in JSON are silently ignored (forward-compat).

#### Lenient modes (`onUnsupportedFeature`)

| Mode | Behavior |
|------|----------|
| `throw` (default) | If an agent declares a feature (e.g. `webSearch=true`) the provider has not flagged supported, the library throws `UnsupportedFeatureException` (a subclass of `AgentException` with code `UNSUPPORTED_FEATURE`) **before any HTTP call is made**. The exception lists the requested feature and the set of supported ones. |
| `warn` | Logs a SLF4J warning naming the instance, the unsupported feature, and the supported set, **strips the feature from the outgoing HTTP body**, then sends the request. The provider sees a clean request without the unsupported field, so it does not 4xx on it. Today the three features that are actually stripped at body-build time are `function_calling` (omits the `tools` array), `structured_output` (omits `response_format`) and `reasoning` (omits `reasoning_effort`); other capabilities (`web_search`, `code_interpreter`, `responses_api`, `streaming`, `embeddings`, `image_generation`) are not currently injected into the `openai-chat` body, so there is nothing to strip. |
| `ignore` | Same wire behavior as `warn` (feature stripped from the body, request sent), but no log line. Use sparingly — debugging "why does my Grok response not include a function call?" is harder when the warning is gone. |

Use `throw` in dev and CI; switch to `warn` in production when you want graceful degradation for providers whose feature matrix is heterogeneous.

#### Example: Grok via xAI (`openai-chat` direct)

```json
{
  "id": "grok",
  "url": "https://api.x.ai",
  "key": "${XAI_API_KEY}",
  "models": "grok-4,grok-4-fast,grok-code-fast",
  "provider": "custom",
  "custom": {
    "apiFormat": "openai-chat",
    "auth": { "header": "Authorization", "format": "Bearer {key}" },
    "endpoints": { "chat_completions": "/v1/chat/completions" },
    "features": {
      "vision": true,
      "function_calling": true,
      "structured_output": true,
      "reasoning": true,
      "web_search": false,
      "code_interpreter": false,
      "embeddings": false,
      "image_generation": false
    },
    "onUnsupportedFeature": "throw"
  }
}
```

#### Example: local Ollama (lenient mode)

```json
{
  "id": "ollama-local",
  "url": "http://localhost:11434",
  "key": "ignored",
  "models": "llama3.1:70b,qwen2.5-coder:32b",
  "provider": "custom",
  "custom": {
    "apiFormat": "openai-chat",
    "auth": { "header": "Authorization", "format": "Bearer {key}" },
    "endpoints": { "chat_completions": "/v1/chat/completions" },
    "features": {
      "function_calling": true,
      "structured_output": false,
      "vision": false,
      "web_search": false,
      "code_interpreter": false
    },
    "onUnsupportedFeature": "warn"
  }
}
```

In this Ollama example, an agent with `resultClass="MyResult"` triggers a warning at request time (`structured_output=false`) instead of throwing, so a single agent definition can be reused across providers of varying capability.

#### Optional `modelPricing` (since 1.22.1)

You can declare per-model pricing for cost tracking. Without it, `TokenUsage.estimatedCostUsd`
stays `null` for unknown models — no error, the request still succeeds, you just don't get cost.

```json
"custom": {
  "apiFormat": "openai-chat",
  "auth": { "header": "Authorization", "format": "Bearer {key}" },
  "endpoints": { "chat_completions": "/v1/chat/completions" },
  "modelPricing": {
    "my-private-llm-v2":      { "input": 1.50, "output": 5.00 },
    "my-private-llm-v2-mini": { "input": 0.20, "output": 0.80 }
  }
}
```

Pricing is in USD per 1M tokens. Lookup tries the library's static table first
(OpenAI / Anthropic / Mistral / Grok / DeepSeek / Gemini), then your `modelPricing`
(longest-prefix match), then gives up gracefully (`estimatedCostUsd=null`).

## API Reference

### AgentService Methods

| Method | Description |
|--------|-------------|
| `requestAgent(agentId, message)` | Simple agent request |
| `requestAgent(agentId, message, conversationId)` | Request with automatic history management |
| `requestAgent(agentId, message, history)` | Request with manual conversation history |
| `requestAgentVision(agentId, message, imageBase64)` | Vision request with single image |
| `requestModel(model, message)` | Direct model request (no agent) |
| `requestModel(model, message, options)` | Direct model with options |
| `createConversation()` | Create new conversation (returns ID) |
| `deleteConversation(conversationId)` | Delete conversation |
| `getConversationMessageCount(conversationId)` | Get message count |
| `requestAgent(agentId, message, toolExecutor)` | Autonomous agent request (internal conversation) |
| `requestAgent(agentId, message, conversationId, toolExecutor)` | Autonomous agent request with external conversation |
| `registerAgent(agent)` | Register agent programmatically |
| `requestEmbedding(text)` | Generate single embedding |
| `requestEmbeddings(texts)` | Generate batch embeddings |
| `requestImage(prompt)` | Generate image (base64) |
| `requestImageEdit(imageBase64, prompt)` | Edit existing image |

## Deprecated APIs

### `chatCompletion(...)` / `requestChatCompletion(...)` (since 1.22.0)

The `chatCompletion` family on `AgentService` targets the legacy OpenAI
`/v1/chat/completions` endpoint exclusively. It does not support:
- Anthropic / Mistral / custom provider routing
- Web search, code interpreter, reasoning effort
- The richer Responses API features
- Function calling beyond the OpenAI tools array

**Use `requestModel(...)` or `requestAgent(...)` instead** — they route per-provider
to the most modern endpoint available (Responses API for OpenAI/Azure-OpenAI,
Messages for Anthropic, Chat Completions stateless for Mistral/Grok/DeepSeek/custom),
and expose the full feature surface of each provider.

`chatCompletion(...)` will be **removed in 2.0.0**. Migrate now:

```java
// Before
agentService.chatCompletion("gpt-4o", messages, 0.7, MyResult.class).join();

// After
ModelRequestOptions opts = ModelRequestOptions.builder()
    .resultClass(MyResult.class)
    .temperature(0.7)
    .history(messages)
    .build();
agentService.requestModel("gpt-4o", lastUserMessage, opts).join();
```

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [simple-openai](https://github.com/sashirestela/simple-openai) by Sashir Estela - The foundation of this library
- [CleverClient](https://github.com/sashirestela/cleverclient) - HTTP client library

## Changelog

### v1.23.0
- **4 new native providers**: `grok` (xAI on `api.x.ai`), `azure-grok` (Grok on Azure AI Foundry), `deepseek` (`api.deepseek.com`), and `gemini` (Google via the OpenAI-compat shim at `generativelanguage.googleapis.com/v1beta/openai/...`). All four use the OpenAI Chat Completions stateless wire format, joining Mistral on the same code path.
- **Routing precedence in `UnifiedRequestService`** is now `custom > anthropic > mistral > grok > deepseek > gemini > openai`, applied symmetrically in all three executor sites (`executeRequestAgentWithImagesAfterPermit`, `executeRequestModelInternalAfterPermit`, `executeRequestAfterPermit`). `Provider.CUSTOM` short-circuits everything; otherwise model-name prefix matching dispatches per family.
- **Factorization**: introduced `executeChatCompletionsCompatRequest(agent, messages, instance, BodyBuilder, responseParser)` private helper. The 4 specific executors (`executeMistralRequest`, `executeGrokRequest`, `executeDeepSeekRequest`, `executeGeminiRequest`) are now 4-6 line wrappers — saves ~120 LOC vs duplication and keeps cross-provider behavior consistent.
- **DeepSeek `reasoning_content`**: `deepseek-reasoner` returns a non-standard `reasoning_content` field separate from `content`. The new `extractChatCompletionsContentWithReasoning` parser extracts it and prepends it wrapped in `[REASONING]\n...\n[/REASONING]\n\n` markers to the parsed text, so the chain-of-thought is surfaced rather than silently dropped. Behavior is byte-for-byte identical to the standard parser when `reasoning_content` is absent.
- **`GrokAdapter`, `DeepSeekAdapter`, `GeminiAdapter`** — static helpers (mirroring `MistralAdapter`): `is<Family>Model`, `isReasoningModel`, `buildRequestBody`. Reasoning-effort filtering: only `grok-3-mini`/`grok-4*` accept `reasoning_effort`; only `gemini-2.5-pro`/`gemini-2.5-flash`/`gemini-2.0-flash-thinking` accept it; DeepSeek never accepts it (reasoning is implicit on `deepseek-reasoner`).
- **Pricing**: full pricing entries for all new providers in `ModelPricing`, verified against official sources (April 2026). Includes `grok-4.20` (current xAI flagship), `deepseek-v4-flash`/`v4-pro`, and the unified DeepSeek pricing post-2025-09-29.
- **Decided OUT OF SCOPE**: Vertex AI for Gemini (OAuth2 GCP — use `Provider.CUSTOM` with a custom bearer if needed); native Gemini proprietary `contents`/`parts` shape; xAI Live Search (`search_parameters`).
- **Examples**: `examples/providers/grok-native.json`, `azure-grok-native.json`, `deepseek-native.json`, `gemini-native.json`.

### v1.22.1
- **Pricing refresh**: added GPT-5.5 family (gpt-5.5, gpt-5.5-pro), Claude 4.7 family (opus/sonnet/haiku-4-7), and Claude Haiku 4.6 to `ModelPricing`. Removed the lingering `// TODO: verify pricing` from the Mistral block. (In a follow-up commit on 1.23.0, all prices were re-verified against official sources and corrected where outdated.)
- **New**: per-instance `modelPricing` on `CustomProviderSpec`. JSON example: `"modelPricing": { "my-private-llm-v2": { "input": 1.50, "output": 5.00 } }`. Lookup order is library static table → custom map → `cost=null` (graceful no-op, no exception). Prefix-matching applies to both layers; longer-prefix wins.
- **New**: `ModelPricing.PriceEntry` POJO + `ModelPricing.calculate(String, Integer, Integer, Map<String, PriceEntry>)` overload that consults a fallback map when the static table has no match. Existing 3-arg overload is unchanged (binary compat preserved). `UnifiedRequestService` routes pricing through a private `calculatePricing(model, in, out, instance)` helper that auto-injects the spec's fallback map for `Provider.CUSTOM` instances.

### v1.22.0
- **Deprecation**: the `chatCompletion(...)` / `requestChatCompletion(...)` family on `AgentService` and `UnifiedRequestService` is now `@Deprecated` (8 methods total). Targets the legacy OpenAI `/v1/chat/completions` endpoint only — no Anthropic/Mistral/custom routing, no web search, no code interpreter, no reasoning, no Responses-API features. Replacement: `requestModel(model, userMessage, ModelRequestOptions)` or `requestAgent(agentId, userMessage)`. **Removal scheduled for 2.0.0.** All existing calls compile and run unchanged in 1.22.x.
- **Docs**: new "Deprecated APIs" section in the README with a Before/After migration snippet.

### v1.21.1
- **Fix (custom provider, lenient modes)**: `WARN` and `IGNORE` now actually strip the unsupported feature from the outgoing HTTP body. Pre-1.21.1 they only logged (or silenced) the mismatch but kept building the request with the agent's flags, so providers that did not support `tools`/`response_format`/`reasoning_effort` returned HTTP 400 even though the library promised "graceful fallback". `executeCustomOpenAIChatRequest` now consumes the sanitized `EnumSet<Feature>` returned by `FeatureValidator.validate(...)` and gates the inclusion of `tools` (`FUNCTION_CALLING`), `response_format` (`STRUCTURED_OUTPUT`) and `reasoning_effort` (`REASONING`) on it. The `THROW` path is unchanged (validator still throws before any HTTP call). Non-CUSTOM executors (`executeOpenAIRequest*`, `executeMistralRequest`, `executeClaudeRequest*`) are untouched — they have always-supported feature flows that do not go through `FeatureValidator`. Added 6 integration tests in `CustomProviderIntegrationTest` (`warnStripsFunctionCalling`, `ignoreStripsFunctionCalling`, `warnStripsResponseFormat`, `ignoreStripsResponseFormat`, `warnStripsReasoning`, `allFeaturesAllowedBodyFull`) that capture the on-the-wire body and assert the stripped/preserved keys; the existing `throwLenientMode` continues to assert pre-flight throwing.

### v1.21.0
- **New providers**: `mistral` (Mistral La Plateforme, OpenAI-compat chat/completions on `api.mistral.ai`) and `azure-mistral` (Mistral via Azure AI Foundry, served under `/models/chat/completions` with an `api-version` query param). Routing is automatic — `mistral-*`, `pixtral-*`, `codestral-*`, `magistral-*`, `ministral-*`, `open-mistral-*`, `open-mixtral-*` model names always go through the `MistralAdapter` chat/completions path. The `developer` role is rewritten to `system`; `magistral-*` reasoning models receive `prompt_mode: "reasoning"` automatically.
- **New CUSTOM provider**: `provider: "custom"` reads endpoints, auth header, query params, extra headers and feature flags from a `custom` block in the instance JSON. Supported `apiFormat` in this release: `openai-chat` (covers Grok, DeepSeek, Together, Groq, Ollama, OpenRouter, ...). `openai-responses` and `anthropic-messages` are deferred to v1.22 (they throw `UnsupportedOperationException` with a clear pointer in the message).
- **New**: `Feature` enum + `FeatureValidator` + `LenientMode` (THROW / WARN / IGNORE) — declared-capability checking for custom providers. `THROW` raises `UnsupportedFeatureException` (new subclass of `AgentException` with code `UNSUPPORTED_FEATURE`); `WARN` logs an SLF4J warning and proceeds; `IGNORE` silently proceeds.
- **New**: `HttpHelper.postRawCustom(fullUrl, headers, body, timeoutMs)` — overload that accepts a fully-built URL and an explicit header map, used by `Provider.CUSTOM` (for which `ProviderConfig.getPath/getHeaders/getQueryParams` deliberately throw `UnsupportedOperationException`).
- **New**: `Provider.MISTRAL`, `Provider.AZURE_MISTRAL`, `Provider.CUSTOM` enum values. Inserted before the deprecated `AZURE` constant so the ordinal of `AZURE` is preserved (no binary-breakage for code that switched on it).
- **New**: `InstanceConfig.custom` (Jackson-bound `CustomProviderSpec`) + `isMistral()`, `isAzureMistral()`, `isCustom()` helpers + extended `validate()` accepting `mistral` / `azure-mistral` / `custom`. Custom instances must declare a non-null `custom` block; `azure-mistral` instances must declare an `apiVersion`.
- **New**: `Instance.customSpec` field, propagated by `AgentService.parseInstances` and consumed by `UnifiedRequestService.executeCustomRequest`.
- **Routing precedence in `UnifiedRequestService`**: `custom > anthropic > mistral > openai`, applied symmetrically in `executeRequestAgentWithImagesAfterPermit`, `executeRequestModelInternalAfterPermit`, and `executeRequestAfterPermit`. A `Provider.CUSTOM` instance short-circuits everything (no model-name sniffing).
- **Docs**: README "Multi-Provider Support" section rewritten to cover all seven providers + the CUSTOM block schema, with concrete Grok and Ollama examples.

### v1.20.3
- **New**: Mustache-style prompt variables in agent `instructions`. Any `{{name}}` placeholder in the system prompt is substituted at request time from a `Map<String, Object> promptVars` passed alongside the user message. New overloads on `AgentService`: `requestAgent(agentId, userMessage, promptVars)`, `requestAgent(agentId, userMessage, history, promptVars)`, `requestAgent(agentId, userMessage, conversationId, promptVars)`, `requestAgent(agentId, userMessage, conversationId, imagesBase64, promptVars)`, `requestAgent(agentId, userMessage, history, imagesBase64, promptVars)`, `requestAgent(agentId, userMessage, conversationId, toolExecutor, promptVars)` (autonomous), and `requestAgentVision(agentId, userMessage, imageBase64, promptVars)`. Variable names match `[a-zA-Z_][a-zA-Z0-9_]*` (no scoping like `{{user.name}}`); whitespace inside braces is tolerated (`{{ foo }}`). Substitution scans only the `instructions` template — `userMessage` and `history` are passed through untouched, so users may type `{{ ... }}` literally in chat content.
- **New**: `io.github.yannfavinleveque.agentic.agent.util.PromptTemplate` utility — `extractVariables(String)` and `render(String, Map<String, Object>)`. One-pass, non-recursive (a value containing `{{x}}` is NOT re-rendered).
- **New**: `MissingPromptVariableException` (extends `AgentException`, code `MISSING_PROMPT_VARIABLE`) — thrown when the template references a variable absent from `promptVars` or whose mapped value is `null`. Carries `agentId`, `variableName`, and the snapshot of `providedKeys`.
- **Changed**: `Agent` now exposes Lombok `toBuilder()` so `AgentService` can produce a per-request copy with rendered instructions without mutating the registered agent shared by other concurrent calls.
- **Backward compat**: every pre-1.20.3 overload is preserved and forwards to the new code path with `promptVars=null`. Agents whose `instructions` contain no `{{...}}` placeholders short-circuit and return the original `Agent` reference (no clone, no allocation).

### v1.20.2
- **New**: `AgentDefinition.instances` — optional JSON array of allow-listed instance ids (e.g. `"instances": ["openai-main"]`). When non-empty, the `InstanceRouter` only routes the agent's requests to instances whose id is in the list AND that expose the requested model. When absent/empty, legacy round-robin over every compatible instance is preserved.
- **New**: `InstanceRouter.getNextInstanceForModel(String model, List<String> allowedIds)` overload — filters by allow-list before round-robin. Throws `NoInstanceAvailableException` with an explicit message when no allowed instance exposes the model. The existing `getNextInstanceForModel(String)` overload is unchanged and now delegates to the new one with a `null` allow-list.
- **Backward compat**: legacy `"instanceId": "<id>"` in agent JSON is auto-mapped to a singleton `instances: ["<id>"]` at parse time. If both `instances` and `instanceId` are present, `instances` wins.
- **Changed**: `Agent` now carries an `instances` field, populated from `AgentDefinition.getInstances()` by `AgentManager.loadAgentFromFile` and propagated to autonomous virtual children by `AutonomousAgentRunner.buildVirtualAgent`. Call sites in `UnifiedRequestService` that have an `Agent` (`requestAgent` with images, `requestModel`, `requestAgent` V2) now pass `agent.getInstances()` to the router. Embedding/image/chat-completion call sites that only have a model continue to use the unfiltered overload (no agent allow-list available).

### v1.20.1
- **Fix**: `AgentResourceExtractor` now honors the configured `agentJsonFolderPath` sub-path instead of always looking up `agents/` on the classpath. Configuring `agentJsonFolderPath("src/main/resources/prompts/agents")` (or `classpath:prompts/agents`) now correctly extracts JSON files from the matching classpath sub-directory. Backward compatible: `null`, empty, `"src/main/resources/agents"`, and `"classpath:agents"` all resolve to the previous `agents` sub-path. Filesystem paths are still returned as-is. Per-sub-path temp directories (`agentic-helper-<sub-path>`) avoid collisions between coexisting apps.
- **API change** (internal): `AgentResourceExtractor.extractAgentsFromClasspath()` is now `extractAgentsFromClasspath(String classpathSubPath)`. End users should not be impacted — the method is invoked only by `AgentServiceConfig.resolveAgentJsonFolderPath()`, which derives the sub-path automatically from `agentJsonFolderPath`.

### v1.20.0
- **New**: `AgentService.updateAgentFunctions(parentAgentId, newFunctions)` — replaces the function list of a registered agent AND propagates the change (with the same `enabledToolGroups` filter + `task_over` auto-injection) to every active autonomous virtual child. The new list ships to the LLM on the NEXT loop iteration; in-flight HTTP requests are unaffected. Thread-safe (per-`Agent` `synchronized`). Useful for live tool-catalog mutations (pin/unpin, hot-registered adapters) that must surface within the current turn instead of waiting for the next `sendUserMessage`.
- **New**: `Message.id` — opaque, conversation-local id auto-assigned by `ConversationManager.addMessage` (null for messages built outside a conversation, e.g. stateless requests). Enables dedup/replace patterns (inject a fresh snapshot, remove the stale one) without accumulating input tokens.
- **New**: `AgentService.removeMessage(conversationId, messageId)` + `ConversationManager.removeMessage(...)` — delete a previously inserted message by id. Pairs with the new `insertMessage` return value.
- **Changed return type** (source-compatible, **NOT binary-compatible**): `AgentService.insertMessage(convId, role, content)` and `ConversationManager.addMessage(convId, message)` now return the auto-generated `String` message id instead of `void`. Callers ignoring the return value recompile cleanly; anything linked against 1.19.0 bytecode will throw `NoSuchMethodError` at runtime until rebuilt against 1.20.
- Internal refactor: `AutonomousAgentRunner.buildVirtualAgent` now delegates the group-filter + `task_over` auto-injection logic to two package-private helpers (`applyGroupFilter`, `maybeInjectTaskOver`) so `updateAgentFunctions` can keep virtual children consistent with a fresh rebuild. No behaviour change for existing agents.

#### Migrating from 1.19.0 to 1.20.0

**JSON agent definitions** — no changes required. The new features (`updateAgentFunctions`, `Message.id`, `removeMessage`) are all additive at the JSON level; no new required fields. `Message.id` serializes only when non-null (class already has `@JsonInclude(NON_NULL)`), so messages persisted pre-1.20 round-trip unchanged.

**Source code** — recompile and you're done. All pre-1.20 call sites of `insertMessage` / `addMessage` that ignore the return value still compile identically. If you stored the return explicitly (unlikely — it was `void`), no source change needed either.

**Binary/ABI** — you MUST rebuild any downstream module that calls `AgentService.insertMessage(...)` or `ConversationManager.addMessage(...)`. Their JVM descriptors changed from `...;V` to `...;Ljava/lang/String;`; an old `.class` file linked against 1.19 will hit `NoSuchMethodError` at the first call. A clean `mvn install` on the dependency tree is enough.

**Runtime behaviour** — unchanged for every existing code path. `updateAgentFunctions` is opt-in; nothing calls it by default. Virtual-child rebuilds go through the same `applyGroupFilter` + `maybeInjectTaskOver` logic that `buildVirtualAgent` used before the refactor, so an agent whose tool list never mutates mid-turn sees byte-identical behaviour.

### v1.19.0
- **New**: `Agent.minIterationIntervalMs` — throttle the autonomous loop to a minimum start-to-start interval between iterations. Lets long-running/immortal loops cap their LLM rate without holding permits during the wait.
- **New**: `FunctionConfig.group` + `Agent.enabledToolGroups` — per-agent tool filtering. Tag tools with a group and expose only a subset to the LLM to keep input-token cost low on large toolboxes.
- **New**: Generic `endsTurn` flag on `FunctionConfig` + `Agent.endTurnOnPlainReply` — build conversational / end-of-turn tools (`ask_user`, `task_complete`) without relying on the hardcoded `task_over`. `endTurnOnPlainReply=true` lets a plain-text reply end the turn cleanly.
- **New**: `Agent.maxConversationTokens` — per-iteration truncation by estimated token budget; drops the oldest whole messages when over budget (runs after compaction).
- **New**: `Agent.compactToolResultsAfterIteration` + `compactKeepLastNIterations` — strip bulky tool-result bodies from earlier iterations while keeping tool-call summaries, preventing quadratic context growth.
- **New**: `Agent.infiniteLoop` + `maxIterationsUnlimited` — immortal observer-style agents that never self-terminate. `disableTaskOver` retained as deprecated alias for `infiniteLoop`.
- Legacy agents unaffected: when `enabledToolGroups` is `null` all tools are exposed; when no tool declares `endsTurn=true` and `infiniteLoop` is `false`, `task_over` is auto-injected exactly as before.

### v1.6.0 (2026-02-06)
- **New**: Direct Anthropic API support (`provider: "anthropic"`) - use Claude models via api.anthropic.com without Azure
- Four providers now supported: `openai`, `azure-openai`, `anthropic`, `azure-anthropic`

### v1.5.0 (2026-02-06)
- **New**: Autonomous Agent Mode - agents autonomously execute multi-step tasks with tool loops
- **New**: `ToolExecutor` functional interface for user-provided tool execution logic
- **New**: `AutonomousAgentRunner` manages the full tool-calling loop internally
- **New**: Auto-injected `task_over` function with schema from `resultClass` for structured termination
- **New**: `requestAgent(agentId, message, toolExecutor)` overload for autonomous agents
- **New**: `requestAgent(agentId, message, conversationId, toolExecutor)` overload with conversation persistence
- **New**: `maxToolTokenOutput` field to trim tool outputs in autonomous mode (prevents context overflow)
- **New**: `maxIterations` field to limit autonomous loop iterations (default: 25)
- **New**: Agent reflection support - agents can "think aloud" between tool calls
- **New**: Automatic nudging when agent responds without tool calls or task_over
- Works with both OpenAI (gpt-5.1-chat) and Claude (claude-sonnet-4-5) providers
- Integration tests for both providers covering trimming, conversation continuity, and multi-tool usage

### v1.4.0 (2026-02-06)
- **New**: Support FQCN for `resultClass` and `parameterClass` without requiring package config
- **New**: Inline parameters schema for `FunctionConfig`
- **New**: Structured `FunctionCall` support in `AgentResult`

### v1.2.0 (2026-02-05)
- **New**: Automatic conversation management with `createConversation()` / `deleteConversation()`
- **New**: `ConversationManager` for in-memory conversation history storage
- **New**: `conversationId` parameter in `requestAgent()` for automatic multi-turn
- **New**: `conversationId` in `ModelRequestOptions` for `requestModel()` conversations
- **New**: `requestAgentVision(agentId, message, imageBase64)` for simplified vision calls
- **New**: `requestImage()` and `requestImageEdit()` aliases for API consistency
- Backwards compatible: `List<Message> history` parameter still works

### v1.1.9 (2026-02-05)
- Restored full response logging (configurable via log wrapper)
- Removed legacy `createAllAgents()` / `createAgent()` methods
- Fixed method ambiguity with `requestAgentVision()` rename

### v1.1.8 (2026-02-05)
- Migrated to OpenAI Responses API for unified stateless architecture
- Added `requestImage` and `requestImageEdit` aliases

### v1.1.6 (2026-02-05)
- **New**: Direct model usage - use `requestAgent("gpt-4o", ...)` without registering an agent
- **New**: Model suffixes for tools - `gpt-4o-websearch`, `gpt-4o-codeinterpreter`
- **Fix**: Structured output JSON schema format (name at format level, not json_schema level)
- Added 29 comprehensive integration tests covering all providers and features

### v1.1.5 (2026-02-05)
- **Breaking**: Migrated to stateless Responses API (no more threads/assistants)
- Renamed `requestAgentV2` to `requestAgent` (new stateless API)
- Removed legacy OpenAI Assistants API code
- Removed `ChatCompletionService` and `AgentRequestService` (merged into `UnifiedRequestService`)
- Added web search, code interpreter, and function calling support
- Added vision (multimodal) support for images
- Simplified agent registration with `Agent.builder()`
- Multi-turn conversations now use `List<Message> history` parameter

### v1.0.7 (2025-12-06)
- feat: Simplify ArrayNode schema generation for more flexible JSON arrays
- feat: Add support for Jackson JsonNode types in structured outputs

### v1.0.6 (2025-12-05)
- feat: Improve retry logging and error messages

### v1.0.5 (2025-12-04)
- chore: Remove file logging from library

### v1.0.4 (2025-12-04)
- Added retry logic to embedding and image generation
- Added retry logic to all chat completion variants
- Improved retry for rate limits (respects retry-after header)
- Progressive timeout for consecutive timeout errors
- Smart retry: skip 4xx client errors (except 429)
