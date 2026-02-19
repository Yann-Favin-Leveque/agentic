package io.github.yannfavinleveque.agentic.integration;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import io.github.yannfavinleveque.agentic.agent.model.ModelRequestOptions;
import io.github.yannfavinleveque.agentic.agent.service.AgentService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for reasoning/thinking support across Azure OpenAI and Azure Anthropic.
 * <p>
 * Tests all reasoning modes: none, low, medium, high, enabled.
 * Makes REAL API calls — requires valid API keys and RUN_INTEGRATION_TESTS=true.
 * </p>
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class ReasoningIntegrationTest {

    private AgentService agentService;

    private static final Dotenv dotenv = Dotenv.load();

    @BeforeAll
    void setup() {
        String instancesJson = dotenv.get("LLM_INSTANCES");
        if (instancesJson == null || instancesJson.isEmpty()) {
            instancesJson = dotenv.get("OPENAI_INSTANCES");
        }
        assumeTrue(instancesJson != null && !instancesJson.isEmpty(),
                "LLM_INSTANCES (or OPENAI_INSTANCES) must be set in .env file");

        AgentServiceConfig config = AgentServiceConfig.builder()
                .instancesJson(instancesJson)
                .requestsPerSecond(5)
                .maxRetries(3)
                .defaultResponseTimeout(120000L)
                .build();

        agentService = new AgentService(config);
        registerReasoningAgents();
    }

    private void registerReasoningAgents() {
        // ==================== Azure OpenAI (GPT-5.1) — reasoning modes ====================

        // None (default — should work like before, no reasoning tokens consumed)
        agentService.registerAgent(Agent.builder()
                .id("test-openai-reasoning-none")
                .name("OpenAI Reasoning None")
                .model("gpt-5.1-chat")
                .instructions("You are a helpful assistant. Answer briefly and concisely.")
                .maxTokens(50)
                .reasoningEffort(null) // null → defaults to "none"
                .build());

        // Explicit none
        agentService.registerAgent(Agent.builder()
                .id("test-openai-reasoning-none-explicit")
                .name("OpenAI Reasoning None Explicit")
                .model("gpt-5.1-chat")
                .instructions("You are a helpful assistant. Answer briefly and concisely.")
                .maxTokens(50)
                .reasoningEffort("none")
                .build());

        // Low
        agentService.registerAgent(Agent.builder()
                .id("test-openai-reasoning-low")
                .name("OpenAI Reasoning Low")
                .model("gpt-5.1-chat")
                .instructions("You are a helpful assistant. Answer briefly.")
                .maxTokens(1000)
                .reasoningEffort("low")
                .build());

        // Medium
        agentService.registerAgent(Agent.builder()
                .id("test-openai-reasoning-medium")
                .name("OpenAI Reasoning Medium")
                .model("gpt-5.1-chat")
                .instructions("You are a helpful assistant. Answer briefly.")
                .maxTokens(1000)
                .reasoningEffort("medium")
                .build());

        // High
        agentService.registerAgent(Agent.builder()
                .id("test-openai-reasoning-high")
                .name("OpenAI Reasoning High")
                .model("gpt-5.1-chat")
                .instructions("You are a helpful assistant. Answer briefly.")
                .maxTokens(2000)
                .reasoningEffort("high")
                .build());

        // Enabled (maps to medium)
        agentService.registerAgent(Agent.builder()
                .id("test-openai-reasoning-enabled")
                .name("OpenAI Reasoning Enabled")
                .model("gpt-5.1-chat")
                .instructions("You are a helpful assistant. Answer briefly.")
                .maxTokens(1000)
                .reasoningEffort("enabled")
                .build());

        // ==================== Azure Anthropic (Claude Sonnet) — reasoning modes ====================

        // None (default)
        agentService.registerAgent(Agent.builder()
                .id("test-claude-reasoning-none")
                .name("Claude Reasoning None")
                .model("claude-sonnet-4-5")
                .instructions("You are a helpful assistant. Answer briefly and concisely.")
                .maxTokens(200)
                .reasoningEffort(null) // null → no thinking
                .build());

        // Explicit none
        agentService.registerAgent(Agent.builder()
                .id("test-claude-reasoning-none-explicit")
                .name("Claude Reasoning None Explicit")
                .model("claude-sonnet-4-5")
                .instructions("You are a helpful assistant. Answer briefly and concisely.")
                .maxTokens(200)
                .reasoningEffort("none")
                .build());

        // Low
        agentService.registerAgent(Agent.builder()
                .id("test-claude-reasoning-low")
                .name("Claude Reasoning Low")
                .model("claude-sonnet-4-5")
                .instructions("You are a helpful assistant. Answer briefly.")
                .maxTokens(1000)
                .reasoningEffort("low")
                .build());

        // Medium
        agentService.registerAgent(Agent.builder()
                .id("test-claude-reasoning-medium")
                .name("Claude Reasoning Medium")
                .model("claude-sonnet-4-5")
                .instructions("You are a helpful assistant. Answer briefly.")
                .maxTokens(1000)
                .reasoningEffort("medium")
                .build());

        // High
        agentService.registerAgent(Agent.builder()
                .id("test-claude-reasoning-high")
                .name("Claude Reasoning High")
                .model("claude-sonnet-4-5")
                .instructions("You are a helpful assistant. Answer briefly.")
                .maxTokens(2000)
                .reasoningEffort("high")
                .build());

        // Enabled
        agentService.registerAgent(Agent.builder()
                .id("test-claude-reasoning-enabled")
                .name("Claude Reasoning Enabled")
                .model("claude-sonnet-4-5")
                .instructions("You are a helpful assistant. Answer briefly.")
                .maxTokens(1000)
                .reasoningEffort("enabled")
                .build());
    }

    // ==================== AZURE OPENAI (GPT-5.1) REASONING TESTS ====================

    @Nested
    @DisplayName("Azure OpenAI — Reasoning Modes (gpt-5.1-chat)")
    class AzureOpenAIReasoningTests {

        @Test
        @DisplayName("reasoning=null (default none) — maxTokens=50 should NOT crash")
        void testReasoningNull() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-openai-reasoning-none", "What is 2+2? Answer with just the number.")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content, "Content should not be null — reasoning=none means all tokens go to output");
            assertTrue(content.contains("4"), "Response should contain '4': " + content);

            System.out.println("✅ OpenAI reasoning=null (none): " + content.trim());
        }

        @Test
        @DisplayName("reasoning='none' (explicit) — maxTokens=50 should NOT crash")
        void testReasoningNoneExplicit() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-openai-reasoning-none-explicit", "What is 3+3? Answer with just the number.")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.contains("6"), "Response should contain '6': " + content);

            System.out.println("✅ OpenAI reasoning='none': " + content.trim());
        }

        @Test
        @DisplayName("reasoning='low' — NOT SUPPORTED on gpt-5.1-chat (only 'medium' allowed)")
        void testReasoningLow() {
            // gpt-5.1-chat-2025-11-13 only supports reasoning.effort='medium'
            try {
                AgentResult result = agentService
                        .requestAgent("test-openai-reasoning-low", "What is 15 * 13? Answer with just the number.")
                        .get(90, TimeUnit.SECONDS);

                // If it works, great — model may have been updated to support 'low'
                assertNotNull(result);
                System.out.println("✅ OpenAI reasoning='low' SUPPORTED: " + result.getContent().trim());
            } catch (Exception e) {
                assertTrue(e.getMessage().contains("Unsupported value"),
                        "Should be 'Unsupported value' error: " + e.getMessage());
                System.out.println("⚠️ OpenAI reasoning='low' NOT SUPPORTED on gpt-5.1-chat (expected): "
                        + e.getMessage().substring(0, Math.min(120, e.getMessage().length())));
            }
        }

        @Test
        @DisplayName("reasoning='medium' — model should reason and respond")
        void testReasoningMedium() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-openai-reasoning-medium", "What is the square root of 144? Answer with just the number.")
                    .get(90, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.contains("12"), "Response should contain '12': " + content);

            System.out.println("✅ OpenAI reasoning='medium': " + content.trim());
        }

        @Test
        @DisplayName("reasoning='high' — NOT SUPPORTED on gpt-5.1-chat (only 'medium' allowed)")
        void testReasoningHigh() {
            // gpt-5.1-chat-2025-11-13 only supports reasoning.effort='medium'
            try {
                AgentResult result = agentService
                        .requestAgent("test-openai-reasoning-high",
                                "A bat and a ball cost $1.10 in total. The bat costs $1.00 more than the ball. How much does the ball cost? Answer with just the amount.")
                        .get(120, TimeUnit.SECONDS);

                // If it works, great — model may have been updated to support 'high'
                assertNotNull(result);
                System.out.println("✅ OpenAI reasoning='high' SUPPORTED: " + result.getContent().trim());
            } catch (Exception e) {
                assertTrue(e.getMessage().contains("Unsupported value"),
                        "Should be 'Unsupported value' error: " + e.getMessage());
                System.out.println("⚠️ OpenAI reasoning='high' NOT SUPPORTED on gpt-5.1-chat (expected): "
                        + e.getMessage().substring(0, Math.min(120, e.getMessage().length())));
            }
        }

        @Test
        @DisplayName("reasoning='enabled' (maps to medium) — model should reason and respond")
        void testReasoningEnabled() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-openai-reasoning-enabled", "What is 7 * 8? Answer with just the number.")
                    .get(90, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.contains("56"), "Response should contain '56': " + content);

            System.out.println("✅ OpenAI reasoning='enabled': " + content.trim());
        }
    }

    // ==================== AZURE ANTHROPIC (CLAUDE) THINKING TESTS ====================

    @Nested
    @DisplayName("Azure Anthropic — Thinking Modes (claude-sonnet-4-5)")
    class AzureAnthropicThinkingTests {

        @Test
        @DisplayName("thinking=null (default disabled) — should respond normally")
        void testThinkingNull() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-claude-reasoning-none", "What is 2+2? Answer with just the number.")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.contains("4"), "Response should contain '4': " + content);

            System.out.println("✅ Claude thinking=null (disabled): " + content.trim());
        }

        @Test
        @DisplayName("thinking='none' (explicit disabled) — should respond normally")
        void testThinkingNoneExplicit() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-claude-reasoning-none-explicit", "What is 3+3? Answer with just the number.")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.contains("6"), "Response should contain '6': " + content);

            System.out.println("✅ Claude thinking='none': " + content.trim());
        }

        @Test
        @DisplayName("thinking='low' — should think and respond")
        void testThinkingLow() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-claude-reasoning-low", "What is 15 * 13? Answer with just the number.")
                    .get(90, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content, "Content should not be null — thinking enabled with sufficient budget");
            assertTrue(content.contains("195"), "Response should contain '195': " + content);

            System.out.println("✅ Claude thinking='low': " + content.trim());
        }

        @Test
        @DisplayName("thinking='medium' — should think and respond")
        void testThinkingMedium() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-claude-reasoning-medium", "What is the square root of 144? Answer with just the number.")
                    .get(90, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.contains("12"), "Response should contain '12': " + content);

            System.out.println("✅ Claude thinking='medium': " + content.trim());
        }

        @Test
        @DisplayName("thinking='high' — should think deeply and respond")
        void testThinkingHigh() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-claude-reasoning-high",
                            "A bat and a ball cost $1.10 in total. The bat costs $1.00 more than the ball. How much does the ball cost? Answer with just the amount.")
                    .get(120, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.contains("0.05") || content.contains("5 cents") || content.contains("$0.05"),
                    "Response should contain '$0.05' or '5 cents': " + content);

            System.out.println("✅ Claude thinking='high': " + content.trim());
        }

        @Test
        @DisplayName("thinking='enabled' — should think and respond")
        void testThinkingEnabled() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-claude-reasoning-enabled", "What is 7 * 8? Answer with just the number.")
                    .get(90, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.contains("56"), "Response should contain '56': " + content);

            System.out.println("✅ Claude thinking='enabled': " + content.trim());
        }
    }

    // ==================== CRITICAL REGRESSION TEST ====================

    @Nested
    @DisplayName("Regression — Low maxTokens with reasoning disabled (the original bug)")
    class RegressionTests {

        @Test
        @DisplayName("GPT-5.1 with maxTokens=50 and reasoning=none should NOT crash")
        void testLowTokensNoReasoning() throws Exception {
            // This is the exact scenario that was crashing before:
            // gpt-5.1-chat with maxTokens=50 and no reasoning config → model reasoned by default
            // → consumed all 50 tokens on reasoning → no message block → parser crash
            AgentResult result = agentService
                    .requestAgent("test-openai-reasoning-none", "Say YES.")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content, "Should have text content — reasoning disabled, all tokens for output");
            assertFalse(content.isEmpty(), "Response should not be empty");

            System.out.println("✅ REGRESSION: GPT-5.1 maxTokens=50 + reasoning=none works: " + content.trim());
        }

        @Test
        @DisplayName("GPT-5.1 with maxTokens=50 and explicit reasoning='none' should NOT crash")
        void testLowTokensExplicitNone() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-openai-reasoning-none-explicit", "Say NO.")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertFalse(content.isEmpty());

            System.out.println("✅ REGRESSION: GPT-5.1 maxTokens=50 + reasoning='none' works: " + content.trim());
        }

        @Test
        @DisplayName("requestModel: GPT-5.1 maxTokens=50, no reasoningEffort, reasoning prompt → should get clear error")
        void testRequestModelLowTokensDefaultReasoning() throws Exception {
            ModelRequestOptions options = ModelRequestOptions.builder()
                    .maxTokens(50)
                    .build();

            try {
                AgentResult result = agentService
                        .requestModel("gpt-5.1-chat", "Solve this step by step showing all work: A farmer has 3 fields. Field A produces 2.7 tons/hectare over 12.5 hectares. Field B produces 3.1 tons/hectare over 8.3 hectares. Field C produces 1.9 tons/hectare over 15.7 hectares. Calculate the total production, the weighted average yield per hectare, and the percentage contribution of each field to the total. Show every calculation step.", options)
                        .get(60, TimeUnit.SECONDS);

                // If it succeeds, that's fine too (model might fit answer in 50 tokens)
                assertNotNull(result);
                System.out.println("✅ requestModel maxTokens=50 default reasoning succeeded: " + result.getContent().trim());
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                // We expect the clear reasoning-related error, NOT a parsing crash
                assertTrue(msg.contains("reasoning") || msg.contains("Output contains"),
                        "Should get clear reasoning-related error, got: " + msg);
                System.out.println("✅ requestModel maxTokens=50 default reasoning got expected error: "
                        + msg.substring(0, Math.min(200, msg.length())));
            }
        }
    }

}
