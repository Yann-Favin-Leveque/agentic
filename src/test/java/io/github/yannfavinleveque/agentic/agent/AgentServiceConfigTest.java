package io.github.yannfavinleveque.agentic.agent;

import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.config.InstanceConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentServiceConfig} configuration builder.
 * These tests verify the JSON-based configuration parsing and validation.
 */
class AgentServiceConfigTest {

    private static final String VALID_INSTANCES_JSON =
            "[{\"id\":\"openai-main\",\"url\":\"https://api.openai.com\",\"key\":\"sk-xxx\",\"models\":\"gpt-4o,gpt-4o-mini\",\"provider\":\"openai\",\"enabled\":true}," +
            "{\"id\":\"azure-1\",\"url\":\"https://test.openai.azure.com\",\"key\":\"azure-key\",\"models\":\"gpt-4o\",\"provider\":\"azure\",\"apiVersion\":\"2024-08-01-preview\",\"enabled\":true}]";

    private static final String SINGLE_INSTANCE_JSON =
            "[{\"id\":\"openai-main\",\"url\":\"https://api.openai.com\",\"key\":\"sk-xxx\",\"models\":\"gpt-4o\",\"provider\":\"openai\",\"enabled\":true}]";

    @Test
    void testFromJsonFactoryMethod() {
        var config = AgentServiceConfig.fromJson(VALID_INSTANCES_JSON)
                .agentResultClassPackage("com.example")
                .build();

        assertTrue(config.isUsingJsonConfig());
        assertEquals("com.example", config.getAgentResultClassPackage());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testParseInstances() {
        var config = AgentServiceConfig.fromJson(VALID_INSTANCES_JSON).build();

        List<InstanceConfig> instances = config.parseInstances();

        assertEquals(2, instances.size());
        assertEquals("openai-main", instances.get(0).getId());
        assertEquals("azure-1", instances.get(1).getId());
        assertEquals("openai", instances.get(0).getProvider());
        assertEquals("azure", instances.get(1).getProvider());
    }

    @Test
    void testValidationFailsWhenInstancesJsonMissing() {
        var config = AgentServiceConfig.builder()
                // Missing instancesJson!
                .build();

        var exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("instancesJson is required"));
    }

    @Test
    void testValidationFailsWhenRequestsPerSecondNegative() {
        var config = AgentServiceConfig.fromJson(SINGLE_INSTANCE_JSON)
                .requestsPerSecond(-1)
                .build();

        var exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("requestsPerSecond must be positive"));
    }

    @Test
    void testValidationFailsWhenMaxRetriesNegative() {
        var config = AgentServiceConfig.fromJson(SINGLE_INSTANCE_JSON)
                .maxRetries(-1)
                .build();

        var exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("maxRetries cannot be negative"));
    }

    @Test
    void testValidationFailsWhenTimeoutNegative() {
        var config = AgentServiceConfig.fromJson(SINGLE_INSTANCE_JSON)
                .defaultResponseTimeout(-1000L)
                .build();

        var exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(exception.getMessage().contains("defaultResponseTimeout must be positive"));
    }

    @Test
    void testDefaultValues() {
        var config = AgentServiceConfig.fromJson(SINGLE_INSTANCE_JSON).build();

        assertEquals(5, config.getRequestsPerSecond());
        assertEquals(3, config.getMaxRetries());
        assertEquals(120000L, config.getDefaultResponseTimeout());
        assertEquals(10000L, config.getRetryBaseDelayMs());
        assertEquals(60000L, config.getRateLimitDelayMs());
        assertEquals(300000L, config.getError502DelayMs());
    }

    @Test
    void testCustomValues() {
        var config = AgentServiceConfig.fromJson(SINGLE_INSTANCE_JSON)
                .requestsPerSecond(10)
                .maxRetries(5)
                .defaultResponseTimeout(60000L)
                .retryBaseDelayMs(5000L)
                .rateLimitDelayMs(30000L)
                .error502DelayMs(120000L)
                .agentResultClassPackage("com.example.results")
                .agentJsonFolderPath("/config/agents")
                .build();

        assertEquals(10, config.getRequestsPerSecond());
        assertEquals(5, config.getMaxRetries());
        assertEquals(60000L, config.getDefaultResponseTimeout());
        assertEquals(5000L, config.getRetryBaseDelayMs());
        assertEquals(30000L, config.getRateLimitDelayMs());
        assertEquals(120000L, config.getError502DelayMs());
        assertEquals("com.example.results", config.getAgentResultClassPackage());
        assertEquals("/config/agents", config.getAgentJsonFolderPath());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testIsUsingJsonConfig() {
        var configWithJson = AgentServiceConfig.fromJson(SINGLE_INSTANCE_JSON).build();
        var configWithoutJson = AgentServiceConfig.builder().build();

        assertTrue(configWithJson.isUsingJsonConfig());
        assertFalse(configWithoutJson.isUsingJsonConfig());
    }

    @Test
    void testParseInstancesReturnsEmptyListWhenNull() {
        var config = AgentServiceConfig.builder().build();

        List<InstanceConfig> instances = config.parseInstances();

        assertTrue(instances.isEmpty());
    }

    @Test
    void testParseInstancesThrowsOnInvalidJson() {
        var config = AgentServiceConfig.fromJson("invalid json").build();

        assertThrows(IllegalArgumentException.class, config::parseInstances);
    }

    @Test
    void testMultiProviderConfiguration() {
        String multiProviderJson =
            "[{\"id\":\"openai\",\"url\":\"https://api.openai.com\",\"key\":\"sk-xxx\",\"models\":\"gpt-4o\",\"provider\":\"openai\",\"enabled\":true}," +
            "{\"id\":\"azure\",\"url\":\"https://test.openai.azure.com\",\"key\":\"key\",\"models\":\"gpt-4o\",\"provider\":\"azure\",\"apiVersion\":\"2024-08-01-preview\",\"enabled\":true}," +
            "{\"id\":\"anthropic\",\"url\":\"https://test.services.ai.azure.com\",\"key\":\"key\",\"models\":\"claude-sonnet-4-5\",\"provider\":\"azure-anthropic\",\"apiVersion\":\"2023-06-01\",\"enabled\":true}]";

        var config = AgentServiceConfig.fromJson(multiProviderJson).build();
        List<InstanceConfig> instances = config.parseInstances();

        assertEquals(3, instances.size());
        assertEquals("openai", instances.get(0).getProvider());
        assertEquals("azure", instances.get(1).getProvider());
        assertEquals("azure-anthropic", instances.get(2).getProvider());
    }

    @Test
    void testBuilderPattern() {
        var config = AgentServiceConfig.builder()
                .instancesJson(SINGLE_INSTANCE_JSON)
                .requestsPerSecond(10)
                .maxRetries(5)
                .defaultResponseTimeout(60000L)
                .agentResultClassPackage("com.example")
                .build();

        assertNotNull(config);
        assertTrue(config.isUsingJsonConfig());
        assertEquals(10, config.getRequestsPerSecond());
        assertEquals("com.example", config.getAgentResultClassPackage());
    }

}
