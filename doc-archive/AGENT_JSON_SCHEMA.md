# Agent JSON Configuration Schema

## Overview

This document describes the JSON schema for defining agents in the AgentService.
Agent configuration files can be loaded from a directory specified in `AgentServiceConfig`.

## Unified Architecture (v3.18.0+)

As of version 3.18.0, SimpleOpenAI and SimpleOpenAIAzure have been merged into a single unified class.
The `assistantIds` field now contains assistant IDs for all configured instances (OpenAI and/or Azure).

## JSON Schema

```json
{
  "id": "string (required)",
  "name": "string (required)",
  "model": "string (required)",
  "instructions": "string (required)",
  "resultClass": "string (optional)",
  "temperature": "number (optional, 0.0-2.0)",
  "retrieval": "boolean (optional, default: false)",
  "responseTimeout": "number (optional, milliseconds, default: 120000)",
  "threadType": "string (optional)",
  "agentType": "string (optional)",
  "createOnAppStart": "boolean (optional, default: false)",
  "assistantIds": "array of strings (optional)"
}
```

## Field Descriptions

### Required Fields

- **id**: Unique identifier for the agent (application-level ID)
  - Example: `"customer-support-agent"`
  - Used to reference the agent in API calls

- **name**: Human-readable name for the agent
  - Example: `"Customer Support Agent"`
  - Used for logging and display purposes

- **model**: OpenAI model to use
  - Example: `"gpt-4o"`, `"gpt-4o-mini"`, `"gpt-3.5-turbo"`
  - Must be a valid OpenAI model identifier

- **instructions**: System instructions for the agent
  - Example: `"You are a helpful customer support agent..."`
  - Defines the agent's behavior and personality

### Optional Fields

- **resultClass**: Fully qualified class name for structured output mapping
  - Example: `"com.example.results.CustomerSupportResult"`
  - If specified, responses will be parsed into this class (must implement `AgentResult`)
  - If null/omitted, returns raw string response

- **temperature**: Controls randomness of responses (0.0 to 2.0)
  - Default: Not set (uses OpenAI default)
  - Lower values (e.g., 0.2) = more focused, deterministic
  - Higher values (e.g., 1.5) = more creative, random
  - Example: `0.7`

- **retrieval**: Enable file search/RAG capabilities
  - Default: `false`
  - Set to `true` to enable vector store search

- **responseTimeout**: Maximum time to wait for response (milliseconds)
  - Default: `120000` (2 minutes)
  - Example: `180000` for 3 minutes

- **threadType**: Thread management strategy
  - Values: `"SINGLE"` (persistent thread), `"MULTI"` (new thread per request)
  - Example: `"SINGLE"`

- **agentType**: Category/type for organizational purposes
  - Example: `"support"`, `"generation"`, `"interrogation"`
  - Used for filtering and categorization

- **createOnAppStart**: Whether to create assistant on application startup
  - Default: `false`
  - Set to `true` to auto-create assistants when loading agents

- **assistantIds**: Array of assistant IDs for each instance
  - **Important**: Index corresponds to instance index in AgentService
  - For single OpenAI instance: `["asst_abc123"]`
  - For multi-instance (OpenAI + 2 Azure): `["asst_openai_123", "asst_azure1_456", "asst_azure2_789"]`
  - If not specified or empty, assistants will be created automatically
  - Example:
    ```json
    "assistantIds": [
      "asst_openai_abc123",
      "asst_azure1_def456",
      "asst_azure2_ghi789"
    ]
    ```

## Examples

### Example 1: Simple Customer Support Agent (Single OpenAI Instance)

```json
{
  "id": "support-agent",
  "name": "Customer Support",
  "model": "gpt-4o",
  "instructions": "You are a helpful customer support agent.",
  "temperature": 0.7,
  "assistantIds": ["asst_abc123"]
}
```

### Example 2: Multi-Instance Agent (OpenAI + Azure)

```json
{
  "id": "code-assistant",
  "name": "Code Review Assistant",
  "model": "gpt-4o",
  "instructions": "You are an expert code reviewer. Provide detailed feedback on code quality, best practices, and potential bugs.",
  "resultClass": "CodeReviewResult",
  "temperature": 0.3,
  "retrieval": true,
  "responseTimeout": 180000,
  "threadType": "SINGLE",
  "agentType": "development",
  "createOnAppStart": true,
  "assistantIds": [
    "asst_openai_main",
    "asst_azure_eu",
    "asst_azure_us"
  ]
}
```

### Example 3: Agent with Structured Output

```json
{
  "id": "data-extractor",
  "name": "Data Extraction Agent",
  "model": "gpt-4o",
  "instructions": "Extract structured data from user input. Always return data in the specified format.",
  "resultClass": "com.example.results.ExtractedData",
  "temperature": 0.0,
  "assistantIds": ["asst_extractor_123"]
}
```

### Example 4: RAG-Enabled Knowledge Agent

```json
{
  "id": "knowledge-base",
  "name": "Knowledge Base Agent",
  "model": "gpt-4o",
  "instructions": "Answer questions based on the knowledge base. Always cite sources when possible.",
  "temperature": 0.5,
  "retrieval": true,
  "responseTimeout": 240000,
  "threadType": "MULTI",
  "agentType": "knowledge",
  "assistantIds": ["asst_kb_123"]
}
```

## Migration from v3.17.0

If you have existing agent JSON files using the old format:

**Old format (v3.17.0 and earlier):**
```json
{
  "openAiId": "asst_abc123",
  "openAiAzureIds": ["asst_azure1", "asst_azure2"]
}
```

**New format (v3.18.0+):**
```json
{
  "assistantIds": ["asst_openai_123", "asst_azure1_456", "asst_azure2_789"]
}
```

### Migration Steps:

1. Combine `openAiId` (if present) and `openAiAzureIds` into a single `assistantIds` array
2. Order matters: Place OpenAI instance IDs before Azure instance IDs
3. Index must match your `AgentServiceConfig` instance order

**Example:**

If your AgentServiceConfig is:
```java
var config = AgentServiceConfig.builder()
    .openAiApiKey("sk-...")
    .azureApiKeys(List.of("key1", "key2"))
    .azureBaseUrls(List.of("url1", "url2"))
    .azureApiVersion("2024-08-01-preview")
    .build();
```

Then your agent should have:
```json
"assistantIds": [
  "asst_for_openai_instance",    // Index 0: OpenAI
  "asst_for_azure_instance_1",   // Index 1: Azure instance 1
  "asst_for_azure_instance_2"    // Index 2: Azure instance 2
]
```

## Loading Agents

To load agents from JSON files:

```java
var config = AgentServiceConfig.forOpenAI(apiKey)
    .agentJsonFolderPath("/path/to/agent/definitions")
    .agentResultClassPackage("com.example.results")
    .build();

var agentService = new AgentService(config);
// Agents are loaded automatically from JSON files in the specified folder
```

## Best Practices

1. **Use descriptive IDs**: Make agent IDs meaningful (e.g., `"customer-support"` not `"agent1"`)
2. **Set appropriate timeouts**: Increase `responseTimeout` for complex tasks
3. **Temperature tuning**:
   - 0.0-0.3: Factual, deterministic responses
   - 0.4-0.7: Balanced creativity and consistency
   - 0.8-2.0: High creativity, more randomness
4. **Structured outputs**: Use `resultClass` when you need typed responses
5. **Instance ordering**: Always list assistant IDs in the same order as your configured instances
6. **Version control**: Keep agent JSON files in version control for traceability

## See Also

- [AgentService Quickstart](AGENTSERVICE_QUICKSTART.md)
- [Testing Guide](TESTING_GUIDE.md)
- [Publishing Guide](PUBLISHING_SUCCESS.md)
