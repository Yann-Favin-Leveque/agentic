package io.github.yannfavinleveque.agentic.agent.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;

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
@Builder
public class AgentServiceConfig {

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
     * Package name for agent result classes.
     * Used for dynamic class loading when mapping responses.
     * Example: "com.example.agents.results"
     */
    private final String agentResultClassPackage;

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
     * Maximum retry attempts for failed requests.
     * Default: 3
     */
    @Builder.Default
    private final int maxRetries = 3;

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

    // === Executor Configuration ===

    /**
     * Custom executor for asynchronous requests (optional).
     * If null, AgentService will create a default executor.
     */
    private final Executor customExecutor;

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

}
