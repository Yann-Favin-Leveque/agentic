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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GrokAdapter} static helpers. Covers Grok model detection (prefix-based,
 * case-insensitive), reasoning detection (grok-3-mini and grok-4* families), and request body
 * construction.
 */
class GrokAdapterTest {

    @Nested
    @DisplayName("isGrokModel")
    class IsGrokModel {

        @Test
        @DisplayName("recognizes grok-4 as Grok")
        void recognizesGrok4() {
            assertTrue(GrokAdapter.isGrokModel("grok-4"));
        }

        @Test
        @DisplayName("recognizes grok-4-fast as Grok")
        void recognizesGrok4Fast() {
            assertTrue(GrokAdapter.isGrokModel("grok-4-fast"));
        }

        @Test
        @DisplayName("recognizes grok-3-mini as Grok")
        void recognizesGrok3Mini() {
            assertTrue(GrokAdapter.isGrokModel("grok-3-mini"));
        }

        @Test
        @DisplayName("recognizes grok-2-vision-1212 as Grok")
        void recognizesGrok2Vision() {
            assertTrue(GrokAdapter.isGrokModel("grok-2-vision-1212"));
        }

        @Test
        @DisplayName("recognizes grok-code-fast-1 as Grok")
        void recognizesGrokCodeFast() {
            assertTrue(GrokAdapter.isGrokModel("grok-code-fast-1"));
        }

        @Test
        @DisplayName("rejects gpt-4o")
        void rejectsGpt4o() {
            assertFalse(GrokAdapter.isGrokModel("gpt-4o"));
        }

        @Test
        @DisplayName("rejects claude-sonnet-4-5")
        void rejectsClaudeSonnet45() {
            assertFalse(GrokAdapter.isGrokModel("claude-sonnet-4-5"));
        }

        @Test
        @DisplayName("rejects mistral-large-latest")
        void rejectsMistralLargeLatest() {
            assertFalse(GrokAdapter.isGrokModel("mistral-large-latest"));
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertFalse(GrokAdapter.isGrokModel(null));
        }

        @Test
        @DisplayName("is case-insensitive")
        void caseInsensitive() {
            assertTrue(GrokAdapter.isGrokModel("GROK-4"));
            assertTrue(GrokAdapter.isGrokModel("Grok-3-Mini"));
        }

    }

    @Nested
    @DisplayName("isReasoningModel")
    class IsReasoningModel {

        @Test
        @DisplayName("recognizes grok-3-mini as reasoning")
        void recognizesGrok3Mini() {
            assertTrue(GrokAdapter.isReasoningModel("grok-3-mini"));
        }

        @Test
        @DisplayName("recognizes grok-4 as reasoning")
        void recognizesGrok4() {
            assertTrue(GrokAdapter.isReasoningModel("grok-4"));
        }

        @Test
        @DisplayName("recognizes grok-4-fast as reasoning")
        void recognizesGrok4Fast() {
            assertTrue(GrokAdapter.isReasoningModel("grok-4-fast"));
        }

        @Test
        @DisplayName("rejects grok-3 (not reasoning)")
        void rejectsGrok3() {
            assertFalse(GrokAdapter.isReasoningModel("grok-3"));
        }

        @Test
        @DisplayName("rejects grok-2-vision-1212 (not reasoning)")
        void rejectsGrok2Vision() {
            assertFalse(GrokAdapter.isReasoningModel("grok-2-vision-1212"));
        }

        @Test
        @DisplayName("rejects grok-code-fast-1 (not reasoning)")
        void rejectsGrokCodeFast() {
            assertFalse(GrokAdapter.isReasoningModel("grok-code-fast-1"));
        }

        @Test
        @DisplayName("rejects gpt-4o (not Grok)")
        void rejectsGpt4o() {
            assertFalse(GrokAdapter.isReasoningModel("gpt-4o"));
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertFalse(GrokAdapter.isReasoningModel(null));
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
                    .model("grok-3")
                    .temperature(0.7)
                    .maxTokens(1024)
                    .build();

            Map<String, Object> body = GrokAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertEquals("grok-3", body.get("model"));
            assertNotNull(body.get("messages"));
            assertEquals(0.7, body.get("temperature"));
            assertEquals(1024, body.get("max_tokens"));
            assertFalse(body.containsKey("tools"), "tools should not be present");
            assertFalse(body.containsKey("response_format"), "response_format should not be present");
            assertFalse(body.containsKey("reasoning_effort"),
                    "reasoning_effort should not be present for non-reasoning model");
        }

        @Test
        @DisplayName("grok-4 + reasoningEffort=high -> body contains reasoning_effort=high")
        void grok4ReasoningEffortIncluded() {
            Agent agent = Agent.builder()
                    .model("grok-4")
                    .temperature(0.5)
                    .maxTokens(2048)
                    .reasoningEffort("high")
                    .build();

            Map<String, Object> body = GrokAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertEquals("high", body.get("reasoning_effort"));
        }

        @Test
        @DisplayName("grok-3 (non-reasoning) + reasoningEffort=high -> body does NOT contain reasoning_effort")
        void grok3NonReasoningEffortExcluded() {
            Agent agent = Agent.builder()
                    .model("grok-3")
                    .temperature(0.5)
                    .maxTokens(2048)
                    .reasoningEffort("high")
                    .build();

            Map<String, Object> body = GrokAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertFalse(body.containsKey("reasoning_effort"),
                    "non-reasoning model must not receive reasoning_effort");
        }

        @Test
        @DisplayName("grok-3-mini + reasoningEffort=null -> body does NOT contain reasoning_effort")
        void grok3MiniNullReasoningEffort() {
            Agent agent = Agent.builder()
                    .model("grok-3-mini")
                    .temperature(0.5)
                    .maxTokens(2048)
                    // no reasoningEffort
                    .build();

            Map<String, Object> body = GrokAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertFalse(body.containsKey("reasoning_effort"));
        }

        @Test
        @DisplayName("non-empty tools -> body contains tools")
        void toolsIncluded() {
            Agent agent = Agent.builder()
                    .model("grok-3")
                    .maxTokens(1024)
                    .build();

            Map<String, Object> tool = new HashMap<>();
            tool.put("type", "function");
            Map<String, Object> fn = new HashMap<>();
            fn.put("name", "get_weather");
            tool.put("function", fn);
            List<Map<String, Object>> tools = new ArrayList<>();
            tools.add(tool);

            Map<String, Object> body = GrokAdapter.buildRequestBody(
                    agent, singleUserMessage(), tools, null);

            assertTrue(body.containsKey("tools"));
            assertEquals(tools, body.get("tools"));
        }

        @Test
        @DisplayName("non-null responseFormat -> body contains response_format")
        void responseFormatIncluded() {
            Agent agent = Agent.builder()
                    .model("grok-3")
                    .maxTokens(1024)
                    .build();

            Map<String, Object> rf = new HashMap<>();
            rf.put("type", "json_schema");

            Map<String, Object> body = GrokAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, rf);

            assertTrue(body.containsKey("response_format"));
            assertEquals(rf, body.get("response_format"));
        }

        @Test
        @DisplayName("null maxTokens -> defaults to 32768")
        void defaultMaxTokens() {
            Agent agent = Agent.builder()
                    .model("grok-3")
                    // no maxTokens
                    .build();

            Map<String, Object> body = GrokAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertEquals(32768, body.get("max_tokens"));
        }

        @Test
        @DisplayName("null temperature -> body does NOT include temperature key")
        void nullTemperatureExcluded() {
            Agent agent = Agent.builder()
                    .model("grok-3")
                    .maxTokens(1024)
                    // no temperature
                    .build();

            Map<String, Object> body = GrokAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertFalse(body.containsKey("temperature"));
        }

    }

}
