package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.core.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DeepSeekAdapter} static helpers.
 * Covers DeepSeek model detection (prefix-based, case-insensitive), reasoning detection
 * (deepseek-reasoner only), request body construction (no reasoning_effort), and
 * extraction of the non-standard {@code reasoning_content} field.
 */
class DeepSeekAdapterTest {

    @Nested
    @DisplayName("isDeepSeekModel")
    class IsDeepSeekModel {

        @Test
        @DisplayName("recognizes deepseek-chat as DeepSeek")
        void recognizesDeepSeekChat() {
            assertTrue(DeepSeekAdapter.isDeepSeekModel("deepseek-chat"));
        }

        @Test
        @DisplayName("recognizes deepseek-reasoner as DeepSeek")
        void recognizesDeepSeekReasoner() {
            assertTrue(DeepSeekAdapter.isDeepSeekModel("deepseek-reasoner"));
        }

        @Test
        @DisplayName("recognizes deepseek-coder as DeepSeek")
        void recognizesDeepSeekCoder() {
            assertTrue(DeepSeekAdapter.isDeepSeekModel("deepseek-coder"));
        }

        @Test
        @DisplayName("rejects gpt-4o")
        void rejectsGpt4o() {
            assertFalse(DeepSeekAdapter.isDeepSeekModel("gpt-4o"));
        }

        @Test
        @DisplayName("rejects grok-4")
        void rejectsGrok4() {
            assertFalse(DeepSeekAdapter.isDeepSeekModel("grok-4"));
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertFalse(DeepSeekAdapter.isDeepSeekModel(null));
        }

        @Test
        @DisplayName("is case-insensitive")
        void caseInsensitive() {
            assertTrue(DeepSeekAdapter.isDeepSeekModel("DEEPSEEK-CHAT"));
            assertTrue(DeepSeekAdapter.isDeepSeekModel("DeepSeek-Reasoner"));
        }
    }

    @Nested
    @DisplayName("isReasoningModel")
    class IsReasoningModel {

        @Test
        @DisplayName("recognizes deepseek-reasoner as reasoning")
        void recognizesDeepSeekReasoner() {
            assertTrue(DeepSeekAdapter.isReasoningModel("deepseek-reasoner"));
        }

        @Test
        @DisplayName("rejects deepseek-chat (not reasoning)")
        void rejectsDeepSeekChat() {
            assertFalse(DeepSeekAdapter.isReasoningModel("deepseek-chat"));
        }

        @Test
        @DisplayName("rejects deepseek-coder (not reasoning)")
        void rejectsDeepSeekCoder() {
            assertFalse(DeepSeekAdapter.isReasoningModel("deepseek-coder"));
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertFalse(DeepSeekAdapter.isReasoningModel(null));
        }
    }

    @Nested
    @DisplayName("buildRequestBody")
    class BuildRequestBody {

        private List<Map<String, Object>> singleUserMessage() {
            Map<String, Object> msg = new HashMap<>();
            msg.put("role", "user");
            msg.put("content", "Hello");
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(msg);
            return messages;
        }

        @Test
        @DisplayName("basic case: model + messages + temperature + max_tokens, no extras")
        void basicCase() {
            Agent agent = Agent.builder()
                    .model("deepseek-chat")
                    .temperature(0.7)
                    .maxTokens(1024)
                    .build();

            Map<String, Object> body = DeepSeekAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertEquals("deepseek-chat", body.get("model"));
            assertNotNull(body.get("messages"));
            assertEquals(0.7, body.get("temperature"));
            assertEquals(1024, body.get("max_tokens"));
            assertFalse(body.containsKey("tools"), "tools should not be present");
            assertFalse(body.containsKey("response_format"), "response_format should not be present");
        }

        @Test
        @DisplayName("deepseek-reasoner with reasoningEffort set -> body STILL does NOT contain reasoning_effort")
        void reasoningEffortAlwaysExcluded() {
            Agent agent = Agent.builder()
                    .model("deepseek-reasoner")
                    .temperature(0.5)
                    .maxTokens(2048)
                    .reasoningEffort("high")
                    .build();

            Map<String, Object> body = DeepSeekAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertFalse(body.containsKey("reasoning_effort"),
                    "DeepSeek does not accept reasoning_effort; helper must never include it");
        }

        @Test
        @DisplayName("non-empty tools -> body contains tools")
        void toolsIncluded() {
            Agent agent = Agent.builder()
                    .model("deepseek-chat")
                    .maxTokens(1024)
                    .build();

            Map<String, Object> tool = new HashMap<>();
            tool.put("type", "function");
            Map<String, Object> fn = new HashMap<>();
            fn.put("name", "get_weather");
            tool.put("function", fn);
            List<Map<String, Object>> tools = new ArrayList<>();
            tools.add(tool);

            Map<String, Object> body = DeepSeekAdapter.buildRequestBody(
                    agent, singleUserMessage(), tools, null);

            assertTrue(body.containsKey("tools"));
            assertEquals(tools, body.get("tools"));
        }

        @Test
        @DisplayName("non-null responseFormat -> body contains response_format")
        void responseFormatIncluded() {
            Agent agent = Agent.builder()
                    .model("deepseek-chat")
                    .maxTokens(1024)
                    .build();

            Map<String, Object> rf = new HashMap<>();
            rf.put("type", "json_schema");

            Map<String, Object> body = DeepSeekAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, rf);

            assertTrue(body.containsKey("response_format"));
            assertEquals(rf, body.get("response_format"));
        }
    }

    @Nested
    @DisplayName("extractReasoningContent")
    class ExtractReasoningContent {

        @Test
        @DisplayName("map with reasoning_content -> returns the value")
        void extractsValue() {
            Map<String, Object> messageMap = new HashMap<>();
            messageMap.put("role", "assistant");
            messageMap.put("content", "final answer");
            messageMap.put("reasoning_content", "thinking...");

            assertEquals("thinking...", DeepSeekAdapter.extractReasoningContent(messageMap));
        }

        @Test
        @DisplayName("map without reasoning_content -> returns null")
        void absentKeyReturnsNull() {
            Map<String, Object> messageMap = new HashMap<>();
            messageMap.put("role", "assistant");
            messageMap.put("content", "final answer");

            assertNull(DeepSeekAdapter.extractReasoningContent(messageMap));
        }

        @Test
        @DisplayName("null map -> returns null")
        void nullMapReturnsNull() {
            assertNull(DeepSeekAdapter.extractReasoningContent(null));
        }

        @Test
        @DisplayName("map with reasoning_content=null -> returns null")
        void nullValueReturnsNull() {
            Map<String, Object> messageMap = new HashMap<>();
            messageMap.put("role", "assistant");
            messageMap.put("reasoning_content", null);

            assertNull(DeepSeekAdapter.extractReasoningContent(messageMap));
        }
    }
}
