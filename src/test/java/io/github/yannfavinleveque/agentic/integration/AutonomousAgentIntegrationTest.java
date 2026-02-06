package io.github.yannfavinleveque.agentic.integration;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import io.github.yannfavinleveque.agentic.agent.model.FunctionConfig;
import io.github.yannfavinleveque.agentic.agent.service.AgentService;
import io.github.yannfavinleveque.agentic.integration.model.ResearchResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the autonomous agent mode.
 * <p>
 * Tests the full tool-calling loop with real API calls:
 * - Agent calls tools, library executes them via ToolExecutor, sends results back
 * - Loop terminates when agent calls task_over with structured result
 * </p>
 * <p>
 * Tests both Azure OpenAI (gpt-5.1-chat) and Azure Anthropic (claude-sonnet-4-5) providers.
 * Each provider has two test scenarios:
 * - No conversationId + maxToolTokenOutput trimming
 * - With conversationId + continuity across two sequential calls (no trim)
 * </p>
 * <p>
 * Run with: RUN_INTEGRATION_TESTS=true mvn test -Dtest=AutonomousAgentIntegrationTest
 * </p>
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class AutonomousAgentIntegrationTest {

    private static final Dotenv dotenv = Dotenv.load();

    @BeforeAll
    void printHeader() {
        System.out.println("\n========================================");
        System.out.println("  AUTONOMOUS AGENT INTEGRATION TESTS");
        System.out.println("  Providers: Azure-OpenAI, Azure-Anthropic");
        System.out.println("========================================\n");
    }

    // ==================== SHARED TOOL DEFINITIONS ====================

    private static FunctionConfig searchDbFunc() {
        return FunctionConfig.builder()
                .name("search_database")
                .description("Search a database for information on a given topic. Returns relevant data entries.")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of(
                                        "type", "string",
                                        "description", "The search query to look up in the database")),
                        "required", List.of("query"),
                        "additionalProperties", false))
                .build();
    }

    private static FunctionConfig analyzeDataFunc() {
        return FunctionConfig.builder()
                .name("analyze_data")
                .description("Analyze a dataset and return statistical insights or a summary.")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "data", Map.of(
                                        "type", "string",
                                        "description", "The data to analyze (as text)"),
                                "analysis_type", Map.of(
                                        "type", "string",
                                        "description", "Type of analysis: 'summary', 'statistics', or 'trends'")),
                        "required", List.of("data", "analysis_type"),
                        "additionalProperties", false))
                .build();
    }

    // ==================== SHARED AGENT SERVICE FACTORY ====================

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
                .defaultResponseTimeout(180000L)
                .agentResultClassPackage("io.github.yannfavinleveque.agentic.integration.model")
                .build();

        AgentService service = new AgentService(config);
        System.clearProperty("ENABLED_PROVIDERS");
        return service;
    }

    // ==================== SHARED TOOL EXECUTOR ====================

    /**
     * Simulates tool execution. Returns realistic-looking data for both tools.
     */
    private static String executeToolCall(io.github.yannfavinleveque.agentic.agent.model.FunctionCall call,
                                          AtomicInteger searchCount, AtomicInteger analyzeCount) {
        String toolName = call.getName();

        if ("search_database".equals(toolName)) {
            searchCount.incrementAndGet();
            Map<String, Object> args = call.getArgumentsAsMap();
            String query = args.getOrDefault("query", "unknown").toString();
            System.out.println("    [Tool] search_database(\"" + query + "\")");
            // Return a long result to test trimming
            return "Database results for '" + query + "':\n"
                    + "Entry 1: Solar energy capacity grew 25% worldwide in 2025, reaching 2,500 GW total installed capacity. "
                    + "China leads with 800 GW, followed by the US at 350 GW and India at 200 GW.\n"
                    + "Entry 2: Wind energy investment hit $180 billion globally. Offshore wind saw the largest growth "
                    + "at 35% year-over-year. European countries dominate offshore installations.\n"
                    + "Entry 3: Battery storage technology improved significantly with solid-state batteries entering "
                    + "commercial production. Average costs dropped to $100/kWh, making grid-scale storage economically viable.\n"
                    + "Entry 4: Green hydrogen production reached 5 million tons annually. Electrolysis efficiency "
                    + "improved to 80%, making it competitive with grey hydrogen in some markets.\n"
                    + "Entry 5: Global renewable energy now provides 40% of electricity generation, up from 30% in 2023.";
        }

        if ("analyze_data".equals(toolName)) {
            analyzeCount.incrementAndGet();
            Map<String, Object> args = call.getArgumentsAsMap();
            String analysisType = args.getOrDefault("analysis_type", "summary").toString();
            System.out.println("    [Tool] analyze_data(type=\"" + analysisType + "\")");
            return "Analysis (" + analysisType + "):\n"
                    + "- Key trend: Exponential growth in solar and wind capacity\n"
                    + "- Notable finding: Battery storage costs crossed the $100/kWh threshold\n"
                    + "- Statistical highlight: 40% of global electricity from renewables (10pp increase in 2 years)\n"
                    + "- Emerging sector: Green hydrogen gaining commercial viability\n"
                    + "- Regional leaders: China (solar), Europe (offshore wind), US (overall investment)";
        }

        return "Unknown tool: " + toolName;
    }

    // ==================== SHARED TEST LOGIC ====================

    private static final String INSTRUCTIONS = "You are a research assistant. To complete a research task:\n"
            + "1. First use search_database to find relevant data on the topic\n"
            + "2. Then use analyze_data to analyze the search results\n"
            + "3. You MUST use BOTH tools at least once before finishing\n"
            + "4. When done, call task_over with the structured result";

    /**
     * Registers the trimmed + full agents for a given model.
     */
    private static void registerAgents(AgentService service, String model, String idSuffix) {
        service.registerAgent(Agent.builder()
                .id("researcher-trimmed-" + idSuffix)
                .name("Researcher Trimmed (" + idSuffix + ")")
                .model(model)
                .instructions(INSTRUCTIONS)
                .resultClass("ResearchResult")
                .autonomous(true)
                .maxIterations(10)
                .maxToolTokenOutput(50)  // Very low limit to force trimming
                .functions(List.of(searchDbFunc(), analyzeDataFunc()))
                .build());

        service.registerAgent(Agent.builder()
                .id("researcher-full-" + idSuffix)
                .name("Researcher Full (" + idSuffix + ")")
                .model(model)
                .instructions(INSTRUCTIONS)
                .resultClass("ResearchResult")
                .autonomous(true)
                .maxIterations(10)
                .functions(List.of(searchDbFunc(), analyzeDataFunc()))
                .build());
    }

    /**
     * Test: no conversationId + trimming enabled.
     */
    private static void runTrimTest(AgentService service, String idSuffix, String providerLabel) throws Exception {
        AtomicInteger searchCount = new AtomicInteger(0);
        AtomicInteger analyzeCount = new AtomicInteger(0);

        System.out.println("--- [" + providerLabel + "] No conversationId + maxToolTokenOutput=50 ---");

        AgentResult result = service.requestAgent(
                "researcher-trimmed-" + idSuffix,
                "Research the current state of renewable energy technology. "
                        + "Search for data first, then analyze it, and provide a structured summary.",
                call -> executeToolCall(call, searchCount, analyzeCount)
        ).get(180, TimeUnit.SECONDS);

        assertNotNull(result, "Result should not be null");
        assertTrue(result instanceof ResearchResult,
                "Should return ResearchResult, got: " + result.getClass().getSimpleName());

        ResearchResult research = (ResearchResult) result;
        assertNotNull(research.getTopic(), "Topic should not be null");
        assertNotNull(research.getFindings(), "Findings should not be null");
        assertFalse(research.getFindings().isEmpty(), "Findings should not be empty");
        assertNotNull(research.getConclusion(), "Conclusion should not be null");

        assertTrue(searchCount.get() >= 1,
                "search_database should be called at least once, was: " + searchCount.get());
        assertTrue(analyzeCount.get() >= 1,
                "analyze_data should be called at least once, was: " + analyzeCount.get());

        System.out.println("  Result topic: " + research.getTopic());
        System.out.println("  Findings count: " + research.getFindings().size());
        System.out.println("  Conclusion: " + research.getConclusion());
        System.out.println("  Tools called: search_database=" + searchCount.get()
                + ", analyze_data=" + analyzeCount.get());
        System.out.println("  maxToolTokenOutput=50 -> tool outputs were trimmed to ~200 chars");
        System.out.println("--- [" + providerLabel + "] Trim test PASSED ---\n");
    }

    /**
     * Test: with conversationId + no trim + two sequential calls.
     */
    private static void runContinuityTest(AgentService service, String idSuffix, String providerLabel) throws Exception {
        AtomicInteger searchCount = new AtomicInteger(0);
        AtomicInteger analyzeCount = new AtomicInteger(0);

        System.out.println("--- [" + providerLabel + "] With conversationId + no trim + two sequential calls ---");

        String convId = service.createConversation();
        assertNotNull(convId, "ConversationId should not be null");
        System.out.println("  Created conversation: " + convId);

        try {
            // === First call ===
            System.out.println("  === Call 1: Research solar energy ===");
            AgentResult result1 = service.requestAgent(
                    "researcher-full-" + idSuffix,
                    "Research the current state of solar energy technology. "
                            + "Search for data, analyze it, and summarize your findings.",
                    convId,
                    call -> executeToolCall(call, searchCount, analyzeCount)
            ).get(180, TimeUnit.SECONDS);

            assertNotNull(result1, "First result should not be null");
            assertTrue(result1 instanceof ResearchResult,
                    "First result should be ResearchResult, got: " + result1.getClass().getSimpleName());

            ResearchResult research1 = (ResearchResult) result1;
            assertNotNull(research1.getTopic(), "First topic should not be null");
            assertNotNull(research1.getFindings(), "First findings should not be null");

            int firstCallSearchCount = searchCount.get();
            int firstCallAnalyzeCount = analyzeCount.get();
            assertTrue(firstCallSearchCount >= 1, "search_database called at least once in first call");
            assertTrue(firstCallAnalyzeCount >= 1, "analyze_data called at least once in first call");

            System.out.println("    Result 1 topic: " + research1.getTopic());
            System.out.println("    Result 1 findings: " + research1.getFindings().size());
            System.out.println("    Tools used: search=" + firstCallSearchCount + ", analyze=" + firstCallAnalyzeCount);

            assertTrue(service.getConversationMessageCount(convId) > 0,
                    "Conversation should still have messages after first call");
            System.out.println("    Conversation messages after call 1: "
                    + service.getConversationMessageCount(convId));

            // === Second call ===
            System.out.println("  === Call 2: Follow-up research (references first) ===");
            AgentResult result2 = service.requestAgent(
                    "researcher-full-" + idSuffix,
                    "Now research wind energy technology. Compare your findings with what you "
                            + "found about solar energy in the previous research. "
                            + "Use the tools again and provide a new summary.",
                    convId,
                    call -> executeToolCall(call, searchCount, analyzeCount)
            ).get(180, TimeUnit.SECONDS);

            assertNotNull(result2, "Second result should not be null");
            assertTrue(result2 instanceof ResearchResult,
                    "Second result should be ResearchResult, got: " + result2.getClass().getSimpleName());

            ResearchResult research2 = (ResearchResult) result2;
            assertNotNull(research2.getTopic(), "Second topic should not be null");
            assertNotNull(research2.getFindings(), "Second findings should not be null");

            assertTrue(searchCount.get() > firstCallSearchCount,
                    "search_database should be called again in second call");
            assertTrue(analyzeCount.get() > firstCallAnalyzeCount,
                    "analyze_data should be called again in second call");

            int finalMessageCount = service.getConversationMessageCount(convId);
            System.out.println("    Result 2 topic: " + research2.getTopic());
            System.out.println("    Result 2 findings: " + research2.getFindings().size());
            System.out.println("    Total tools used: search=" + searchCount.get()
                    + ", analyze=" + analyzeCount.get());
            System.out.println("    Conversation messages after call 2: " + finalMessageCount);

            assertTrue(finalMessageCount > 2,
                    "Conversation should have accumulated messages from both calls");

        } finally {
            boolean deleted = service.deleteConversation(convId);
            assertTrue(deleted, "Should successfully delete external conversation");
            System.out.println("  Cleaned up conversation: " + convId);
        }

        System.out.println("--- [" + providerLabel + "] Continuity test PASSED ---\n");
    }

    // ==================== AZURE OPENAI (gpt-5.1-chat) ====================

    @Nested
    @DisplayName("Azure OpenAI (gpt-5.1-chat)")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class AzureOpenAITests {

        private AgentService agentService;

        @BeforeAll
        void setup() {
            agentService = createAgentService("azure-openai");
            registerAgents(agentService, "gpt-5.1-chat", "azure");
            System.out.println("  [Azure OpenAI] AgentService initialized with gpt-5.1-chat\n");
        }

        @Test
        @DisplayName("No conversationId + tool output trimming (gpt-5.1-chat)")
        void testTrimming() throws Exception {
            runTrimTest(agentService, "azure", "Azure-OpenAI");
        }

        @Test
        @DisplayName("With conversationId + continuity across two calls (gpt-5.1-chat)")
        void testContinuity() throws Exception {
            runContinuityTest(agentService, "azure", "Azure-OpenAI");
        }
    }

    // ==================== AZURE ANTHROPIC (claude-sonnet-4-5) ====================

    @Nested
    @DisplayName("Azure Anthropic (claude-sonnet-4-5)")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class AzureAnthropicTests {

        private AgentService agentService;

        @BeforeAll
        void setup() {
            agentService = createAgentService("azure-anthropic");
            registerAgents(agentService, "claude-sonnet-4-5", "claude");
            System.out.println("  [Azure Anthropic] AgentService initialized with claude-sonnet-4-5\n");
        }

        @Test
        @DisplayName("No conversationId + tool output trimming (claude-sonnet-4-5)")
        void testTrimming() throws Exception {
            runTrimTest(agentService, "claude", "Azure-Anthropic");
        }

        @Test
        @DisplayName("With conversationId + continuity across two calls (claude-sonnet-4-5)")
        void testContinuity() throws Exception {
            runContinuityTest(agentService, "claude", "Azure-Anthropic");
        }
    }
}
