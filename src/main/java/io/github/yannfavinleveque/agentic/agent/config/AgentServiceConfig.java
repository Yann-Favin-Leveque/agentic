package io.github.yannfavinleveque.agentic.agent.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yannfavinleveque.agentic.agent.util.AgentResourceExtractor;
import lombok.Builder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Configuration for {link AgentService} with builder pattern.
 * Uses JSON-based instance configuration for flexible multi-provider setups.
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * AgentServiceConfig config = AgentServiceConfig.builder()
 *     .instancesJson(System.getenv("OPENAI_INSTANCES"))
 *     .agentResultClassPackage("com.example.results")
 *     .agentJsonFolderPath("/config/agents")
 *     .requestsPerSecond(5)
 *     .maxRetries(3)
 *     .build();
 * }</pre>
 *
 * <p>Environment variable OPENAI_INSTANCES format:</p>
 * <pre>{@code
 * [
 *   {"id":"openai-main","url":"https://api.openai.com","key":"sk-xxx","models":"gpt-4o,gpt-4o-mini","provider":"openai","enabled":true},
 *   {"id":"azure-1","url":"https://my-resource.openai.azure.com","key":"xxx","models":"gpt-4o","provider":"azure","apiVersion":"2024-08-01-preview","enabled":true},
 *   {"id":"azure-anthropic","url":"https://my-resource.services.ai.azure.com","key":"xxx","models":"claude-sonnet-4-5","provider":"azure-anthropic","apiVersion":"2023-06-01","enabled":true}
 * ]
 * }</pre>
 *
 * see AgentService
 */
@Getter
@Builder(toBuilder = true)
public class AgentServiceConfig {

    private static final Logger logger = LoggerFactory.getLogger(AgentServiceConfig.class);

    // === Instance Configuration ===

    /**
     * JSON string containing instance configurations.
     *
     * <p>Each instance object should have:</p>
     * <ul>
     *   <li>id: Unique identifier for the instance</li>
     *   <li>url: Base URL (e.g., "https://api.openai.com")</li>
     *   <li>key: API key</li>
     *   <li>models: Comma-separated list of deployed models</li>
     *   <li>provider: "openai", "azure", or "azure-anthropic"</li>
     *   <li>apiVersion: Required for Azure providers</li>
     *   <li>enabled: true/false to enable/disable the instance</li>
     * </ul>
     */
    private final String instancesJson;

    // === Agent Configuration ===

    /**
     * Package name for agent result classes (optional).
     * Used for dynamic class loading when mapping responses.
     * If a resultClass contains '.', it's treated as a fully qualified class name (FQCN).
     * Otherwise, this package is prepended.
     * Example: "com.example.agents.results"
     */
    private final String agentResultClassPackage;

    /**
     * Package name for function parameter classes (optional).
     * Used for dynamic class loading when defining function parameters.
     * If a parameterClass contains '.', it's treated as a fully qualified class name (FQCN).
     * Otherwise, this package is prepended.
     * Example: "com.example.agents.params"
     */
    private final String functionParameterClassPackage;

    /**
     * Package name for function executor classes (optional).
     * Used for dynamic class loading when resolving executorClass on FunctionConfig.
     * If an executorClass contains '.', it's treated as a fully qualified class name (FQCN).
     * Otherwise, this package is prepended.
     * Example: "com.example.executors"
     */
    private final String functionExecutorClassPackage;

    /**
     * Path to folder containing agent JSON definition files.
     * Example: "/config/agents" or "classpath:agents"
     */
    private final String agentJsonFolderPath;

    /**
     * Default timeout for agent responses in milliseconds.
     * Default: 120000ms (2 minutes)
     */
    @Builder.Default
    private final long defaultResponseTimeout = 120000L;

    // === Rate Limiting ===

    /**
     * Maximum requests per second to API.
     * Uses token bucket algorithm (Bucket4j).
     * Default: 5 requests/second
     */
    @Builder.Default
    private final int requestsPerSecond = 5;

    /**
     * Maximum concurrent HTTP/2 streams per instance.
     * Limits parallel requests to each Azure/OpenAI endpoint to prevent "too many concurrent streams" errors.
     * HTTP/2 connections typically support ~100-128 concurrent streams.
     * Setting this to 50 provides a safe margin.
     * Default: 50 concurrent streams per instance
     */
    @Builder.Default
    private final int maxConcurrentStreamsPerInstance = 50;

    /**
     * Maximum retry attempts for failed requests.
     * Default: 7
     */
    @Builder.Default
    private final int maxRetries = 7;

    /**
     * Base delay for exponential backoff in milliseconds.
     * Default: 10000ms (10 seconds)
     */
    @Builder.Default
    private final long retryBaseDelayMs = 10000L;

    /**
     * Delay for rate limit errors in milliseconds.
     * Default: 60000ms (1 minute)
     */
    @Builder.Default
    private final long rateLimitDelayMs = 60000L;

    /**
     * Delay for 502 errors in milliseconds.
     * Default: 300000ms (5 minutes)
     */
    @Builder.Default
    private final long error502DelayMs = 300000L;

    // === Retry Configuration ===

    /**
     * Global default retry configuration for all agents.
     * Individual agents can override these values in their retryConfig.
     * If null, library hard-coded defaults are used (network=7, maxToken=1, deserialization=1, maxIteration=1).
     */
    private final RetryConfig defaultRetryConfig;

    // === Executor Configuration ===

    /**
     * Custom executor for asynchronous requests (optional).
     * If null, AgentService will create a default executor.
     */
    private final Executor customExecutor;

    // ==================== CLASS RESOLUTION ====================

    /**
     * Resolves a class name to a fully qualified class name (FQCN).
     * If the className contains '.', it's treated as already fully qualified.
     * Otherwise, the provided package is prepended.
     *
     * @param className Simple class name or FQCN
     * @param packageName Package to prepend if className is simple (can be null)
     * @return Fully qualified class name, or null if both className is simple and packageName is null
     */
    public static String resolveClassName(String className, String packageName) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        // If it contains '.', it's already a FQCN
        if (className.contains(".")) {
            return className;
        }
        // Simple name - prepend package if available
        if (packageName != null && !packageName.isEmpty()) {
            return packageName + "." + className;
        }
        // No package configured, cannot resolve
        return null;
    }

    /**
     * Resolves a result class name using the configured agentResultClassPackage.
     *
     * @param resultClassName Simple class name or FQCN
     * @return Fully qualified class name, or null if cannot resolve
     */
    public String resolveResultClassName(String resultClassName) {
        return resolveClassName(resultClassName, agentResultClassPackage);
    }

    /**
     * Resolves a parameter class name using the configured functionParameterClassPackage.
     *
     * @param parameterClassName Simple class name or FQCN
     * @return Fully qualified class name, or null if cannot resolve
     */
    public String resolveParameterClassName(String parameterClassName) {
        return resolveClassName(parameterClassName, functionParameterClassPackage);
    }

    /**
     * Resolves an executor class name using the configured functionExecutorClassPackage.
     *
     * @param executorClassName Simple class name or FQCN
     * @return Fully qualified class name, or null if cannot resolve
     */
    public String resolveExecutorClassName(String executorClassName) {
        return resolveClassName(executorClassName, functionExecutorClassPackage);
    }

    // ==================== VALIDATION ====================

    /**
     * Validate configuration consistency.
     *
     * @throws IllegalArgumentException if configuration is invalid
     */
    public void validate() {
        if (instancesJson == null || instancesJson.trim().isEmpty()) {
            throw new IllegalArgumentException("instancesJson is required");
        }
        if (requestsPerSecond <= 0) {
            throw new IllegalArgumentException("requestsPerSecond must be positive");
        }
        if (maxConcurrentStreamsPerInstance <= 0) {
            throw new IllegalArgumentException("maxConcurrentStreamsPerInstance must be positive");
        }
        if (maxConcurrentStreamsPerInstance > 100) {
            throw new IllegalArgumentException("maxConcurrentStreamsPerInstance should not exceed 100 (HTTP/2 typical limit)");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }
        if (defaultResponseTimeout <= 0) {
            throw new IllegalArgumentException("defaultResponseTimeout must be positive");
        }
    }

    // ==================== JSON PARSING ====================

    /**
     * Parse the instancesJson field into a list of InstanceConfig objects.
     * This method is called internally by AgentService constructor.
     *
     * @return List of parsed InstanceConfig objects, or empty list if instancesJson is null/empty
     * @throws IllegalArgumentException if JSON parsing fails or validation fails
     */
    public List<InstanceConfig> parseInstances() {
        if (instancesJson == null || instancesJson.trim().isEmpty()) {
            return List.of();
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            List<InstanceConfig> instances = mapper.readValue(
                    instancesJson,
                    new TypeReference<List<InstanceConfig>>() {}
            );

            // Validate each instance
            for (InstanceConfig instance : instances) {
                instance.validate();
            }

            return instances;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse instancesJson: " + e.getMessage(), e);
        }
    }

    /**
     * Check if JSON-based configuration is being used.
     * @return true if instancesJson is provided and not empty
     */
    public boolean isUsingJsonConfig() {
        return instancesJson != null && !instancesJson.trim().isEmpty();
    }

    /**
     * Factory method to create configuration from JSON string.
     *
     * @param instancesJson JSON string with instance configurations
     * @return Builder pre-configured with JSON
     */
    public static AgentServiceConfigBuilder fromJson(String instancesJson) {
        return AgentServiceConfig.builder()
                .instancesJson(instancesJson);
    }

    /**
     * Processes and normalizes the agentJsonFolderPath, automatically extracting
     * resources from JAR if needed. This method is called internally before
     * AgentService initialization.
     *
     * <p>Supported forms for {@code agentJsonFolderPath}:</p>
     * <ul>
     *   <li>{@code null} — defaults to classpath sub-path {@code "agents"} (legacy).</li>
     *   <li>{@code "src/main/resources/<sub>"} — strips the prefix; classpath sub-path is {@code <sub>}.</li>
     *   <li>{@code "classpath:<sub>"} — strips the prefix; classpath sub-path is {@code <sub>}.</li>
     *   <li>Any other value — treated as a filesystem path and returned as-is (no extraction).</li>
     * </ul>
     *
     * @return Normalized agent folder path (extracted to temp directory if from JAR)
     */
    public String resolveAgentJsonFolderPath() {
        String classpathSubPath = computeClasspathSubPath(agentJsonFolderPath);

        if (classpathSubPath != null) {
            try {
                Path extractedPath = AgentResourceExtractor.extractAgentsFromClasspath(classpathSubPath);
                String resolvedPath = extractedPath.toString();
                logger.info("📂 Agent JSON folder automatically resolved to: {} (classpath sub-path: '{}')",
                        resolvedPath, classpathSubPath);
                return resolvedPath;
            } catch (IOException e) {
                logger.error("❌ Failed to extract agent resources from classpath '{}': {}",
                        classpathSubPath, e.getMessage());
                logger.warn("⚠️  Falling back to original path: {}", agentJsonFolderPath);
                return agentJsonFolderPath;
            }
        }

        // Path is already a filesystem path, use as-is
        return agentJsonFolderPath;
    }

    /**
     * Derive the classpath sub-path that should be looked up by {@link AgentResourceExtractor}.
     *
     * @param configuredPath value provided to {@code agentJsonFolderPath} (may be null)
     * @return classpath sub-path (e.g. {@code "agents"}, {@code "prompts/agents"}) or {@code null}
     *         if the configured path should be treated as a plain filesystem path.
     */
    static String computeClasspathSubPath(String configuredPath) {
        if (configuredPath == null) {
            // Backward compat: when nothing is configured, default to "agents" classpath sub-path.
            return "agents";
        }
        String trimmed = configuredPath.trim();
        if (trimmed.isEmpty()) {
            return "agents";
        }
        if (trimmed.startsWith("classpath:")) {
            String sub = trimmed.substring("classpath:".length());
            return stripSlashes(sub);
        }
        if (trimmed.startsWith("src/main/resources/")) {
            String sub = trimmed.substring("src/main/resources/".length());
            return stripSlashes(sub);
        }
        // Filesystem path — don't try to extract from classpath.
        return null;
    }

    private static String stripSlashes(String s) {
        String result = s;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.isEmpty() ? "agents" : result;
    }

}
