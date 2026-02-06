package io.github.yannfavinleveque.agentic.integration;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import io.github.yannfavinleveque.agentic.agent.model.FunctionConfig;
import io.github.yannfavinleveque.agentic.agent.model.Message;
import io.github.yannfavinleveque.agentic.agent.service.AgentService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for the V2 Stateless API.
 * <p>
 * These tests make REAL API calls and require valid API keys. They are disabled by default and
 * should only be run manually.
 * </p>
 * <p>
 * To run these tests:
 * </p>
 * <ol>
 * <li>Set environment variable OPENAI_INSTANCES with your instance configuration</li>
 * <li>Set environment variable RUN_INTEGRATION_TESTS=true</li>
 * <li>Run: mvn test -Dtest=V2ApiIntegrationTest</li>
 * </ol>
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class V2ApiIntegrationTest {

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

        // Register test agents programmatically
        registerTestAgents();
    }

    private void registerTestAgents() {
        // ===== GPT-4o agents =====
        agentService.registerAgent(Agent.builder()
                .id("test-gpt4o-simple")
                .name("Test GPT-4o Simple")
                .model("gpt-4o")
                .instructions("You are a helpful assistant. Answer briefly and concisely.")
                .build());

        agentService.registerAgent(Agent.builder()
                .id("test-gpt4o-websearch")
                .name("Test GPT-4o WebSearch")
                .model("gpt-4o")
                .instructions(
                        "You are a helpful assistant with web search. Use web search to find current information.")
                .webSearch(true)
                .build());

        // ===== GPT-5.1 agents =====
        agentService.registerAgent(Agent.builder()
                .id("test-gpt51-simple")
                .name("Test GPT-5.1 Simple")
                .model("gpt-5.1-chat")
                .instructions("You are a helpful assistant. Answer briefly and concisely.")
                .build());

        agentService.registerAgent(Agent.builder()
                .id("test-gpt51-websearch")
                .name("Test GPT-5.1 WebSearch")
                .model("gpt-5.1-chat")
                .instructions(
                        "You are a helpful assistant with web search. Use web search to find current information.")
                .webSearch(true)
                .build());

        // ===== GPT-5.2 agents =====
        agentService.registerAgent(Agent.builder()
                .id("test-gpt52-simple")
                .name("Test GPT-5.2 Simple")
                .model("gpt-5.2-chat")
                .instructions("You are a helpful assistant. Answer briefly and concisely.")
                .build());

        // ===== GPT-5 (non-chat) agents =====
        agentService.registerAgent(Agent.builder()
                .id("test-gpt5-simple")
                .name("Test GPT-5 Simple")
                .model("gpt-5-chat")
                .instructions("You are a helpful assistant. Answer briefly and concisely.")
                .build());

        agentService.registerAgent(Agent.builder()
                .id("test-gpt5-websearch")
                .name("Test GPT-5 WebSearch")
                .model("gpt-5-chat")
                .instructions(
                        "You are a helpful assistant with web search. Use web search to find current information.")
                .webSearch(true)
                .build());

        agentService.registerAgent(Agent.builder()
                .id("test-gpt5-codeinterpreter")
                .name("Test GPT-5 CodeInterpreter")
                .model("gpt-5-chat")
                .instructions("You are a helpful assistant with code execution capabilities.")
                .codeInterpreter(true)
                .build());

        // ===== Claude agents =====
        agentService.registerAgent(Agent.builder()
                .id("test-claude-sonnet-simple")
                .name("Test Claude Sonnet Simple")
                .model("claude-sonnet-4-5")
                .instructions("You are a helpful assistant. Answer briefly and concisely.")
                .build());

        agentService.registerAgent(Agent.builder()
                .id("test-claude-haiku-simple")
                .name("Test Claude Haiku Simple")
                .model("claude-haiku-4-5")
                .instructions("You are a helpful assistant. Answer briefly and concisely.")
                .build());

        agentService.registerAgent(Agent.builder()
                .id("test-claude-opus-simple")
                .name("Test Claude Opus Simple")
                .model("claude-opus-4-5")
                .instructions("You are a helpful assistant. Answer briefly and concisely.")
                .build());

        agentService.registerAgent(Agent.builder()
                .id("test-claude-websearch")
                .name("Test Claude WebSearch")
                .model("claude-haiku-4-5")
                .instructions(
                        "You are a helpful assistant with web search. Use web search to find current information.")
                .webSearch(true)
                .build());

        // ===== Tools test agents =====

        // GPT-4o with code interpreter
        agentService.registerAgent(Agent.builder()
                .id("test-gpt4o-codeinterpreter")
                .name("Test GPT-4o CodeInterpreter")
                .model("gpt-4o")
                .instructions("You are a helpful assistant with code execution capabilities. Execute code when asked.")
                .codeInterpreter(true)
                .build());

        // GPT-5.1 with code interpreter
        agentService.registerAgent(Agent.builder()
                .id("test-gpt51-codeinterpreter")
                .name("Test GPT-5.1 CodeInterpreter")
                .model("gpt-5.1-chat")
                .instructions("You are a helpful assistant with code execution capabilities. Execute code when asked.")
                .codeInterpreter(true)
                .build());

        // GPT-4o with multiple tools
        agentService.registerAgent(Agent.builder()
                .id("test-gpt4o-multitools")
                .name("Test GPT-4o MultiTools")
                .model("gpt-4o")
                .instructions("You are a helpful assistant with web search and code execution capabilities.")
                .webSearch(true)
                .codeInterpreter(true)
                .build());

        // Claude Sonnet with web search
        agentService.registerAgent(Agent.builder()
                .id("test-claude-sonnet-websearch")
                .name("Test Claude Sonnet WebSearch")
                .model("claude-sonnet-4-5")
                .instructions(
                        "You are a helpful assistant with web search. Use web search to find current information.")
                .webSearch(true)
                .build());

        // Claude Opus with web search
        agentService.registerAgent(Agent.builder()
                .id("test-claude-opus-websearch")
                .name("Test Claude Opus WebSearch")
                .model("claude-opus-4-5")
                .instructions(
                        "You are a helpful assistant with web search. Use web search to find current information.")
                .webSearch(true)
                .build());

        // Custom function for testing
        FunctionConfig weatherFunc = FunctionConfig.builder()
                .name("get_weather")
                .description("Get the current weather for a location")
                .build();

        // GPT-4o with custom function
        agentService.registerAgent(Agent.builder()
                .id("test-gpt4o-customfunc")
                .name("Test GPT-4o Custom Function")
                .model("gpt-4o")
                .instructions("You are a helpful assistant. When asked about weather, use the get_weather function.")
                .functions(List.of(weatherFunc))
                .build());

        // GPT-5.1 with custom function
        agentService.registerAgent(Agent.builder()
                .id("test-gpt51-customfunc")
                .name("Test GPT-5.1 Custom Function")
                .model("gpt-5.1-chat")
                .instructions("You are a helpful assistant. When asked about weather, use the get_weather function.")
                .functions(List.of(weatherFunc))
                .build());

        // GPT-5.2 with custom function
        agentService.registerAgent(Agent.builder()
                .id("test-gpt52-customfunc")
                .name("Test GPT-5.2 Custom Function")
                .model("gpt-5.2-chat")
                .instructions("You are a helpful assistant. When asked about weather, use the get_weather function.")
                .functions(List.of(weatherFunc))
                .build());

        // Claude Sonnet with custom function
        agentService.registerAgent(Agent.builder()
                .id("test-claude-customfunc")
                .name("Test Claude Custom Function")
                .model("claude-sonnet-4-5")
                .instructions("You are a helpful assistant. When asked about weather, use the get_weather function.")
                .functions(List.of(weatherFunc))
                .build());
    }

    // ==================== GPT-4o TESTS ====================

    @Nested
    @DisplayName("GPT-4o Tests")
    class GPT4oTests {

        @Test
        @DisplayName("Simple single-turn request")
        void testSimpleSingleTurnRequest() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-gpt4o-simple", "What is 2+2? Answer with just the number.")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.contains("4"), "Response should contain '4': " + content);

            System.out.println("✅ GPT-4o simple request: " + content);
        }

        @Test
        @DisplayName("Multi-turn conversation with history")
        void testMultiTurnConversation() throws Exception {
            List<Message> history = new ArrayList<>();

            // First turn
            AgentResult result1 = agentService.requestAgent("test-gpt4o-simple", "My name is Alice.")
                    .get(60, TimeUnit.SECONDS);
            assertNotNull(result1);

            history.add(Message.user("My name is Alice."));
            history.add(Message.assistant(result1.getContent()));

            // Second turn - should remember the name
            AgentResult result2 = agentService.requestAgent("test-gpt4o-simple", "What is my name?", history)
                    .get(60, TimeUnit.SECONDS);
            assertNotNull(result2);

            String content = result2.getContent();
            assertTrue(content.toLowerCase().contains("alice"),
                    "Response should remember 'Alice': " + content);

            System.out.println("✅ GPT-4o multi-turn: " + content);
        }

    }

    // ==================== GPT-5.1 TESTS ====================

    @Nested
    @DisplayName("GPT-5.1 Tests")
    class GPT51Tests {

        @Test
        @DisplayName("Simple single-turn request")
        void testSimpleSingleTurnRequest() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-gpt51-simple", "What is 5+5? Answer with just the number.")
                    .get(90, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.contains("10"), "Response should contain '10': " + content);

            System.out.println("✅ GPT-5.1 simple request: " + content);
        }

        @Test
        @DisplayName("Multi-turn conversation with history")
        void testMultiTurnConversation() throws Exception {
            List<Message> history = new ArrayList<>();

            // First turn
            AgentResult result1 = agentService.requestAgent("test-gpt51-simple", "Remember this number: 42.")
                    .get(90, TimeUnit.SECONDS);
            assertNotNull(result1);

            history.add(Message.user("Remember this number: 42."));
            history.add(Message.assistant(result1.getContent()));

            // Second turn - should remember the number
            AgentResult result2 = agentService
                    .requestAgent("test-gpt51-simple", "What number did I ask you to remember?", history)
                    .get(90, TimeUnit.SECONDS);
            assertNotNull(result2);

            String content = result2.getContent();
            assertTrue(content.contains("42"),
                    "Response should remember '42': " + content);

            System.out.println("✅ GPT-5.1 multi-turn: " + content);
        }

        @Test
        @DisplayName("Complex reasoning task")
        void testComplexReasoning() throws Exception {
            AgentResult result = agentService.requestAgent("test-gpt51-simple",
                    "If a train leaves Paris at 9:00 AM traveling at 200 km/h, and another train leaves Lyon (400 km away) at 9:30 AM traveling at 250 km/h towards Paris, at what time will they meet? Just answer with the time.")
                    .get(120, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertFalse(content.isEmpty(), "Response should not be empty");

            System.out.println("✅ GPT-5.1 complex reasoning: " + content);
        }

    }

    // ==================== GPT-5.2 TESTS ====================

    @Nested
    @DisplayName("GPT-5.2 Tests")
    class GPT52Tests {

        @Test
        @DisplayName("Simple single-turn request")
        void testSimpleSingleTurnRequest() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-gpt52-simple", "What is 7*8? Answer with just the number.")
                    .get(90, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.contains("56"), "Response should contain '56': " + content);

            System.out.println("✅ GPT-5.2 simple request: " + content);
        }

        @Test
        @DisplayName("Extended reasoning")
        void testExtendedReasoning() throws Exception {
            AgentResult result = agentService.requestAgent("test-gpt52-simple",
                    "Explain in one sentence why the sky appears blue.")
                    .get(120, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(
                    content.toLowerCase().contains("scatter") || content.toLowerCase().contains("light")
                            || content.toLowerCase().contains("rayleigh"),
                    "Response should mention scattering or light: " + content);

            System.out.println("✅ GPT-5.2 reasoning: " + content);
        }

    }

    // ==================== GPT-5 (non-chat variant) TESTS ====================

    @Nested
    @DisplayName("GPT-5 Tests (gpt-5-chat model)")
    class GPT5Tests {

        @Test
        @DisplayName("Simple single-turn request")
        void testSimpleSingleTurnRequest() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-gpt5-simple", "What is 6*7? Answer with just the number.")
                    .get(90, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.contains("42"), "Response should contain '42': " + content);

            System.out.println("✅ GPT-5 simple request: " + content);
        }

        @Test
        @DisplayName("GPT-5 with web_search tool")
        void testGpt5WebSearch() {
            try {
                AgentResult result = agentService.requestAgent("test-gpt5-websearch",
                        "What is the weather like today? Use web search.")
                        .get(120, TimeUnit.SECONDS);

                assertNotNull(result);
                String content = result.getContent();
                assertNotNull(content);
                assertFalse(content.isEmpty());

                System.out.println("✅ GPT-5 web_search SUCCESS: " +
                        content.substring(0, Math.min(200, content.length())));
            } catch (Exception e) {
                // Check if it's a "not supported" error or actual failure
                String msg = e.getMessage();
                if (msg != null && msg.contains("400")) {
                    System.out.println("⚠️ GPT-5 web_search NOT SUPPORTED on Azure: " +
                            msg.substring(0, Math.min(100, msg.length())));
                    assertTrue(msg.contains("400"), "Should be HTTP 400 error");
                } else {
                    System.out.println("❌ GPT-5 web_search FAILED: " + msg);
                    fail("GPT-5 web search failed: " + msg);
                }
            }
        }

        @Test
        @DisplayName("GPT-5 with code_interpreter tool")
        void testGpt5CodeInterpreter() {
            try {
                AgentResult result = agentService.requestAgent("test-gpt5-codeinterpreter",
                        "Calculate factorial of 5 using code.")
                        .get(120, TimeUnit.SECONDS);

                assertNotNull(result);
                String content = result.getContent();
                assertNotNull(content);

                System.out.println("✅ GPT-5 code_interpreter SUCCESS: " +
                        content.substring(0, Math.min(200, content.length())));
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("400")) {
                    System.out.println("⚠️ GPT-5 code_interpreter NOT SUPPORTED on Azure: " +
                            msg.substring(0, Math.min(100, msg.length())));
                    assertTrue(msg.contains("400"), "Should be HTTP 400 error");
                } else {
                    System.out.println("❌ GPT-5 code_interpreter FAILED: " + msg);
                    fail("GPT-5 code interpreter failed: " + msg);
                }
            }
        }

    }

    // ==================== CLAUDE TESTS ====================

    @Nested
    @DisplayName("Claude Messages API Tests")
    class ClaudeTests {

        @Test
        @DisplayName("Claude Sonnet simple request")
        void testClaudeSonnetSimple() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-claude-sonnet-simple", "What is 3+3? Answer with just the number.")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.contains("6"), "Response should contain '6': " + content);

            System.out.println("✅ Claude Sonnet simple: " + content);
        }

        @Test
        @DisplayName("Claude Haiku simple request")
        void testClaudeHaikuSimple() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-claude-haiku-simple", "What is 4*4? Answer with just the number.")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.contains("16"), "Response should contain '16': " + content);

            System.out.println("✅ Claude Haiku simple: " + content);
        }

        @Test
        @DisplayName("Claude Opus simple request")
        void testClaudeOpusSimple() throws Exception {
            AgentResult result = agentService
                    .requestAgent("test-claude-opus-simple", "What is 9*9? Answer with just the number.")
                    .get(120, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.contains("81"), "Response should contain '81': " + content);

            System.out.println("✅ Claude Opus simple: " + content);
        }

        @Test
        @DisplayName("Claude multi-turn conversation")
        void testClaudeMultiTurn() throws Exception {
            List<Message> history = new ArrayList<>();

            // First turn
            AgentResult result1 = agentService.requestAgent("test-claude-sonnet-simple", "My favorite color is blue.")
                    .get(60, TimeUnit.SECONDS);
            assertNotNull(result1);

            history.add(Message.user("My favorite color is blue."));
            history.add(Message.assistant(result1.getContent()));

            // Second turn - should remember the color
            AgentResult result2 = agentService
                    .requestAgent("test-claude-sonnet-simple", "What is my favorite color?", history)
                    .get(60, TimeUnit.SECONDS);
            assertNotNull(result2);

            String content = result2.getContent();
            assertTrue(content.toLowerCase().contains("blue"),
                    "Response should remember 'blue': " + content);

            System.out.println("✅ Claude multi-turn: " + content);
        }

    }

    // ==================== PARALLEL REQUESTS ====================

    @Nested
    @DisplayName("Parallel Request Tests")
    class ParallelTests {

        @Test
        @DisplayName("Multiple parallel GPT-4o requests")
        void testParallelGPT4oRequests() throws Exception {
            List<CompletableFuture<AgentResult>> futures = new ArrayList<>();

            // Launch 3 parallel requests
            for (int i = 0; i < 3; i++) {
                final int num = i + 1;
                futures.add(agentService.requestAgent("test-gpt4o-simple",
                        "What is " + num + " + " + num + "? Answer with just the number."));
            }

            // Wait for all
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(120, TimeUnit.SECONDS);

            // Verify all succeeded
            for (int i = 0; i < 3; i++) {
                AgentResult result = futures.get(i).get();
                assertNotNull(result);
                assertNotNull(result.getContent());
                System.out.println("✅ Parallel GPT-4o " + (i + 1) + ": " + result.getContent().trim());
            }
        }

        @Test
        @DisplayName("Mixed model parallel requests (GPT-4o, GPT-5.1, Claude)")
        void testMixedModelParallelRequests() throws Exception {
            CompletableFuture<AgentResult> gpt4oRequest = agentService.requestAgent("test-gpt4o-simple",
                    "Say 'GPT4O' and nothing else.");
            CompletableFuture<AgentResult> gpt51Request = agentService.requestAgent("test-gpt51-simple",
                    "Say 'GPT51' and nothing else.");
            CompletableFuture<AgentResult> claudeRequest = agentService.requestAgent("test-claude-sonnet-simple",
                    "Say 'CLAUDE' and nothing else.");

            // Wait for all
            CompletableFuture.allOf(gpt4oRequest, gpt51Request, claudeRequest)
                    .get(180, TimeUnit.SECONDS);

            AgentResult gpt4oResult = gpt4oRequest.get();
            AgentResult gpt51Result = gpt51Request.get();
            AgentResult claudeResult = claudeRequest.get();

            assertNotNull(gpt4oResult);
            assertNotNull(gpt51Result);
            assertNotNull(claudeResult);

            System.out.println("✅ GPT-4o parallel: " + gpt4oResult.getContent().trim());
            System.out.println("✅ GPT-5.1 parallel: " + gpt51Result.getContent().trim());
            System.out.println("✅ Claude parallel: " + claudeResult.getContent().trim());
        }

        @Test
        @DisplayName("All Claude variants parallel")
        void testAllClaudeVariantsParallel() throws Exception {
            CompletableFuture<AgentResult> haikuRequest = agentService.requestAgent("test-claude-haiku-simple",
                    "Say 'HAIKU' and nothing else.");
            CompletableFuture<AgentResult> sonnetRequest = agentService.requestAgent("test-claude-sonnet-simple",
                    "Say 'SONNET' and nothing else.");
            CompletableFuture<AgentResult> opusRequest = agentService.requestAgent("test-claude-opus-simple",
                    "Say 'OPUS' and nothing else.");

            // Wait for all
            CompletableFuture.allOf(haikuRequest, sonnetRequest, opusRequest)
                    .get(180, TimeUnit.SECONDS);

            assertNotNull(haikuRequest.get());
            assertNotNull(sonnetRequest.get());
            assertNotNull(opusRequest.get());

            System.out.println("✅ Claude Haiku: " + haikuRequest.get().getContent().trim());
            System.out.println("✅ Claude Sonnet: " + sonnetRequest.get().getContent().trim());
            System.out.println("✅ Claude Opus: " + opusRequest.get().getContent().trim());
        }

    }

    // ==================== TOOLS INTEGRATION TESTS ====================

    /**
     * NOTE: Azure OpenAI does NOT support web_search_preview or code_interpreter tools. These tools are
     * only available on OpenAI direct API, not on Azure OpenAI. These tests document this limitation
     * and are marked as expected to fail.
     */
    @Nested
    @DisplayName("Tools Integration Tests - Azure OpenAI (Limited Support)")
    class AzureOpenAIToolsTests {

        @Test
        @DisplayName("GPT-4o with web_search_preview tool - NOT SUPPORTED on Azure")
        void testGpt4oWebSearch() {
            try {
                AgentResult result = agentService.requestAgent("test-gpt4o-websearch",
                        "What is the current weather in Paris today? Use web search to find out.")
                        .get(120, TimeUnit.SECONDS);

                // If we get here, Azure now supports web_search - great!
                assertNotNull(result);
                System.out.println("✅ GPT-4o web_search SUCCESS (unexpected!): " +
                        result.getContent().substring(0, Math.min(200, result.getContent().length())));
            } catch (Exception e) {
                // Expected - Azure OpenAI does not support web_search_preview
                System.out.println("⚠️ GPT-4o web_search NOT SUPPORTED on Azure OpenAI (expected): " +
                        e.getMessage().substring(0, Math.min(100, e.getMessage().length())));
                assertTrue(e.getMessage().contains("400"), "Should be HTTP 400 error");
            }
        }

        @Test
        @DisplayName("GPT-5.1 with web_search_preview tool - NOT SUPPORTED on Azure")
        void testGpt51WebSearch() {
            try {
                AgentResult result = agentService.requestAgent("test-gpt51-websearch",
                        "What are the latest tech news today? Use web search.")
                        .get(120, TimeUnit.SECONDS);

                // If we get here, Azure now supports web_search - great!
                assertNotNull(result);
                System.out.println("✅ GPT-5.1 web_search SUCCESS (unexpected!): " +
                        result.getContent().substring(0, Math.min(200, result.getContent().length())));
            } catch (Exception e) {
                // Expected - Azure OpenAI does not support web_search_preview
                System.out.println("⚠️ GPT-5.1 web_search NOT SUPPORTED on Azure OpenAI (expected): " +
                        e.getMessage().substring(0, Math.min(100, e.getMessage().length())));
                assertTrue(e.getMessage().contains("400"), "Should be HTTP 400 error");
            }
        }

        @Test
        @DisplayName("GPT-4o with code_interpreter tool - NOT SUPPORTED on Azure")
        void testGpt4oCodeInterpreter() {
            try {
                AgentResult result = agentService.requestAgent("test-gpt4o-codeinterpreter",
                        "Calculate the factorial of 10 using code.")
                        .get(120, TimeUnit.SECONDS);

                // If we get here, Azure now supports code_interpreter - great!
                assertNotNull(result);
                System.out.println("✅ GPT-4o code_interpreter SUCCESS (unexpected!): " +
                        result.getContent().substring(0, Math.min(200, result.getContent().length())));
            } catch (Exception e) {
                // Expected - Azure OpenAI does not support code_interpreter
                System.out.println("⚠️ GPT-4o code_interpreter NOT SUPPORTED on Azure OpenAI (expected): " +
                        e.getMessage().substring(0, Math.min(100, e.getMessage().length())));
                assertTrue(e.getMessage().contains("400"), "Should be HTTP 400 error");
            }
        }

        @Test
        @DisplayName("GPT-5.1 with code_interpreter tool - NOT SUPPORTED on Azure")
        void testGpt51CodeInterpreter() {
            try {
                AgentResult result = agentService.requestAgent("test-gpt51-codeinterpreter",
                        "Write and execute Python code to calculate the sum of numbers from 1 to 100.")
                        .get(120, TimeUnit.SECONDS);

                // If we get here, Azure now supports code_interpreter - great!
                assertNotNull(result);
                System.out.println("✅ GPT-5.1 code_interpreter SUCCESS (unexpected!): " +
                        result.getContent().substring(0, Math.min(200, result.getContent().length())));
            } catch (Exception e) {
                // Expected - Azure OpenAI does not support code_interpreter
                System.out.println("⚠️ GPT-5.1 code_interpreter NOT SUPPORTED on Azure OpenAI (expected): " +
                        e.getMessage().substring(0, Math.min(100, e.getMessage().length())));
                assertTrue(e.getMessage().contains("400"), "Should be HTTP 400 error");
            }
        }

        @Test
        @DisplayName("GPT-4o with multiple tools - NOT SUPPORTED on Azure")
        void testGpt4oMultipleTools() {
            try {
                AgentResult result = agentService.requestAgent("test-gpt4o-multitools",
                        "Search the web for the current population of France and then calculate 10% of it.")
                        .get(180, TimeUnit.SECONDS);

                // If we get here, Azure now supports these tools - great!
                assertNotNull(result);
                System.out.println("✅ GPT-4o multi-tools SUCCESS (unexpected!): " +
                        result.getContent().substring(0, Math.min(200, result.getContent().length())));
            } catch (Exception e) {
                // Expected - Azure OpenAI does not support web_search_preview or code_interpreter
                System.out.println("⚠️ GPT-4o multi-tools NOT SUPPORTED on Azure OpenAI (expected): " +
                        e.getMessage().substring(0, Math.min(100, e.getMessage().length())));
                assertTrue(e.getMessage().contains("400"), "Should be HTTP 400 error");
            }
        }

    }

    @Nested
    @DisplayName("Tools Integration Tests - Azure Anthropic (Claude)")
    class AzureAnthropicToolsTests {

        @Test
        @DisplayName("Claude Haiku with web_search tool")
        void testClaudeHaikuWebSearch() {
            try {
                AgentResult result = agentService.requestAgent("test-claude-websearch",
                        "What is the current weather in London? Use web search to find out.")
                        .get(120, TimeUnit.SECONDS);

                assertNotNull(result);
                String content = result.getContent();
                assertNotNull(content);
                assertFalse(content.isEmpty());

                System.out.println(
                        "✅ Claude Haiku web_search SUCCESS: " + content.substring(0, Math.min(200, content.length())));
            } catch (Exception e) {
                System.out.println("❌ Claude Haiku web_search FAILED: " + e.getMessage());
                fail("Claude Haiku web search failed: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("Claude Sonnet with web_search tool")
        void testClaudeSonnetWebSearch() {
            try {
                AgentResult result = agentService.requestAgent("test-claude-sonnet-websearch",
                        "Search the web for the latest news about AI today.")
                        .get(120, TimeUnit.SECONDS);

                assertNotNull(result);
                String content = result.getContent();
                assertNotNull(content);
                assertFalse(content.isEmpty());

                System.out.println(
                        "✅ Claude Sonnet web_search SUCCESS: " + content.substring(0, Math.min(200, content.length())));
            } catch (Exception e) {
                System.out.println("❌ Claude Sonnet web_search FAILED: " + e.getMessage());
                fail("Claude Sonnet web search failed: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("Claude Opus with web_search tool")
        void testClaudeOpusWebSearch() {
            try {
                AgentResult result = agentService.requestAgent("test-claude-opus-websearch",
                        "Use web search to find what major events happened today in the world.")
                        .get(180, TimeUnit.SECONDS);

                assertNotNull(result);
                String content = result.getContent();
                assertNotNull(content);
                assertFalse(content.isEmpty());

                System.out.println(
                        "✅ Claude Opus web_search SUCCESS: " + content.substring(0, Math.min(200, content.length())));
            } catch (Exception e) {
                System.out.println("❌ Claude Opus web_search FAILED: " + e.getMessage());
                fail("Claude Opus web search failed: " + e.getMessage());
            }
        }

    }

    @Nested
    @DisplayName("Custom Function Tools Tests")
    class CustomFunctionToolsTests {

        @Test
        @DisplayName("GPT-4o with custom function tool")
        void testGpt4oCustomFunction() {
            try {
                AgentResult result = agentService.requestAgent("test-gpt4o-customfunc",
                        "Call the get_weather function for Paris.")
                        .get(90, TimeUnit.SECONDS);

                assertNotNull(result);
                String content = result.getContent();
                assertNotNull(content);

                System.out.println(
                        "✅ GPT-4o custom function SUCCESS: " + content.substring(0, Math.min(200, content.length())));
            } catch (Exception e) {
                System.out.println("❌ GPT-4o custom function FAILED: " + e.getMessage());
                fail("GPT-4o custom function failed: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("Claude Sonnet with custom function tool")
        void testClaudeSonnetCustomFunction() {
            try {
                AgentResult result = agentService.requestAgent("test-claude-customfunc",
                        "Call the get_weather function for London.")
                        .get(90, TimeUnit.SECONDS);

                assertNotNull(result);
                String content = result.getContent();
                assertNotNull(content);

                System.out.println("✅ Claude Sonnet custom function SUCCESS: "
                        + content.substring(0, Math.min(200, content.length())));
            } catch (Exception e) {
                System.out.println("❌ Claude Sonnet custom function FAILED: " + e.getMessage());
                fail("Claude Sonnet custom function failed: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("GPT-5.1 with custom function tool")
        void testGpt51CustomFunction() {
            try {
                AgentResult result = agentService.requestAgent("test-gpt51-customfunc",
                        "Call the get_weather function for Tokyo.")
                        .get(90, TimeUnit.SECONDS);

                assertNotNull(result);
                String content = result.getContent();
                assertNotNull(content);

                System.out.println("✅ GPT-5.1 custom function SUCCESS: "
                        + content.substring(0, Math.min(200, content.length())));
            } catch (Exception e) {
                System.out.println("❌ GPT-5.1 custom function FAILED: " + e.getMessage());
                fail("GPT-5.1 custom function failed: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("GPT-5.2 with custom function tool")
        void testGpt52CustomFunction() {
            try {
                AgentResult result = agentService.requestAgent("test-gpt52-customfunc",
                        "Call the get_weather function for Berlin.")
                        .get(90, TimeUnit.SECONDS);

                assertNotNull(result);
                String content = result.getContent();
                assertNotNull(content);

                System.out.println("✅ GPT-5.2 custom function SUCCESS: "
                        + content.substring(0, Math.min(200, content.length())));
            } catch (Exception e) {
                System.out.println("❌ GPT-5.2 custom function FAILED: " + e.getMessage());
                fail("GPT-5.2 custom function failed: " + e.getMessage());
            }
        }

    }

    // ==================== ERROR HANDLING ====================

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Unknown agent ID throws exception")
        void testUnknownAgentId() {
            assertThrows(Exception.class, () -> {
                agentService.requestAgent("non-existent-agent", "Hello")
                        .get(10, TimeUnit.SECONDS);
            });
            System.out.println("✅ Unknown agent properly throws exception");
        }

    }

    // ==================== EXTENDED CONVERSATION TESTS ====================

    @Test
    @DisplayName("Extended conversation with GPT-5.1 (5 turns)")
    void testExtendedConversationGPT51() throws Exception {
        List<Message> history = new ArrayList<>();

        String[] questions = {
                "Let's play a number game. I'm thinking of the number 7.",
                "Add 3 to that number. What is the result?",
                "Now multiply that by 2. What do you get?",
                "Subtract 5 from the result. What is it now?",
                "Finally, what was my original number?"
        };

        for (String question : questions) {
            AgentResult result = agentService.requestAgent("test-gpt51-simple", question, history)
                    .get(90, TimeUnit.SECONDS);

            assertNotNull(result);
            assertNotNull(result.getContent());

            history.add(Message.user(question));
            history.add(Message.assistant(result.getContent()));

            System.out.println("Turn " + (history.size() / 2) + ": " + result.getContent().trim());
        }

        // Final answer should mention 7
        String lastResponse = history.get(history.size() - 1).getContent();
        assertTrue(lastResponse.contains("7"), "Should remember original number was 7: " + lastResponse);

        System.out.println("✅ GPT-5.1 extended conversation completed with " + (history.size() / 2) + " turns");
    }

    @Test
    @DisplayName("Extended conversation with Claude (5 turns)")
    void testExtendedConversationClaude() throws Exception {
        List<Message> history = new ArrayList<>();

        String[] questions = {
                "I'm going to tell you about my day. First, I woke up at 7 AM.",
                "Then I had breakfast: eggs and toast.",
                "After that, I went for a 30 minute run.",
                "What time did I wake up this morning?",
                "What did I have for breakfast?"
        };

        for (String question : questions) {
            AgentResult result = agentService.requestAgent("test-claude-sonnet-simple", question, history)
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            assertNotNull(result.getContent());

            history.add(Message.user(question));
            history.add(Message.assistant(result.getContent()));

            System.out.println("Turn " + (history.size() / 2) + ": " + result.getContent().trim());
        }

        // Final answer should mention breakfast items
        String lastResponse = history.get(history.size() - 1).getContent().toLowerCase();
        assertTrue(lastResponse.contains("eggs") || lastResponse.contains("toast"),
                "Should remember breakfast: " + lastResponse);

        System.out.println("✅ Claude extended conversation completed with " + (history.size() / 2) + " turns");
    }

    // ==================== MODEL COMPARISON TEST ====================

    @Test
    @DisplayName("Compare same question across all models")
    void testModelComparison() throws Exception {
        String question = "In one word, what is the capital of France?";

        String[] agents = { "test-gpt4o-simple", "test-gpt51-simple", "test-gpt52-simple",
                "test-claude-sonnet-simple" };
        String[] modelNames = { "GPT-4o", "GPT-5.1", "GPT-5.2", "Claude Sonnet" };

        for (int i = 0; i < agents.length; i++) {
            AgentResult result = agentService.requestAgent(agents[i], question)
                    .get(90, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent();
            assertNotNull(content);
            assertTrue(content.toLowerCase().contains("paris"),
                    modelNames[i] + " should answer 'Paris': " + content);

            System.out.println("✅ " + modelNames[i] + ": " + content.trim());
        }
    }

}
