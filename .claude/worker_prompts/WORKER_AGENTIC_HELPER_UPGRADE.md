# WORKER: Agentic-Helper v1.0.4 Upgrade - Retry & Logging

## 🎯 Mission
Improve retry logic and implement compact logging in agentic-helper library.

**Working Directory:** `C:\Users\user\IdeaProjects\agentic-helper`
**Target Version:** 1.0.4
**Branch:** `feature/retry-logging-improvements`

---

## 📋 Tasks

### 1. Add Retry to All Request Methods

**Currently ONLY `requestAgent()` has retry. Add to:**

#### A) ChatCompletionService.generateEmbedding()
- Add retry with exponential backoff
- Use same pattern as AgentRequestService.attemptRequest()
- Max retries from config

#### B) ChatCompletionService.generateImage()
- Add retry with exponential backoff
- Handle rate limits properly

#### C) ChatCompletionService.chatCompletion() (all variants)
- Add retry for direct chat completions

**Pattern to follow:**
```java
private CompletableFuture<T> attemptEmbedding(String text, String model, int attemptNumber) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            return executeEmbeddingRequest(text, model);
        } catch (Exception e) {
            return handleEmbeddingException(text, model, attemptNumber, e);
        }
    });
}
```

---

### 2. Improve Retry Logic in AgentRequestService

**Current issues:**
- RateLimitException throws immediately (should retry with delay!)
- No distinction between 4xx (client error, don't retry) vs 5xx (server error, retry)
- No progressive timeout for consecutive timeout errors

**Changes needed:**

#### A) Fix RateLimitException handling (line ~421)
```java
// BEFORE:
if (errorMessage.contains("rate_limit") || errorMessage.contains("429")) {
    throw new RateLimitException("Rate limit exceeded: " + e.getMessage());
}

// AFTER:
if (errorMessage.contains("rate_limit") || errorMessage.contains("429")) {
    long retryDelay = extractRetryAfter(e); // Extract from response header if available
    logger.warn("Rate limit hit (attempt {}), waiting {}ms", attemptNumber + 1, retryDelay);
    try {
        Thread.sleep(retryDelay);
        return attemptRequest(agent, userMessage, threadId, additionalParams, attemptNumber + 1).join();
    } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new RateLimitException("Rate limit retry interrupted", retryDelay);
    }
}
```

#### B) Add progressive timeout for TimeoutException
```java
// Track consecutive timeouts
if (e instanceof RequestTimeoutException) {
    // Double timeout for next attempt
    int newTimeout = baseTimeout * (attemptNumber + 1);
    additionalParams.put("timeout", newTimeout);
    logger.warn("Timeout (attempt {}), increasing timeout to {}ms", attemptNumber + 1, newTimeout);
}
```

#### C) Don't retry 4xx errors (except 429)
```java
// Check HTTP status code
if (errorMessage.contains("400") || errorMessage.contains("401") ||
    errorMessage.contains("403") || errorMessage.contains("404")) {
    logger.error("Client error (4xx), not retrying: {}", e.getMessage());
    throw new AgentException(AgentException.ErrorCode.REQUEST_FAILED, "Client error: " + e.getMessage(), e);
}
```

---

### 3. Implement Compact Logging (Logback)

**Create:** `src/main/resources/logback.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <!-- Compact format: replace all newlines with " | " -->
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %replace(%msg){'[\r\n]+', ' | '}%n</pattern>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/agentic-helper.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/agentic-helper-%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %replace(%msg){'[\r\n]+', ' | '}%n</pattern>
        </encoder>
    </appender>

    <!-- Root logger -->
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>

    <!-- Library-specific levels -->
    <logger name="io.github.yannfavinleveque.agentic" level="DEBUG"/>
</configuration>
```

**Effect:**
- All logs on single line
- JSON: `{"key":"value",\n"key2":"value2"}` → `{"key":"value", | "key2":"value2"}`
- Stack traces: `Exception\n  at line1\n  at line2` → `Exception | at line1 | at line2`

---

### 4. Improve Log Levels

**Review all logs and set appropriate levels:**

#### TRACE (very detailed):
- Each HTTP request/response body
- Each instance selection
- Thread state changes

#### DEBUG (development):
- Agent call start/end
- Retry attempts
- Rate limiter decisions
- Instance routing

#### INFO (production):
- Agent request completed
- Embedding batch completed
- Configuration loaded

#### WARN (recoverable issues):
- Retry triggered
- Rate limit hit
- Fallback used
- Instance degraded

#### ERROR (critical failures):
- Max retries exceeded
- No instances available
- Invalid configuration
- Request failed after all retries

**Example fixes:**
```java
// BEFORE:
logger.info("Request failed, retrying...");

// AFTER:
logger.warn("Request failed (attempt {}/{}), retrying in {}ms: {}",
    attemptNumber + 1, config.getMaxRetries(), delay, e.getMessage());
```

---

### 5. Update Version

**pom.xml:**
```xml
<version>1.0.4</version>
```

**Changelog entry in README.md:**
```markdown
## v1.0.4 (2025-12-04)

### Improvements
- ✅ Added retry logic to embedding and image generation
- ✅ Improved retry for rate limits (respects retry-after header)
- ✅ Progressive timeout for consecutive timeout errors
- ✅ Smart retry: skip 4xx client errors (except 429)
- ✅ Compact logs: all logs on single line (Logback config)
- ✅ Improved log levels (TRACE/DEBUG/INFO/WARN/ERROR)

### Breaking Changes
None - fully backward compatible
```

---

## ✅ Acceptance Criteria

1. ✅ All request methods have retry (requestAgent, embedding, image, chat)
2. ✅ RateLimitException triggers retry with delay
3. ✅ Consecutive timeouts increase timeout progressively
4. ✅ 4xx errors don't retry (except 429)
5. ✅ Logback configured for compact single-line logs
6. ✅ Log levels appropriate (TRACE/DEBUG/INFO/WARN/ERROR)
7. ✅ Version updated to 1.0.4
8. ✅ Tests pass
9. ✅ Committed and pushed

---

## 🚀 Execution

```bash
cd C:\Users\user\IdeaProjects\agentic-helper
git checkout -b feature/retry-logging-improvements

# Implement all changes
# Test thoroughly

mvn clean install
git add .
git commit -m "feat: v1.0.4 - Improved retry logic and compact logging"
git push origin feature/retry-logging-improvements
```

---

**Estimated time: 1-2 hours**

Good luck! 🚀
