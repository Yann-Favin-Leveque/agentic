# Changelog

All notable changes to this project will be documented in this file.

## [3.31.0] - 2025-11-06

### Added

#### Instance Enable/Disable Support
- **`enabled` Field in InstanceConfig**: New optional boolean field (default: `true`)
  - Allows temporarily disabling instances without removing them from configuration
  - Set `"enabled": false` in instance JSON to skip loading
  - Backward compatible: existing configurations without the field default to `enabled=true`
- **Enhanced Logging**: Shows total vs enabled instances
  - Format: `"Loaded X instance(s) from JSON configuration (Y total, X enabled)"`
  - Example: `"Loaded 8 instance(s) from JSON configuration (9 total, 8 enabled)"`

### Changed
- **AgentService Constructor**: Filters instances to only load those with `enabled=true`
- **InstanceConfig**: Added validation and JavaDoc for `enabled` field

### Use Cases
- **Cost Control**: Disable expensive instances temporarily
- **Testing**: Enable/disable instances for testing different configurations
- **Maintenance**: Disable instances undergoing maintenance without removing config
- **Gradual Rollout**: Enable instances one at a time during deployment

### Example
```json
[
  {
    "id": "openai-main",
    "url": "https://api.openai.com/v1",
    "key": "sk-xxx",
    "models": "gpt-4o",
    "provider": "openai",
    "enabled": false
  },
  {
    "id": "azure-backup",
    "url": "https://my-resource.openai.azure.com",
    "key": "azure-key",
    "models": "gpt-4o",
    "provider": "azure",
    "apiVersion": "2024-08-01-preview",
    "enabled": true
  }
]
```

### Tests
- All 308 tests passing
- Backward compatibility verified (configs without `enabled` field work correctly)

---

## [3.18.0] - 2025-10-21

### Added - AgentService Enhancements

#### Provider Enum Pattern
- **Unified Provider Configuration**: Replaced `useAzure` boolean with extensible `Provider` enum
  - `Provider.OPENAI` for standard OpenAI API
  - `Provider.AZURE` for Azure OpenAI Service
  - TODO comments for future providers (Claude, Grok, Gemini) using Chat Completion fallback
- **Clean Factory Methods**:
  - `AgentServiceConfig.forOpenAI(apiKey)` - Configure for OpenAI
  - `AgentServiceConfig.forAzure(apiKey, baseUrl, apiVersion)` - Single Azure instance
  - `AgentServiceConfig.forAzureMultiInstance(keys, urls, apiVersion)` - Multi-instance load balancing
- **Backward Compatibility**: `isUseAzure()` helper method maintained

#### Vector Store Instance Encoding
- **Automatic Instance Tracking**: Vector store references encode Azure instance index
  - Format: `"instanceIndex_vectorStoreId"` (e.g., `"2_vs_abc123"`)
  - Plain ID for OpenAI or single Azure instance: `"vs_abc123"`
- **Seamless Instance Affinity**: `createVectorStore()` and `deleteVectorStore()` automatically encode/decode
- **No External Tracking**: Self-describing references eliminate need for instance mapping

#### Persistent Thread API
- **Multi-Turn Conversations**: New methods for persistent thread management
  - `createThread()` - Creates thread without auto-deletion
  - `sendMessageToThread(agentId, threadRef, message)` - Sends to existing thread
  - `deleteThread(threadRef)` - Explicitly deletes thread
- **Thread Instance Encoding**: Same pattern as vector stores
  - Format: `"instanceIndex_threadId"` (e.g., `"1_thread_xyz789"`)
  - Maintains instance affinity for Azure multi-instance
- **Use Cases**: Customer support chats, tutoring sessions, code review workflows

### Changed

- **AgentServiceConfig**: Provider validation logic updated to use enum instead of boolean
- **AgentService**: Internal routing logic enhanced for instance-aware operations

### Tests

- **30 AgentService Tests**: 100% passing
  - 15 tests for AgentServiceConfig (provider enum, validation)
  - 7 tests for Agent POJO
  - 8 tests for AgentResult mapping
- **175 Total Library Tests**: All passing

### Documentation

- **Updated AgentServiceDemo**: Added 3 new demo scenarios
  - Demo 6: Provider enum patterns
  - Demo 7: Vector store instance encoding
  - Demo 8: Persistent thread API
- **MERGE_ANALYSIS.md**: Analysis of upstream changes (v3.17.0 → v3.22.2)
- **AGENTSERVICE_QUICKSTART.md**: Quick start guide
- **TESTING_GUIDE.md**: Comprehensive testing and publishing guide

### Migration Guide

If upgrading from 3.17.0, update configurations:

**Before (3.17.0):**
```java
AgentServiceConfig config = AgentServiceConfig.builder()
    .useAzure(false)
    .openAiApiKey(apiKey)
    .build();
```

**After (3.18.0):**
```java
AgentServiceConfig config = AgentServiceConfig
    .forOpenAI(apiKey)
    .build();
```

**Azure Multi-Instance (3.18.0):**
```java
AgentServiceConfig config = AgentServiceConfig
    .forAzureMultiInstance(
        List.of(apiKey1, apiKey2, apiKey3),
        List.of(baseUrl1, baseUrl2, baseUrl3),
        "2024-08-01-preview"
    )
    .requestsPerSecond(15)  // 3 instances * 5 req/s each
    .build();
```

### Notes

- This version consolidates AgentService implementations from multiple projects
- Published to GitHub Packages: `io.github.Yann-Favin-Leveque:simple-openai:3.18.0`
- Fork of [sashirestela/simple-openai](https://github.com/sashirestela/simple-openai) v3.17.0
- For upstream merge consideration after testing in production

---

## [3.17.0] - 2024-12/2025-01

Base version forked from [sashirestela/simple-openai](https://github.com/sashirestela/simple-openai)

### Features from Base Library

- OpenAI API client with Assistants, Chat Completions, Embeddings, Images, Audio
- Azure OpenAI support
- Basic AgentService implementation
- Rate limiting with Bucket4j
- Structured outputs with JSON Schema
- Comprehensive test coverage
