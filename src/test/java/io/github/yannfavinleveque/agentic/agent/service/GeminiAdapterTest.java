package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.core.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GeminiAdapter} static helpers. Covers Gemini model detection (prefix-based,
 * case-insensitive), reasoning detection (Gemini 2.5 + 2.0-flash-thinking), exposed constants, and
 * request body construction (with/without reasoning_effort, tools, response_format).
 */
class GeminiAdapterTest {

    @Nested
    @DisplayName("isGeminiModel")
    class IsGeminiModel {

        @Test
        @DisplayName("recognizes gemini-2.5-pro as Gemini")
        void recognizesGemini25Pro() {
            assertTrue(GeminiAdapter.isGeminiModel("gemini-2.5-pro"));
        }

        @Test
        @DisplayName("recognizes gemini-2.5-flash as Gemini")
        void recognizesGemini25Flash() {
            assertTrue(GeminiAdapter.isGeminiModel("gemini-2.5-flash"));
        }

        @Test
        @DisplayName("recognizes gemini-2.0-flash as Gemini")
        void recognizesGemini20Flash() {
            assertTrue(GeminiAdapter.isGeminiModel("gemini-2.0-flash"));
        }

        @Test
        @DisplayName("recognizes gemini-1.5-pro as Gemini")
        void recognizesGemini15Pro() {
            assertTrue(GeminiAdapter.isGeminiModel("gemini-1.5-pro"));
        }

        @Test
        @DisplayName("recognizes text-embedding-004 as Gemini")
        void recognizesTextEmbedding004() {
            assertTrue(GeminiAdapter.isGeminiModel("text-embedding-004"));
        }

        @Test
        @DisplayName("rejects gpt-4o")
        void rejectsGpt4o() {
            assertFalse(GeminiAdapter.isGeminiModel("gpt-4o"));
        }

        @Test
        @DisplayName("rejects claude-sonnet-4-5")
        void rejectsClaudeSonnet45() {
            assertFalse(GeminiAdapter.isGeminiModel("claude-sonnet-4-5"));
        }

        @Test
        @DisplayName("rejects mistral-large-latest")
        void rejectsMistralLargeLatest() {
            assertFalse(GeminiAdapter.isGeminiModel("mistral-large-latest"));
        }

        @Test
        @DisplayName("rejects grok-4")
        void rejectsGrok4() {
            assertFalse(GeminiAdapter.isGeminiModel("grok-4"));
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertFalse(GeminiAdapter.isGeminiModel(null));
        }

        @Test
        @DisplayName("is case-insensitive")
        void caseInsensitive() {
            assertTrue(GeminiAdapter.isGeminiModel("GEMINI-2.5-PRO"));
            assertTrue(GeminiAdapter.isGeminiModel("Gemini-1.5-Flash"));
        }

    }

    @Nested
    @DisplayName("isReasoningModel")
    class IsReasoningModel {

        @Test
        @DisplayName("recognizes gemini-2.5-pro as reasoning")
        void recognizesGemini25ProReasoning() {
            assertTrue(GeminiAdapter.isReasoningModel("gemini-2.5-pro"));
        }

        @Test
        @DisplayName("recognizes gemini-2.5-flash as reasoning")
        void recognizesGemini25FlashReasoning() {
            assertTrue(GeminiAdapter.isReasoningModel("gemini-2.5-flash"));
        }

        @Test
        @DisplayName("recognizes gemini-2.0-flash-thinking-exp as reasoning")
        void recognizesGemini20FlashThinkingExp() {
            assertTrue(GeminiAdapter.isReasoningModel("gemini-2.0-flash-thinking-exp"));
        }

        @Test
        @DisplayName("rejects gemini-2.0-flash (not a thinking model)")
        void rejectsGemini20Flash() {
            assertFalse(GeminiAdapter.isReasoningModel("gemini-2.0-flash"));
        }

        @Test
        @DisplayName("rejects gemini-1.5-pro (not a thinking model)")
        void rejectsGemini15Pro() {
            assertFalse(GeminiAdapter.isReasoningModel("gemini-1.5-pro"));
        }

        @Test
        @DisplayName("rejects gpt-4o")
        void rejectsGpt4o() {
            assertFalse(GeminiAdapter.isReasoningModel("gpt-4o"));
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertFalse(GeminiAdapter.isReasoningModel(null));
        }

    }

    @Nested
    @DisplayName("constants")
    class Constants {

        @Test
        @DisplayName("DEFAULT_BASE_URL points at generativelanguage.googleapis.com")
        void defaultBaseUrl() {
            assertEquals("https://generativelanguage.googleapis.com", GeminiAdapter.DEFAULT_BASE_URL);
        }

        @Test
        @DisplayName("CHAT_COMPLETIONS_PATH is /v1beta/openai/chat/completions")
        void chatCompletionsPath() {
            assertEquals("/v1beta/openai/chat/completions", GeminiAdapter.CHAT_COMPLETIONS_PATH);
        }

        @Test
        @DisplayName("EMBEDDINGS_PATH is /v1beta/openai/embeddings")
        void embeddingsPath() {
            assertEquals("/v1beta/openai/embeddings", GeminiAdapter.EMBEDDINGS_PATH);
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
                    .model("gemini-2.0-flash")
                    .temperature(0.7)
                    .maxTokens(1024)
                    .build();

            Map<String, Object> body = GeminiAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertEquals("gemini-2.0-flash", body.get("model"));
            assertNotNull(body.get("messages"));
            assertEquals(0.7, body.get("temperature"));
            assertEquals(1024, body.get("max_tokens"));
            assertFalse(body.containsKey("tools"), "tools should not be present");
            assertFalse(body.containsKey("response_format"), "response_format should not be present");
            assertFalse(body.containsKey("reasoning_effort"),
                    "reasoning_effort should not be present without reasoning model");
        }

        @Test
        @DisplayName("gemini-2.5-pro with reasoningEffort=high -> body contains reasoning_effort")
        void reasoningEffortAddedForThinkingModel() {
            Agent agent = Agent.builder()
                    .model("gemini-2.5-pro")
                    .temperature(0.5)
                    .maxTokens(2048)
                    .reasoningEffort("high")
                    .build();

            Map<String, Object> body = GeminiAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertEquals("high", body.get("reasoning_effort"));
        }

        @Test
        @DisplayName("gemini-2.0-flash (non-thinking) with reasoningEffort=high -> body does NOT contain reasoning_effort")
        void reasoningEffortIgnoredForNonThinkingModel() {
            Agent agent = Agent.builder()
                    .model("gemini-2.0-flash")
                    .maxTokens(1024)
                    .reasoningEffort("high")
                    .build();

            Map<String, Object> body = GeminiAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertFalse(body.containsKey("reasoning_effort"),
                    "reasoning_effort must not leak into non-thinking Gemini models");
        }

        @Test
        @DisplayName("null maxTokens -> defaults to 32768")
        void defaultMaxTokens() {
            Agent agent = Agent.builder()
                    .model("gemini-1.5-pro")
                    // no maxTokens
                    .build();

            Map<String, Object> body = GeminiAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertEquals(32768, body.get("max_tokens"));
        }

        @Test
        @DisplayName("non-empty tools -> body contains tools")
        void toolsIncluded() {
            Agent agent = Agent.builder()
                    .model("gemini-2.0-flash")
                    .maxTokens(1024)
                    .build();

            Map<String, Object> tool = new HashMap<>();
            tool.put("type", "function");
            Map<String, Object> fn = new HashMap<>();
            fn.put("name", "get_weather");
            tool.put("function", fn);
            List<Map<String, Object>> tools = new ArrayList<>();
            tools.add(tool);

            Map<String, Object> body = GeminiAdapter.buildRequestBody(
                    agent, singleUserMessage(), tools, null);

            assertTrue(body.containsKey("tools"));
            assertEquals(tools, body.get("tools"));
        }

        @Test
        @DisplayName("empty tools list -> body does NOT contain tools")
        void emptyToolsExcluded() {
            Agent agent = Agent.builder()
                    .model("gemini-2.0-flash")
                    .maxTokens(1024)
                    .build();

            Map<String, Object> body = GeminiAdapter.buildRequestBody(
                    agent, singleUserMessage(), Collections.emptyList(), null);

            assertFalse(body.containsKey("tools"));
        }

        @Test
        @DisplayName("non-null responseFormat -> body contains response_format")
        void responseFormatIncluded() {
            Agent agent = Agent.builder()
                    .model("gemini-2.0-flash")
                    .maxTokens(1024)
                    .build();

            Map<String, Object> rf = new HashMap<>();
            rf.put("type", "json_schema");

            Map<String, Object> body = GeminiAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, rf);

            assertTrue(body.containsKey("response_format"));
            assertEquals(rf, body.get("response_format"));
        }

        @Test
        @DisplayName("null temperature -> body does NOT include temperature key")
        void nullTemperatureExcluded() {
            Agent agent = Agent.builder()
                    .model("gemini-2.0-flash")
                    .maxTokens(1024)
                    // no temperature
                    .build();

            Map<String, Object> body = GeminiAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertFalse(body.containsKey("temperature"));
        }

        @Test
        @DisplayName("empty reasoningEffort string on thinking model -> body does NOT contain reasoning_effort")
        void emptyReasoningEffortIgnored() {
            Agent agent = Agent.builder()
                    .model("gemini-2.5-pro")
                    .maxTokens(1024)
                    .reasoningEffort("")
                    .build();

            Map<String, Object> body = GeminiAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertFalse(body.containsKey("reasoning_effort"));
        }

    }

}
