package io.github.yannfavinleveque.agentic.agent.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-agent retry configuration for different error types.
 * <p>
 * Each field controls how many retries are allowed for a specific category of error.
 * A {@code null} value means "use the default from {@link AgentServiceConfig#getDefaultRetryConfig()}".
 * </p>
 *
 * <p>Resolution order for each field:</p>
 * <ol>
 *   <li>Agent-level retryConfig (from JSON or programmatic)</li>
 *   <li>Global defaultRetryConfig from AgentServiceConfig</li>
 *   <li>Library hard-coded defaults: network=7, maxToken=1, deserialization=1, maxIteration=1</li>
 * </ol>
 *
 * <p>Example JSON in agent definition:</p>
 * <pre>{@code
 * {
 *   "retryConfig": {
 *     "networkRetries": 5,
 *     "maxTokenRetries": 0,
 *     "deserializationRetries": 1,
 *     "maxIterationRetries": 0
 *   }
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetryConfig {

    /** Library hard-coded defaults */
    public static final int DEFAULT_NETWORK_RETRIES = 7;
    public static final int DEFAULT_MAX_TOKEN_RETRIES = 1;
    public static final int DEFAULT_DESERIALIZATION_RETRIES = 1;
    public static final int DEFAULT_MAX_ITERATION_RETRIES = 1;
    /**
     * Default retries for content-filter 400 errors. Set to a small positive
     * value so the round-robin can try a different LLM instance — Azure regions
     * have varying filter strictness, and a prompt that trips one endpoint may
     * be accepted by another. Capped to avoid wasting tokens when the prompt
     * itself is the problem.
     */
    public static final int DEFAULT_CONTENT_FILTER_RETRIES = 3;

    /**
     * Retries for network/infrastructure errors (429 rate limit, 502, timeout, 5xx).
     * These are transient and worth retrying. Default: 7.
     */
    @JsonProperty("networkRetries")
    @JsonAlias("network_retries")
    private Integer networkRetries;

    /**
     * Retries for max token exceeded errors (LLM output truncated due to maxTokens limit).
     * Usually not worth retrying since the same prompt will produce the same truncated output.
     * Default: 1.
     */
    @JsonProperty("maxTokenRetries")
    @JsonAlias("max_token_retries")
    private Integer maxTokenRetries;

    /**
     * Retries for deserialization errors (LLM returned invalid JSON for the result class).
     * Occasionally worth one retry since LLM output is non-deterministic.
     * Default: 1.
     */
    @JsonProperty("deserializationRetries")
    @JsonAlias("deserialization_retries")
    private Integer deserializationRetries;

    /**
     * Retries for autonomous agent max iteration exceeded errors.
     * When the autonomous loop exhausts maxIterations, retry the entire loop.
     * Default: 1.
     */
    @JsonProperty("maxIterationRetries")
    @JsonAlias("max_iteration_retries")
    private Integer maxIterationRetries;

    /**
     * Retries for content-filter 400 errors (Azure responsible-AI policy and
     * similar). The intent is to round-robin to a different LLM instance that
     * may have looser filtering. Default: 3. Set to 0 to preserve the legacy
     * "fail immediately on content filter" behaviour.
     */
    @JsonProperty("contentFilterRetries")
    @JsonAlias("content_filter_retries")
    private Integer contentFilterRetries;

    /**
     * Resolves the effective retry count for network errors, falling back through the chain.
     *
     * @param globalDefault the global default RetryConfig (from AgentServiceConfig), may be null
     * @return effective retry count
     */
    public int resolveNetworkRetries(RetryConfig globalDefault) {
        if (networkRetries != null) return networkRetries;
        if (globalDefault != null && globalDefault.getNetworkRetries() != null) return globalDefault.getNetworkRetries();
        return DEFAULT_NETWORK_RETRIES;
    }

    /**
     * Resolves the effective retry count for max token errors, falling back through the chain.
     */
    public int resolveMaxTokenRetries(RetryConfig globalDefault) {
        if (maxTokenRetries != null) return maxTokenRetries;
        if (globalDefault != null && globalDefault.getMaxTokenRetries() != null) return globalDefault.getMaxTokenRetries();
        return DEFAULT_MAX_TOKEN_RETRIES;
    }

    /**
     * Resolves the effective retry count for deserialization errors, falling back through the chain.
     */
    public int resolveDeserializationRetries(RetryConfig globalDefault) {
        if (deserializationRetries != null) return deserializationRetries;
        if (globalDefault != null && globalDefault.getDeserializationRetries() != null) return globalDefault.getDeserializationRetries();
        return DEFAULT_DESERIALIZATION_RETRIES;
    }

    /**
     * Resolves the effective retry count for max iteration errors, falling back through the chain.
     */
    public int resolveMaxIterationRetries(RetryConfig globalDefault) {
        if (maxIterationRetries != null) return maxIterationRetries;
        if (globalDefault != null && globalDefault.getMaxIterationRetries() != null) return globalDefault.getMaxIterationRetries();
        return DEFAULT_MAX_ITERATION_RETRIES;
    }

    /**
     * Resolves the effective retry count for content-filter errors, falling back through the chain.
     */
    public int resolveContentFilterRetries(RetryConfig globalDefault) {
        if (contentFilterRetries != null) return contentFilterRetries;
        if (globalDefault != null && globalDefault.getContentFilterRetries() != null) return globalDefault.getContentFilterRetries();
        return DEFAULT_CONTENT_FILTER_RETRIES;
    }
}
