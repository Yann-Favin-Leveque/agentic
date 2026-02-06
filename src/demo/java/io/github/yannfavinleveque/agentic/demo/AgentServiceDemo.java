package io.github.yannfavinleveque.agentic.demo;

import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Demo for AgentService showing the new JSON-based configuration. Prerequisites: - Set environment
 * variable OPENAI_INSTANCES with JSON configuration - Optional: Set ENABLED_PROVIDERS to filter
 * providers
 */
public class AgentServiceDemo {

    private static final Logger logger = LoggerFactory.getLogger(AgentServiceDemo.class);

    /**
     * Example result class for structured outputs.
     */
    public static class WeatherResult implements AgentResult {

        public String location;
        public double temperature;
        public String conditions;
        public String recommendation;

        @Override
        public String toString() {
            return String.format("Weather in %s: %.1f C, %s. %s",
                    location, temperature, conditions, recommendation);
        }

        @Override
        public String getContent() {
            return toString();
        }

    }

    public static void main(String[] args) {
        AgentServiceDemo demo = new AgentServiceDemo();

        System.out.println("=== AgentService Demo ===\n");

        try {
            demo.demoJsonConfiguration();
            demo.demoAgentCreation();
            demo.demoChatCompletion();
        } catch (Exception e) {
            logger.error("Demo failed", e);
            e.printStackTrace();
        }
    }

    /**
     * Demo 1: JSON-based instance configuration.
     */
    public void demoJsonConfiguration() throws IOException {
        System.out.println("--- Demo 1: JSON-based Instance Configuration ---\n");

        System.out.println("Environment variable OPENAI_INSTANCES format:");
        System.out.println("[");
        System.out.println(
                "  {\"id\":\"openai-main\",\"url\":\"https://api.openai.com\",\"key\":\"sk-xxx\",\"models\":\"gpt-4o\",\"provider\":\"openai\",\"enabled\":true},");
        System.out.println(
                "  {\"id\":\"azure-1\",\"url\":\"https://my-resource.openai.azure.com\",\"key\":\"xxx\",\"models\":\"gpt-4o\",\"provider\":\"azure\",\"apiVersion\":\"2024-08-01-preview\",\"enabled\":true},");
        System.out.println(
                "  {\"id\":\"azure-anthropic\",\"url\":\"https://my-resource.services.ai.azure.com\",\"key\":\"xxx\",\"models\":\"claude-sonnet-4-5\",\"provider\":\"azure-anthropic\",\"apiVersion\":\"2023-06-01\",\"enabled\":true}");
        System.out.println("]\n");

        System.out.println("Configuration code:");
        System.out.println("AgentServiceConfig config = AgentServiceConfig.builder()");
        System.out.println("    .instancesJson(System.getenv(\"OPENAI_INSTANCES\"))");
        System.out.println("    .agentResultClassPackage(\"com.example.results\")");
        System.out.println("    .agentJsonFolderPath(\"/config/agents\")");
        System.out.println("    .build();\n");

        System.out.println("Optional: Filter providers with ENABLED_PROVIDERS:");
        System.out.println("  ENABLED_PROVIDERS=openai,azure        # Only OpenAI providers");
        System.out.println("  ENABLED_PROVIDERS=azure-anthropic     # Only Claude\n");
    }

    /**
     * Demo 2: Agent creation.
     */
    public void demoAgentCreation() throws IOException {
        System.out.println("--- Demo 2: Agent Creation ---\n");

        Agent agent = Agent.builder()
                .id("demo-assistant")
                .name("Demo Assistant")
                .model("gpt-4o")
                .instructions("You are a helpful assistant.")
                .temperature(0.7)
                .responseTimeout(120000L)
                .retrieval(false)
                .build();

        System.out.println("Created agent: " + agent.getName());
        System.out.println("  - Model: " + agent.getModel());
        System.out.println("  - Temperature: " + agent.getTemperature());
        System.out.println("  - Timeout: " + agent.getResponseTimeout() + "ms\n");
    }

    /**
     * Demo 3: Chat completion with typed results.
     */
    public void demoChatCompletion() throws IOException {
        System.out.println("--- Demo 3: Chat Completion ---\n");

        System.out.println("Simple chat completion (returns DefaultResult):");
        System.out.println("DefaultResult result = service.chatCompletion(\"gpt-4o\", messages, 0.7).join();");
        System.out.println("String text = result.getResult();\n");

        System.out.println("Typed chat completion (returns custom AgentResult):");
        System.out.println(
                "WeatherResult result = service.chatCompletion(\"gpt-4o\", messages, 0.7, WeatherResult.class).join();");
        System.out.println("double temp = result.temperature;\n");

        System.out.println("Works with both OpenAI (response_format) and Claude (output_format)!\n");
    }

}
