package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.model.ClaudeRequest;
import io.github.yannfavinleveque.agentic.agent.model.FunctionConfig;
import io.github.yannfavinleveque.agentic.domain.responses.ResponsesRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ToolBuilder}. Tests tool construction for OpenAI and Claude providers.
 */
class ToolBuilderTest {

    // ==================== OPENAI TOOLS TESTS ====================

    @Nested
    @DisplayName("OpenAI Tools")
    class OpenAIToolsTests {

        @Test
        @DisplayName("Returns null when no tools configured")
        void testNoToolsReturnsNull() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .build();

            List<ResponsesRequest.Tool> tools = ToolBuilder.buildOpenAITools(agent);

            assertNull(tools);
        }

        @Test
        @DisplayName("Builds web search tool")
        void testWebSearchTool() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .webSearch(true)
                    .build();

            List<ResponsesRequest.Tool> tools = ToolBuilder.buildOpenAITools(agent);

            assertNotNull(tools);
            assertEquals(1, tools.size());
            assertEquals("web_search_preview", tools.get(0).getType());
        }

        @Test
        @DisplayName("Builds code interpreter tool")
        void testCodeInterpreterTool() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .codeInterpreter(true)
                    .build();

            List<ResponsesRequest.Tool> tools = ToolBuilder.buildOpenAITools(agent);

            assertNotNull(tools);
            assertEquals(1, tools.size());
            assertEquals("code_interpreter", tools.get(0).getType());
        }

        @Test
        @DisplayName("Builds file search tool from retrieval flag")
        void testFileSearchFromRetrieval() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .retrieval(true)
                    .build();

            List<ResponsesRequest.Tool> tools = ToolBuilder.buildOpenAITools(agent);

            assertNotNull(tools);
            assertEquals(1, tools.size());
            assertEquals("file_search", tools.get(0).getType());
        }

        @Test
        @DisplayName("Builds multiple tools")
        void testMultipleTools() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .webSearch(true)
                    .codeInterpreter(true)
                    .retrieval(true)
                    .build();

            List<ResponsesRequest.Tool> tools = ToolBuilder.buildOpenAITools(agent);

            assertNotNull(tools);
            assertEquals(3, tools.size());

            List<String> types = tools.stream()
                    .map(ResponsesRequest.Tool::getType)
                    .collect(Collectors.toList());
            assertTrue(types.contains("web_search_preview"));
            assertTrue(types.contains("code_interpreter"));
            assertTrue(types.contains("file_search"));
        }

        @Test
        @DisplayName("Builds custom function tool")
        void testCustomFunctionTool() {
            FunctionConfig func = FunctionConfig.builder()
                    .name("get_weather")
                    .description("Get weather for a location")
                    .build();

            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .functions(List.of(func))
                    .build();

            List<ResponsesRequest.Tool> tools = ToolBuilder.buildOpenAITools(agent);

            assertNotNull(tools);
            assertEquals(1, tools.size());
            assertEquals("function", tools.get(0).getType());
            assertEquals("get_weather", tools.get(0).getName());
            assertEquals("Get weather for a location", tools.get(0).getDescription());
            assertNotNull(tools.get(0).getParameters());
        }

        @Test
        @DisplayName("Ignores false boolean values")
        void testIgnoresFalseValues() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .webSearch(false)
                    .codeInterpreter(false)
                    .retrieval(false)
                    .build();

            List<ResponsesRequest.Tool> tools = ToolBuilder.buildOpenAITools(agent);

            assertNull(tools);
        }

    }

    // ==================== CLAUDE TOOLS TESTS ====================

    @Nested
    @DisplayName("Claude Tools")
    class ClaudeToolsTests {

        @Test
        @DisplayName("Returns null when no tools configured")
        void testNoToolsReturnsNull() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("claude-sonnet-4-5")
                    .build();

            List<ClaudeRequest.ClaudeTool> tools = ToolBuilder.buildClaudeTools(agent);

            assertNull(tools);
        }

        @Test
        @DisplayName("Builds web search tool")
        void testWebSearchTool() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("claude-sonnet-4-5")
                    .webSearch(true)
                    .build();

            List<ClaudeRequest.ClaudeTool> tools = ToolBuilder.buildClaudeTools(agent);

            assertNotNull(tools);
            assertEquals(1, tools.size());
            assertEquals("web_search_20250305", tools.get(0).getType());
            assertEquals("web_search", tools.get(0).getName());
        }

        @Test
        @DisplayName("Ignores code interpreter (not supported)")
        void testIgnoresCodeInterpreter() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("claude-sonnet-4-5")
                    .codeInterpreter(true)
                    .build();

            List<ClaudeRequest.ClaudeTool> tools = ToolBuilder.buildClaudeTools(agent);

            // Code interpreter not supported on Claude, should return null
            assertNull(tools);
        }

        @Test
        @DisplayName("Builds custom function tool")
        void testCustomFunctionTool() {
            FunctionConfig func = FunctionConfig.builder()
                    .name("calculate_sum")
                    .description("Calculate sum of numbers")
                    .build();

            Agent agent = Agent.builder()
                    .id("test")
                    .model("claude-sonnet-4-5")
                    .functions(List.of(func))
                    .build();

            List<ClaudeRequest.ClaudeTool> tools = ToolBuilder.buildClaudeTools(agent);

            assertNotNull(tools);
            assertEquals(1, tools.size());
            assertEquals("calculate_sum", tools.get(0).getName());
            assertEquals("Calculate sum of numbers", tools.get(0).getDescription());
            assertNotNull(tools.get(0).getInputSchema());
        }

        @Test
        @DisplayName("Builds web search with custom function")
        void testWebSearchWithFunction() {
            FunctionConfig func = FunctionConfig.builder()
                    .name("my_func")
                    .description("My function")
                    .build();

            Agent agent = Agent.builder()
                    .id("test")
                    .model("claude-sonnet-4-5")
                    .webSearch(true)
                    .functions(List.of(func))
                    .build();

            List<ClaudeRequest.ClaudeTool> tools = ToolBuilder.buildClaudeTools(agent);

            assertNotNull(tools);
            assertEquals(2, tools.size());
        }

    }

    // ==================== PROVIDER DETECTION TESTS ====================

    @Nested
    @DisplayName("Provider Detection")
    class ProviderDetectionTests {

        @Test
        @DisplayName("Returns OpenAI tools for gpt models")
        void testOpenAIToolsForGptModel() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .webSearch(true)
                    .build();

            Object tools = ToolBuilder.buildToolsForModel(agent);

            assertNotNull(tools);
            assertTrue(tools instanceof List);
            @SuppressWarnings("unchecked")
            List<ResponsesRequest.Tool> openaiTools = (List<ResponsesRequest.Tool>) tools;
            assertEquals("web_search_preview", openaiTools.get(0).getType());
        }

        @Test
        @DisplayName("Returns Claude tools for claude models")
        void testClaudeToolsForClaudeModel() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("claude-sonnet-4-5")
                    .webSearch(true)
                    .build();

            Object tools = ToolBuilder.buildToolsForModel(agent);

            assertNotNull(tools);
            assertTrue(tools instanceof List);
            @SuppressWarnings("unchecked")
            List<ClaudeRequest.ClaudeTool> claudeTools = (List<ClaudeRequest.ClaudeTool>) tools;
            assertEquals("web_search_20250305", claudeTools.get(0).getType());
        }

        @Test
        @DisplayName("Returns OpenAI tools for gpt-5 models")
        void testOpenAIToolsForGpt5Model() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-5.1-chat")
                    .webSearch(true)
                    .build();

            Object tools = ToolBuilder.buildToolsForModel(agent);

            assertNotNull(tools);
            assertTrue(tools instanceof List);
            @SuppressWarnings("unchecked")
            List<ResponsesRequest.Tool> openaiTools = (List<ResponsesRequest.Tool>) tools;
            assertEquals("web_search_preview", openaiTools.get(0).getType());
        }

    }

    // ==================== HAS TOOLS TESTS ====================

    @Nested
    @DisplayName("Has Tools Check")
    class HasToolsTests {

        @Test
        @DisplayName("Returns false when no tools")
        void testNoTools() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .build();

            assertFalse(ToolBuilder.hasTools(agent));
        }

        @Test
        @DisplayName("Returns true for web search")
        void testWebSearch() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .webSearch(true)
                    .build();

            assertTrue(ToolBuilder.hasTools(agent));
        }

        @Test
        @DisplayName("Returns true for code interpreter")
        void testCodeInterpreter() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .codeInterpreter(true)
                    .build();

            assertTrue(ToolBuilder.hasTools(agent));
        }

        @Test
        @DisplayName("Returns true for retrieval")
        void testRetrieval() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .retrieval(true)
                    .build();

            assertTrue(ToolBuilder.hasTools(agent));
        }

        @Test
        @DisplayName("Returns true for functions")
        void testFunctions() {
            FunctionConfig func = FunctionConfig.builder()
                    .name("test")
                    .build();

            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .functions(List.of(func))
                    .build();

            assertTrue(ToolBuilder.hasTools(agent));
        }

        @Test
        @DisplayName("Returns false for empty functions list")
        void testEmptyFunctionsList() {
            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .functions(List.of())
                    .build();

            assertFalse(ToolBuilder.hasTools(agent));
        }

    }

    // ==================== FUNCTION SCHEMA TESTS ====================

    @Nested
    @DisplayName("Function Schema Generation")
    class FunctionSchemaTests {

        @Test
        @DisplayName("Generates default schema when no parameter class")
        void testDefaultSchema() {
            FunctionConfig func = FunctionConfig.builder()
                    .name("simple_func")
                    .description("A simple function")
                    .build();

            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .functions(List.of(func))
                    .build();

            List<ResponsesRequest.Tool> tools = ToolBuilder.buildOpenAITools(agent);

            assertNotNull(tools);
            assertEquals(1, tools.size());
            assertNotNull(tools.get(0).getParameters());
            assertEquals("object", tools.get(0).getParameters().get("type"));
        }

        @Test
        @DisplayName("Handles multiple functions")
        void testMultipleFunctions() {
            FunctionConfig func1 = FunctionConfig.builder()
                    .name("func1")
                    .description("Function 1")
                    .build();

            FunctionConfig func2 = FunctionConfig.builder()
                    .name("func2")
                    .description("Function 2")
                    .build();

            Agent agent = Agent.builder()
                    .id("test")
                    .model("gpt-4o")
                    .functions(List.of(func1, func2))
                    .build();

            List<ResponsesRequest.Tool> tools = ToolBuilder.buildOpenAITools(agent);

            assertNotNull(tools);
            assertEquals(2, tools.size());
            assertEquals("func1", tools.get(0).getName());
            assertEquals("func2", tools.get(1).getName());
        }

    }

}
