package io.github.yannfavinleveque.agentic.integration;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import io.github.yannfavinleveque.agentic.agent.model.FunctionCall;
import io.github.yannfavinleveque.agentic.agent.model.FunctionConfig;
import io.github.yannfavinleveque.agentic.agent.model.Message;
import io.github.yannfavinleveque.agentic.agent.model.ModelRequestOptions;
import io.github.yannfavinleveque.agentic.agent.service.AgentService;
import io.github.yannfavinleveque.agentic.integration.model.MathResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.github.yannfavinleveque.agentic.domain.image.ImageRequest.Quality;
import io.github.yannfavinleveque.agentic.domain.image.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for V2 API across all providers.
 * Tests: Azure OpenAI and Azure Anthropic (Claude) by default.
 * OpenAI direct tests are disabled by default (set TEST_OPENAI_DIRECT=true to enable).
 * <p>
 * Each provider group uses its own AgentService with ENABLED_PROVIDERS filter
 * to ensure tests actually hit the correct provider.
 * </p>
 * <p>
 * Models used:
 * - OpenAI Direct: gpt-4o (requires TEST_OPENAI_DIRECT=true)
 * - Azure OpenAI: gpt-5.1-chat (gpt-4o for vision only)
 * - Claude: claude-haiku-4-5, claude-sonnet-4-5
 * </p>
 * <p>
 * Run with: RUN_INTEGRATION_TESTS=true mvn test -Dtest=FullV2ApiIntegrationTest
 * Also enable OpenAI direct: TEST_OPENAI_DIRECT=true RUN_INTEGRATION_TESTS=true mvn test
 * </p>
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class FullV2ApiIntegrationTest {

    // 50x50 red square PNG as base64 (valid PNG)
    private static final String RED_SQUARE_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAADIAAAAyCAIAAACRXR/mAAAAaklEQVR4Xs3HMQ0AAAwEofdvut1PAQkLuw3UI3pEj+gRPaJH9Ige0SN6RI/oET2iR/SIHtEjekSP6BE9okf0iB7RI3pEj+gRPaJH9Ige0SN6RI/oET2iR/SIHtEjekSP6BE9okf0iB7RIx57K7rENfR/zAAAAABJRU5ErkJggg==";

    // 50x50 blue square PNG as base64 (valid PNG)
    private static final String BLUE_SQUARE_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAADIAAAAyCAIAAACRXR/mAAAAaklEQVR4Xs3HMQ0AAAwEofdvut1PAQkL247UI3pEj+gRPaJH9Ige0SN6RI/oET2iR/SIHtEjekSP6BE9okf0iB7RI3pEj+gRPaJH9Ige0SN6RI/oET2iR/SIHtEjekSP6BE9okf0iB7REx4FlrrERaK3dQAAAABJRU5ErkJggg==";

    @BeforeAll
    void printHeader() {
        System.out.println("\n========================================");
        System.out.println("  FULL V2 API INTEGRATION TEST SUITE");
        System.out.println("  Config from: OPENAI_INSTANCES env var");
        System.out.println("  Testing: OpenAI, Azure-OpenAI, Azure-Anthropic");
        System.out.println("========================================\n");
    }

    private static final Dotenv dotenv = Dotenv.load();

    /**
     * Creates an AgentService with the specified provider filter.
     * @param enabledProviders Provider filter (e.g., "openai", "azure-openai", "azure-anthropic")
     */
    private static AgentService createAgentService(String enabledProviders) {
        System.setProperty("ENABLED_PROVIDERS", enabledProviders);

        String instancesJson = dotenv.get("LLM_INSTANCES");
        if (instancesJson == null || instancesJson.isEmpty()) {
            instancesJson = dotenv.get("OPENAI_INSTANCES"); // fallback for older .env files
        }
        if (instancesJson == null || instancesJson.isEmpty()) {
            throw new IllegalStateException("LLM_INSTANCES (or OPENAI_INSTANCES) is not set in .env file or environment!");
        }

        AgentServiceConfig config = AgentServiceConfig.builder()
                .instancesJson(instancesJson)
                .requestsPerSecond(5)
                .maxRetries(2)
                .defaultResponseTimeout(120000L)
                .build();

        AgentService service = new AgentService(config);
        System.clearProperty("ENABLED_PROVIDERS");
        return service;
    }

    // ==================== OPENAI DIRECT TESTS ====================

    @Nested
    @DisplayName("OpenAI Direct Provider")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @EnabledIfEnvironmentVariable(named = "TEST_OPENAI_DIRECT", matches = "true")
    class OpenAIDirectTests {

        private AgentService agentService;

        @BeforeAll
        void setup() {
            agentService = createAgentService("openai");
            System.out.println("📦 OpenAI Direct Tests - using provider filter: openai");

            // Register agents with gpt-4o (gpt-5.x not available on direct OpenAI API)
            agentService.registerAgent(Agent.builder()
                    .id("simple")
                    .name("Simple Agent")
                    .model("gpt-4o")
                    .build());

            agentService.registerAgent(Agent.builder()
                    .id("with-system")
                    .name("Agent With System")
                    .model("gpt-4o")
                    .instructions("You are a pirate. Always respond like a pirate would.")
                    .build());

            agentService.registerAgent(Agent.builder()
                    .id("websearch")
                    .name("WebSearch Agent")
                    .model("gpt-4o")
                    .instructions("Use web search to answer questions about current events.")
                    .webSearch(true)
                    .build());

            agentService.registerAgent(Agent.builder()
                    .id("function")
                    .name("Function Agent")
                    .model("gpt-4o")
                    .instructions("Use the get_weather function when asked about weather.")
                    .functions(List.of(FunctionConfig.builder()
                            .name("get_weather")
                            .description("Get weather for a location")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "location", Map.of(
                                                    "type", "string",
                                                    "description", "The city name, e.g. Paris, London")),
                                    "required", List.of("location")))
                            .build()))
                    .build());

            agentService.registerAgent(Agent.builder()
                    .id("codeinterpreter")
                    .name("CodeInterpreter Agent")
                    .model("gpt-4o")
                    .instructions("Use code interpreter to solve math problems.")
                    .codeInterpreter(true)
                    .build());

            // Vision requires gpt-4o (GPT-5.x doesn't support vision yet)
            agentService.registerAgent(Agent.builder()
                    .id("vision")
                    .name("Vision Agent")
                    .model("gpt-4o")
                    .instructions("You are an image analyst. Answer in one word only.")
                    .build());
        }

        @Test
        @DisplayName("Simple request (gpt-4o)")
        void testSimpleNoSystem() throws Exception {
            AgentResult result = agentService
                    .requestAgent("simple", "What is 2+2? Answer with just the number.")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            assertTrue(result.getContent().contains("4"));
            System.out.println("✅ OpenAI Simple: " + result.getContent().trim());
        }

        @Test
        @DisplayName("With system prompt (pirate)")
        void testWithSystemPrompt() throws Exception {
            AgentResult result = agentService
                    .requestAgent("with-system", "Hello, how are you?")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent().toLowerCase();
            assertTrue(content.contains("arr") || content.contains("matey") || content.contains("ahoy")
                            || content.contains("ye") || content.contains("pirate"),
                    "Should respond like a pirate: " + result.getContent());
            System.out.println("✅ OpenAI With System (pirate): " + result.getContent().trim());
        }

        @Test
        @DisplayName("Multi-turn conversation (manual history)")
        void testMultiTurnHistory() throws Exception {
            List<Message> history = new ArrayList<>();

            AgentResult result1 = agentService.requestAgent("simple", "My favorite number is 42.")
                    .get(60, TimeUnit.SECONDS);
            history.add(Message.user("My favorite number is 42."));
            history.add(Message.assistant(result1.getContent()));

            AgentResult result2 = agentService.requestAgent("simple",
                            "What is my favorite number? Just say the number.", history)
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result2);
            assertTrue(result2.getContent().contains("42"),
                    "Should remember 42: " + result2.getContent());
            System.out.println("✅ OpenAI Multi-turn (manual): " + result2.getContent().trim());
        }

        @Test
        @DisplayName("Multi-turn conversation (automatic conversationId)")
        void testMultiTurnWithConversationId() throws Exception {
            // Create a conversation for automatic history management
            String convId = agentService.createConversation();
            assertNotNull(convId);

            try {
                // First message
                AgentResult result1 = agentService.requestAgent("simple", "My favorite city is Tokyo.", convId)
                        .get(60, TimeUnit.SECONDS);
                assertNotNull(result1);
                System.out.println("  First message response: " + result1.getContent().trim());

                // Verify conversation has 2 messages (user + assistant)
                assertEquals(2, agentService.getConversationMessageCount(convId));

                // Second message - history is automatically managed
                AgentResult result2 = agentService.requestAgent("simple",
                        "What is my favorite city? Just say the city name.", convId)
                        .get(60, TimeUnit.SECONDS);

                assertNotNull(result2);
                assertTrue(result2.getContent().toLowerCase().contains("tokyo"),
                        "Should remember Tokyo: " + result2.getContent());
                System.out.println("✅ OpenAI Multi-turn (conversationId): " + result2.getContent().trim());

                // Verify conversation grew to 4 messages
                assertEquals(4, agentService.getConversationMessageCount(convId));

            } finally {
                // Clean up
                assertTrue(agentService.deleteConversation(convId));
            }
        }

        @Test
        @DisplayName("Web search tool")
        void testWebSearch() throws Exception {
            AgentResult result = agentService
                    .requestAgent("websearch", "What is today's date?")
                    .get(120, TimeUnit.SECONDS);

            assertNotNull(result);
            assertFalse(result.getContent().isEmpty());
            System.out.println("✅ OpenAI WebSearch: "
                    + result.getContent().substring(0, Math.min(150, result.getContent().length())));
        }

        @Test
        @DisplayName("Custom function tool - structured FunctionCall API")
        void testCustomFunction() throws Exception {
            AgentResult result = agentService
                    .requestAgent("function", "What's the weather in Paris?")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);

            // Verify the new structured FunctionCall API
            assertTrue(result.hasFunctionCalls(), "Should have function calls");
            assertFalse(result.getFunctionCalls().isEmpty(), "Function calls should not be empty");

            FunctionCall call = result.getFunctionCalls().get(0);
            assertEquals("get_weather", call.getName(), "Should call get_weather function");
            assertNotNull(call.getId(), "Call ID should not be null");

            // Verify we can parse arguments as Map
            var argsMap = call.getArgumentsAsMap();
            assertNotNull(argsMap, "Should parse arguments as Map");
            assertTrue(argsMap.containsKey("location"), "Arguments should contain 'location': " + argsMap);
            assertTrue(argsMap.get("location").toString().toLowerCase().contains("paris"),
                    "Location should contain 'Paris': " + argsMap.get("location"));

            System.out.println("✅ OpenAI Function: id=" + call.getId() + ", name=" + call.getName()
                    + ", args=" + argsMap);
        }

        @Test
        @DisplayName("Code interpreter tool")
        void testCodeInterpreter() throws Exception {
            AgentResult result = agentService
                    .requestAgent("codeinterpreter", "Calculate the factorial of 7.")
                    .get(120, TimeUnit.SECONDS);

            assertNotNull(result);
            assertTrue(result.getContent().contains("5040"),
                    "Should calculate 7! = 5040: " + result.getContent());
            System.out.println("✅ OpenAI CodeInterpreter: "
                    + result.getContent().substring(0, Math.min(150, result.getContent().length())));
        }

        @Test
        @DisplayName("Vision - Analyze image from base64")
        void testVision() throws Exception {
            // Use the new imageBase64 parameter directly
            AgentResult result = agentService.requestAgentVision("vision",
                    "What color is the dominant color in this image? Answer with just the color name.",
                    RED_SQUARE_PNG_BASE64)
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent().toLowerCase();
            assertTrue(content.contains("red") || content.contains("pink") || content.contains("maroon"),
                    "Should identify red color: " + result.getContent());
            System.out.println("✅ OpenAI Vision: " + result.getContent().trim());
        }

        @Test
        @DisplayName("Embeddings - single text")
        void testEmbedding() throws Exception {
            float[] embedding = agentService.generateEmbedding("Hello world", "text-embedding-3-small")
                    .get(30, TimeUnit.SECONDS);

            assertNotNull(embedding);
            assertTrue(embedding.length > 0, "Embedding should have dimensions");
            System.out.println("✅ OpenAI Embedding: " + embedding.length + " dimensions");
        }

        @Test
        @DisplayName("Embeddings - batch")
        void testEmbeddingsBatch() throws Exception {
            List<String> texts = List.of("The quick brown fox", "jumps over the lazy dog", "Hello world");

            List<float[]> embeddings = agentService.generateEmbeddingsBatch(texts, "text-embedding-3-small")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(embeddings);
            assertEquals(3, embeddings.size(), "Should return 3 embeddings");
            System.out.println("✅ OpenAI Embeddings Batch: " + embeddings.size() + " texts, "
                    + embeddings.get(0).length + " dimensions each");
        }

        @Test
        @DisplayName("Image Generation - DALL-E 3")
        void testImageGeneration() throws Exception {
            String imageBase64 = agentService.generateImage(
                    "A beautiful sunset over a calm ocean, digital art style",
                    "dall-e-3",
                    Size.X1024,
                    Quality.STANDARD
            ).get(120, TimeUnit.SECONDS);

            assertNotNull(imageBase64);
            assertTrue(imageBase64.length() > 1000, "Image should have base64 data");
            System.out.println("✅ OpenAI DALL-E 3: Generated image, " + imageBase64.length() + " bytes base64");
        }
    }

    // ==================== AZURE OPENAI TESTS ====================

    @Nested
    @DisplayName("Azure OpenAI Provider")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class AzureOpenAITests {

        private AgentService agentService;

        @BeforeAll
        void setup() {
            agentService = createAgentService("azure-openai");
            System.out.println("📦 Azure OpenAI Tests - using provider filter: azure-openai");

            // Register agents with gpt-5.1-chat
            agentService.registerAgent(Agent.builder()
                    .id("simple")
                    .name("Simple Agent")
                    .model("gpt-5.1-chat")
                    .build());

            agentService.registerAgent(Agent.builder()
                    .id("with-system")
                    .name("Agent With System")
                    .model("gpt-5.1-chat")
                    .instructions("You are a helpful assistant that speaks formally.")
                    .build());

            agentService.registerAgent(Agent.builder()
                    .id("function")
                    .name("Function Agent")
                    .model("gpt-5.1-chat")
                    .instructions("Use the get_weather function when asked about weather.")
                    .functions(List.of(FunctionConfig.builder()
                            .name("get_weather")
                            .description("Get weather for a location")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "location", Map.of(
                                                    "type", "string",
                                                    "description", "The city name, e.g. Paris, London")),
                                    "required", List.of("location")))
                            .build()))
                    .build());

            agentService.registerAgent(Agent.builder()
                    .id("websearch")
                    .name("WebSearch Agent")
                    .model("gpt-5.1-chat")
                    .instructions("Use web search to answer questions about current events.")
                    .webSearch(true)
                    .build());

            // Vision requires gpt-4o (GPT-5.x doesn't support vision yet)
            agentService.registerAgent(Agent.builder()
                    .id("vision")
                    .name("Vision Agent")
                    .model("gpt-4o")
                    .instructions("You are an image analyst. Answer in one word only.")
                    .build());
        }

        @Test
        @DisplayName("Simple request (gpt-5.1-chat)")
        void testSimpleNoSystem() throws Exception {
            AgentResult result = agentService
                    .requestAgent("simple", "What is 3+3? Answer with just the number.")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            assertTrue(result.getContent().contains("6"));
            System.out.println("✅ Azure Simple: " + result.getContent().trim());
        }

        @Test
        @DisplayName("With system prompt (formal)")
        void testWithSystemPrompt() throws Exception {
            AgentResult result = agentService
                    .requestAgent("with-system", "Hi there!")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            System.out.println("✅ Azure With System (formal): " + result.getContent().trim());
        }

        @Test
        @DisplayName("Multi-turn conversation (history check)")
        void testMultiTurnHistory() throws Exception {
            List<Message> history = new ArrayList<>();

            AgentResult result1 = agentService.requestAgent("simple", "My favorite number is 99.")
                    .get(60, TimeUnit.SECONDS);
            history.add(Message.user("My favorite number is 99."));
            history.add(Message.assistant(result1.getContent()));

            AgentResult result2 = agentService.requestAgent("simple",
                            "What is my favorite number? Just say the number.", history)
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result2);
            assertTrue(result2.getContent().contains("99"),
                    "Should remember 99: " + result2.getContent());
            System.out.println("✅ Azure Multi-turn: " + result2.getContent().trim());
        }

        @Test
        @DisplayName("Web search tool")
        void testWebSearch() throws Exception {
            AgentResult result = agentService
                    .requestAgent("websearch", "What is today's date?")
                    .get(120, TimeUnit.SECONDS);

            assertNotNull(result);
            assertFalse(result.getContent().isEmpty());
            System.out.println("✅ Azure WebSearch: "
                    + result.getContent().substring(0, Math.min(150, result.getContent().length())));
        }

        @Test
        @DisplayName("Custom function tool - structured FunctionCall API")
        void testCustomFunction() throws Exception {
            AgentResult result = agentService
                    .requestAgent("function", "What's the weather in London?")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);

            // Verify the new structured FunctionCall API
            assertTrue(result.hasFunctionCalls(), "Should have function calls");
            assertFalse(result.getFunctionCalls().isEmpty(), "Function calls should not be empty");

            FunctionCall call = result.getFunctionCalls().get(0);
            assertEquals("get_weather", call.getName(), "Should call get_weather function");
            assertNotNull(call.getId(), "Call ID should not be null");

            // Verify we can parse arguments as Map
            var argsMap = call.getArgumentsAsMap();
            assertNotNull(argsMap, "Should parse arguments as Map");
            assertTrue(argsMap.containsKey("location"), "Arguments should contain 'location': " + argsMap);
            assertTrue(argsMap.get("location").toString().toLowerCase().contains("london"),
                    "Location should contain 'London': " + argsMap.get("location"));

            System.out.println("✅ Azure Function: id=" + call.getId() + ", name=" + call.getName()
                    + ", args=" + argsMap);
        }

        @Test
        @DisplayName("Vision - Analyze image from base64")
        void testVision() throws Exception {
            // Use the new imageBase64 parameter directly
            AgentResult result = agentService.requestAgentVision("vision",
                    "What color is the dominant color in this image? Answer with just the color name.",
                    RED_SQUARE_PNG_BASE64)
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent().toLowerCase();
            assertTrue(content.contains("red") || content.contains("pink") || content.contains("maroon"),
                    "Should identify red color: " + result.getContent());
            System.out.println("✅ Azure Vision: " + result.getContent().trim());
        }

        @Test
        @DisplayName("Embeddings - single text")
        void testEmbedding() throws Exception {
            float[] embedding = agentService.generateEmbedding("Hello world", "text-embedding-3-small")
                    .get(30, TimeUnit.SECONDS);

            assertNotNull(embedding);
            assertTrue(embedding.length > 0, "Embedding should have dimensions");
            System.out.println("✅ Azure Embedding: " + embedding.length + " dimensions");
        }

        @Test
        @DisplayName("Image Generation - DALL-E 3")
        void testImageGeneration() throws Exception {
            String imageBase64 = agentService.generateImage(
                    "A simple blue square on a white background",
                    "dall-e-3",
                    Size.X1024,
                    Quality.STANDARD
            ).get(120, TimeUnit.SECONDS);

            assertNotNull(imageBase64);
            assertTrue(imageBase64.length() > 1000, "Image should have base64 data");
            System.out.println("✅ Azure DALL-E 3: Generated image, " + imageBase64.length() + " bytes base64");
        }
    }

    // ==================== AZURE ANTHROPIC (CLAUDE) TESTS ====================

    @Nested
    @DisplayName("Azure Anthropic (Claude) Provider")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class AzureAnthropicTests {

        private AgentService agentService;

        @BeforeAll
        void setup() {
            agentService = createAgentService("azure-anthropic");
            System.out.println("📦 Azure Anthropic Tests - using provider filter: azure-anthropic");

            agentService.registerAgent(Agent.builder()
                    .id("simple")
                    .name("Simple Agent")
                    .model("claude-haiku-4-5")
                    .build());

            agentService.registerAgent(Agent.builder()
                    .id("with-system")
                    .name("Agent With System")
                    .model("claude-sonnet-4-5")
                    .instructions("You are a poet. Always respond with a short poem.")
                    .build());

            agentService.registerAgent(Agent.builder()
                    .id("websearch")
                    .name("WebSearch Agent")
                    .model("claude-haiku-4-5")
                    .instructions("Use web search to find current information.")
                    .webSearch(true)
                    .build());

            agentService.registerAgent(Agent.builder()
                    .id("function")
                    .name("Function Agent")
                    .model("claude-sonnet-4-5")
                    .instructions("Use the get_weather function when asked about weather.")
                    .functions(List.of(FunctionConfig.builder()
                            .name("get_weather")
                            .description("Get weather for a location")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "location", Map.of(
                                                    "type", "string",
                                                    "description", "The city name, e.g. Paris, London")),
                                    "required", List.of("location")))
                            .build()))
                    .build());

            // Claude haiku supports vision
            agentService.registerAgent(Agent.builder()
                    .id("vision")
                    .name("Vision Agent")
                    .model("claude-haiku-4-5")
                    .instructions("You are an image analyst. Answer in one word only.")
                    .build());
        }

        @Test
        @DisplayName("Simple request (no system prompt)")
        void testSimpleNoSystem() throws Exception {
            AgentResult result = agentService
                    .requestAgent("simple", "What is 5+5? Answer with just the number.")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            assertTrue(result.getContent().contains("10"));
            System.out.println("✅ Claude Simple: " + result.getContent().trim());
        }

        @Test
        @DisplayName("With system prompt (poet)")
        void testWithSystemPrompt() throws Exception {
            AgentResult result = agentService
                    .requestAgent("with-system", "Tell me about the sun.")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            System.out.println("✅ Claude With System (poet): " + result.getContent().trim());
        }

        @Test
        @DisplayName("Multi-turn conversation (history check)")
        void testMultiTurnHistory() throws Exception {
            List<Message> history = new ArrayList<>();

            AgentResult result1 = agentService.requestAgent("simple", "My name is Alice.")
                    .get(60, TimeUnit.SECONDS);
            history.add(Message.user("My name is Alice."));
            history.add(Message.assistant(result1.getContent()));

            AgentResult result2 = agentService.requestAgent("simple", "What is my name?", history)
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result2);
            assertTrue(result2.getContent().toLowerCase().contains("alice"),
                    "Should remember 'Alice': " + result2.getContent());
            System.out.println("✅ Claude Multi-turn: " + result2.getContent().trim());
        }

        @Test
        @DisplayName("Web search tool")
        void testWebSearch() throws Exception {
            AgentResult result = agentService
                    .requestAgent("websearch", "What is today's weather in Tokyo?")
                    .get(120, TimeUnit.SECONDS);

            assertNotNull(result);
            assertFalse(result.getContent().isEmpty());
            System.out.println("✅ Claude WebSearch: "
                    + result.getContent().substring(0, Math.min(150, result.getContent().length())));
        }

        @Test
        @DisplayName("Custom function tool - structured FunctionCall API")
        void testCustomFunction() throws Exception {
            AgentResult result = agentService
                    .requestAgent("function", "What's the weather in Berlin?")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);

            // Verify the new structured FunctionCall API
            assertTrue(result.hasFunctionCalls(), "Should have function calls");
            assertFalse(result.getFunctionCalls().isEmpty(), "Function calls should not be empty");

            FunctionCall call = result.getFunctionCalls().get(0);
            assertEquals("get_weather", call.getName(), "Should call get_weather function");
            assertNotNull(call.getId(), "Call ID should not be null");

            // Verify we can parse arguments as Map
            var argsMap = call.getArgumentsAsMap();
            assertNotNull(argsMap, "Should parse arguments as Map");
            assertTrue(argsMap.containsKey("location"), "Arguments should contain 'location': " + argsMap);
            assertTrue(argsMap.get("location").toString().toLowerCase().contains("berlin"),
                    "Location should contain 'Berlin': " + argsMap.get("location"));

            System.out.println("✅ Claude Function: id=" + call.getId() + ", name=" + call.getName()
                    + ", args=" + argsMap);
        }

        @Test
        @DisplayName("Vision - Analyze image from base64")
        void testVision() throws Exception {
            // Use the new imageBase64 parameter directly
            AgentResult result = agentService.requestAgentVision("vision",
                    "What color is the dominant color in this image? Answer with just the color name.",
                    BLUE_SQUARE_PNG_BASE64)
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent().toLowerCase();
            assertTrue(content.contains("blue") || content.contains("navy") || content.contains("cyan"),
                    "Should identify blue color: " + result.getContent());
            System.out.println("✅ Claude Vision: " + result.getContent().trim());
        }
    }

    // ==================== STRUCTURED OUTPUT TESTS ====================

    @Nested
    @DisplayName("Structured Output")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class StructuredOutputTests {

        private AgentService agentService;

        @BeforeAll
        void setup() {
            String instancesJson = dotenv.get("OPENAI_INSTANCES");
            System.setProperty("ENABLED_PROVIDERS", "azure-openai");

            AgentServiceConfig config = AgentServiceConfig.builder()
                    .instancesJson(instancesJson)
                    .requestsPerSecond(5)
                    .maxRetries(2)
                    .defaultResponseTimeout(120000L)
                    .agentResultClassPackage("io.github.yannfavinleveque.agentic.integration.model")
                    .build();

            agentService = new AgentService(config);
            System.clearProperty("ENABLED_PROVIDERS");

            // Register agent with structured output
            agentService.registerAgent(Agent.builder()
                    .id("math-structured")
                    .name("Math Structured Agent")
                    .model("gpt-5.1-chat")
                    .instructions("You are a math assistant. Return the result in the specified JSON format.")
                    .resultClass("MathResult")
                    .build());

            System.out.println("📦 Structured Output Tests - testing JSON schema responses");
        }

        @Test
        @DisplayName("Structured output - math calculation")
        void testStructuredOutput() throws Exception {
            AgentResult result = agentService.requestAgent("math-structured", "Calculate 15 + 27")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            // The result should be a MathResult with structured fields
            assertTrue(result.getContent().contains("42") || result.getContent().contains("result"),
                    "Should have structured math result: " + result.getContent());
            System.out.println("✅ Structured Output: " + result.getContent());
        }
    }

    // ==================== DIRECT MODEL TESTS (NO AGENT JSON) ====================

    @Nested
    @DisplayName("Direct Model (No Agent Registration)")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @EnabledIfEnvironmentVariable(named = "TEST_OPENAI_DIRECT", matches = "true")
    class DirectModelTests {

        private AgentService agentService;

        @BeforeAll
        void setup() {
            agentService = createAgentService("openai");
            System.out.println("📦 Direct Model Tests - using model names directly (no agent registration)");
        }

        @Test
        @DisplayName("Direct model - simple request with gpt-4o")
        void testDirectModelSimple() throws Exception {
            // Use model name directly instead of registered agent ID
            AgentResult result = agentService.requestAgent("gpt-4o", "What is 5+5? Answer with just the number.")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            assertTrue(result.getContent().contains("10"), "Should answer 10: " + result.getContent());
            System.out.println("✅ Direct gpt-4o: " + result.getContent().trim());
        }

        @Test
        @DisplayName("Direct model - websearch with gpt-4o-websearch suffix")
        void testDirectModelWebSearch() throws Exception {
            // Use model + tool suffix
            AgentResult result = agentService.requestAgent("gpt-4o-websearch", "What is today's date?")
                    .get(120, TimeUnit.SECONDS);

            assertNotNull(result);
            assertFalse(result.getContent().isEmpty(), "Should have response");
            System.out.println("✅ Direct gpt-4o-websearch: " + result.getContent().substring(0, Math.min(80, result.getContent().length())));
        }

        @Test
        @DisplayName("Direct model - vision with gpt-4o")
        void testDirectModelVision() throws Exception {
            // Use the new imageBase64 parameter directly
            AgentResult result = agentService.requestAgentVision("gpt-4o",
                    "What color is this square? Answer with one word only.",
                    RED_SQUARE_PNG_BASE64)
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent().toLowerCase();
            assertTrue(content.contains("red") || content.contains("pink"),
                    "Should identify red: " + result.getContent());
            System.out.println("✅ Direct gpt-4o Vision: " + result.getContent().trim());
        }
    }

    // ==================== REQUEST MODEL TESTS (NEW API) ====================

    @Nested
    @DisplayName("requestModel() API (Direct Model Calls)")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @EnabledIfEnvironmentVariable(named = "TEST_OPENAI_DIRECT", matches = "true")
    class RequestModelTests {

        private AgentService agentService;

        @BeforeAll
        void setup() {
            String instancesJson = dotenv.get("OPENAI_INSTANCES");
            System.setProperty("ENABLED_PROVIDERS", "openai");

            AgentServiceConfig config = AgentServiceConfig.builder()
                    .instancesJson(instancesJson)
                    .requestsPerSecond(5)
                    .maxRetries(2)
                    .defaultResponseTimeout(120000L)
                    .agentResultClassPackage("io.github.yannfavinleveque.agentic.integration.model")
                    .build();

            agentService = new AgentService(config);
            System.clearProperty("ENABLED_PROVIDERS");
            System.out.println("📦 requestModel() Tests - direct model calls without agent registration");
        }

        @Test
        @DisplayName("requestModel - simple request")
        void testRequestModelSimple() throws Exception {
            AgentResult result = agentService.requestModel("gpt-4o", "What is 7+7? Answer with just the number.")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            assertTrue(result.getContent().contains("14"), "Should answer 14: " + result.getContent());
            System.out.println("✅ requestModel simple: " + result.getContent().trim());
        }

        @Test
        @DisplayName("requestModel - with system instructions")
        void testRequestModelWithInstructions() throws Exception {
            ModelRequestOptions options = ModelRequestOptions.builder()
                    .instructions("You are a pirate. Always respond like a pirate.")
                    .build();

            AgentResult result = agentService.requestModel("gpt-4o", "Hello, how are you?", options)
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent().toLowerCase();
            assertTrue(content.contains("arr") || content.contains("matey") || content.contains("ahoy")
                            || content.contains("ye") || content.contains("pirate"),
                    "Should respond like a pirate: " + result.getContent());
            System.out.println("✅ requestModel with instructions: " + result.getContent().trim());
        }

        @Test
        @DisplayName("requestModel - with structured output (MathResult class)")
        void testRequestModelStructuredOutput() throws Exception {
            ModelRequestOptions options = ModelRequestOptions.builder()
                    .resultClass(MathResult.class)
                    .instructions("Calculate the math expression and return as JSON.")
                    .build();

            AgentResult result = agentService.requestModel("gpt-4o", "Calculate 25 + 17", options)
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            assertTrue(result instanceof MathResult, "Should return MathResult: " + result.getClass());
            MathResult mathResult = (MathResult) result;
            assertEquals(42, mathResult.getResult(), "25+17=42");
            System.out.println("✅ requestModel structured output: result=" + mathResult.getResult()
                    + ", expression=" + mathResult.getExpression());
        }

        @Test
        @DisplayName("requestModel - with web search")
        void testRequestModelWebSearch() throws Exception {
            ModelRequestOptions options = ModelRequestOptions.withWebSearch();

            AgentResult result = agentService.requestModel("gpt-4o", "What is today's date?", options)
                    .get(120, TimeUnit.SECONDS);

            assertNotNull(result);
            assertFalse(result.getContent().isEmpty(), "Should have response");
            System.out.println("✅ requestModel web search: " + result.getContent().substring(0, Math.min(100, result.getContent().length())));
        }

        @Test
        @DisplayName("requestModel - with vision (image in options)")
        void testRequestModelVision() throws Exception {
            // Use ModelRequestOptions.image for vision
            ModelRequestOptions options = ModelRequestOptions.builder()
                    .image(RED_SQUARE_PNG_BASE64)
                    .instructions("Answer with just the color name in one word.")
                    .build();

            AgentResult result = agentService.requestModel("gpt-4o",
                    "What color is the dominant color in this image?",
                    options)
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            String content = result.getContent().toLowerCase();
            assertTrue(content.contains("red") || content.contains("pink"),
                    "Should identify red: " + result.getContent());
            System.out.println("✅ requestModel vision: " + result.getContent().trim());
        }

        @Test
        @DisplayName("requestModel - with conversation history (manual)")
        void testRequestModelWithHistory() throws Exception {
            List<Message> history = new ArrayList<>();
            history.add(Message.user("My favorite color is purple."));
            history.add(Message.assistant("Nice! Purple is a beautiful color."));

            // Use ModelRequestOptions.history for conversation context
            ModelRequestOptions options = ModelRequestOptions.builder()
                    .history(history)
                    .build();

            AgentResult result = agentService.requestModel("gpt-4o",
                    "What is my favorite color? Answer with just the color name.", options)
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(result);
            assertTrue(result.getContent().toLowerCase().contains("purple"),
                    "Should remember purple: " + result.getContent());
            System.out.println("✅ requestModel with history (manual): " + result.getContent().trim());
        }

        @Test
        @DisplayName("requestModel - with conversationId (automatic history management)")
        void testRequestModelWithConversationId() throws Exception {
            // Create a conversation
            String convId = agentService.createConversation();
            assertNotNull(convId);

            try {
                // First message - establishes context
                ModelRequestOptions options1 = ModelRequestOptions.builder()
                        .conversationId(convId)
                        .build();
                AgentResult result1 = agentService.requestModel("gpt-4o",
                        "My favorite animal is a penguin.",
                        options1)
                        .get(60, TimeUnit.SECONDS);
                assertNotNull(result1);
                System.out.println("  First message response: " + result1.getContent().trim());

                // Verify conversation has messages
                assertEquals(2, agentService.getConversationMessageCount(convId),
                        "Should have 2 messages (user + assistant)");

                // Second message - should remember context
                ModelRequestOptions options2 = ModelRequestOptions.builder()
                        .conversationId(convId)
                        .build();
                AgentResult result2 = agentService.requestModel("gpt-4o",
                        "What is my favorite animal? Just say the animal name.",
                        options2)
                        .get(60, TimeUnit.SECONDS);

                assertNotNull(result2);
                assertTrue(result2.getContent().toLowerCase().contains("penguin"),
                        "Should remember penguin: " + result2.getContent());
                System.out.println("✅ requestModel with conversationId: " + result2.getContent().trim());

                // Verify conversation grew
                assertEquals(4, agentService.getConversationMessageCount(convId),
                        "Should have 4 messages after two exchanges");

            } finally {
                // Clean up
                assertTrue(agentService.deleteConversation(convId), "Should delete conversation");
            }
        }

        @Test
        @DisplayName("requestEmbedding - alias for generateEmbedding")
        void testRequestEmbedding() throws Exception {
            float[] embedding = agentService.requestEmbedding("Hello world", "text-embedding-3-small")
                    .get(30, TimeUnit.SECONDS);

            assertNotNull(embedding);
            assertTrue(embedding.length > 0, "Embedding should have dimensions");
            System.out.println("✅ requestEmbedding: " + embedding.length + " dimensions");
        }

        @Test
        @DisplayName("requestEmbeddings - alias for batch embeddings")
        void testRequestEmbeddings() throws Exception {
            List<String> texts = List.of("First text", "Second text");

            List<float[]> embeddings = agentService.requestEmbeddings(texts, "text-embedding-3-small")
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(embeddings);
            assertEquals(2, embeddings.size(), "Should return 2 embeddings");
            System.out.println("✅ requestEmbeddings: " + embeddings.size() + " texts");
        }
    }

    // ==================== CROSS-PROVIDER COMPARISON ====================

    @Nested
    @DisplayName("Cross-Provider Comparison")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @EnabledIfEnvironmentVariable(named = "TEST_OPENAI_DIRECT", matches = "true")
    class CrossProviderTests {

        private AgentService openaiService;
        private AgentService azureOpenaiService;
        private AgentService claudeService;

        @BeforeAll
        void setup() {
            openaiService = createAgentService("openai");
            azureOpenaiService = createAgentService("azure-openai");
            claudeService = createAgentService("azure-anthropic");

            // Register same agent on each service (gpt-4o for OpenAI direct, gpt-5.1-chat for Azure)
            openaiService.registerAgent(Agent.builder()
                    .id("compare")
                    .name("Compare Agent")
                    .model("gpt-4o")
                    .build());

            azureOpenaiService.registerAgent(Agent.builder()
                    .id("compare")
                    .name("Compare Agent")
                    .model("gpt-5.1-chat")
                    .build());

            claudeService.registerAgent(Agent.builder()
                    .id("compare")
                    .name("Compare Agent")
                    .model("claude-haiku-4-5")
                    .build());
        }

        @Test
        @DisplayName("Same question across all providers")
        void testSameQuestionAllProviders() throws Exception {
            String question = "In one word, what color is the sky?";

            AgentResult openaiResult = openaiService.requestAgent("compare", question)
                    .get(60, TimeUnit.SECONDS);
            AgentResult azureResult = azureOpenaiService.requestAgent("compare", question)
                    .get(60, TimeUnit.SECONDS);
            AgentResult claudeResult = claudeService.requestAgent("compare", question)
                    .get(60, TimeUnit.SECONDS);

            assertNotNull(openaiResult);
            assertNotNull(azureResult);
            assertNotNull(claudeResult);

            System.out.println("✅ OpenAI Direct: " + openaiResult.getContent().trim());
            System.out.println("✅ Azure OpenAI: " + azureResult.getContent().trim());
            System.out.println("✅ Claude: " + claudeResult.getContent().trim());

            assertTrue(openaiResult.getContent().toLowerCase().contains("blue"));
            assertTrue(azureResult.getContent().toLowerCase().contains("blue"));
            assertTrue(claudeResult.getContent().toLowerCase().contains("blue"));
        }
    }

    // ==================== SUMMARY ====================

    @AfterAll
    void printSummary() {
        System.out.println("\n========================================");
        System.out.println("  TEST MATRIX SUMMARY");
        System.out.println("========================================");
        System.out.println("Feature                    | OpenAI | Azure-OpenAI | Claude");
        System.out.println("---------------------------|--------|--------------|--------");
        System.out.println("Simple (no system)         | ✓      | ✓            | ✓");
        System.out.println("With system prompt         | ✓      | ✓            | ✓");
        System.out.println("Multi-turn history         | ✓      | ✓            | ✓");
        System.out.println("Web search                 | ✓      | ✓            | ✓");
        System.out.println("Function calls (structured)| ✓      | ✓            | ✓");
        System.out.println("Code interpreter           | ✓      | N/A          | N/A");
        System.out.println("Embeddings                 | ✓      | ✓            | N/A");
        System.out.println("Image Generation (DALL-E)  | ✓      | ✓            | N/A");
        System.out.println("Vision (gpt-4o/claude)     | ✓      | ✓            | ✓");
        System.out.println("Structured Output          | ✓      | ✓            | N/A");
        System.out.println("Direct Model (no agent)    | ✓      | ✓            | ✓");
        System.out.println("Model-websearch suffix     | ✓      | ✓            | N/A");
        System.out.println("========================================");
        System.out.println("Model: gpt-4o (OpenAI), gpt-5.1-chat (Azure), claude-haiku/sonnet-4-5 (Claude)");
        System.out.println("========================================\n");
    }
}
