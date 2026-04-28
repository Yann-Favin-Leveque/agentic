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
 * Tests for {@link MistralAdapter} static helpers. Covers model detection (prefix-based,
 * case-insensitive), reasoning detection (Magistral family), role sanitization, and request body
 * construction.
 */
class MistralAdapterTest {

    @Nested
    @DisplayName("isMistralModel")
    class IsMistralModel {

        @Test
        @DisplayName("recognizes mistral-large-latest as Mistral")
        void recognizesMistralLargeLatest() {
            assertTrue(MistralAdapter.isMistralModel("mistral-large-latest"));
        }

        @Test
        @DisplayName("recognizes pixtral-12b as Mistral")
        void recognizesPixtral12b() {
            assertTrue(MistralAdapter.isMistralModel("pixtral-12b"));
        }

        @Test
        @DisplayName("recognizes codestral-latest as Mistral")
        void recognizesCodestralLatest() {
            assertTrue(MistralAdapter.isMistralModel("codestral-latest"));
        }

        @Test
        @DisplayName("recognizes magistral-medium-latest as Mistral")
        void recognizesMagistralMediumLatest() {
            assertTrue(MistralAdapter.isMistralModel("magistral-medium-latest"));
        }

        @Test
        @DisplayName("recognizes ministral-8b-latest as Mistral")
        void recognizesMinistral8bLatest() {
            assertTrue(MistralAdapter.isMistralModel("ministral-8b-latest"));
        }

        @Test
        @DisplayName("recognizes open-mistral-7b as Mistral")
        void recognizesOpenMistral7b() {
            assertTrue(MistralAdapter.isMistralModel("open-mistral-7b"));
        }

        @Test
        @DisplayName("recognizes open-mixtral-8x7b as Mistral")
        void recognizesOpenMixtral8x7b() {
            assertTrue(MistralAdapter.isMistralModel("open-mixtral-8x7b"));
        }

        @Test
        @DisplayName("rejects gpt-4o")
        void rejectsGpt4o() {
            assertFalse(MistralAdapter.isMistralModel("gpt-4o"));
        }

        @Test
        @DisplayName("rejects claude-sonnet-4-5")
        void rejectsClaudeSonnet45() {
            assertFalse(MistralAdapter.isMistralModel("claude-sonnet-4-5"));
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertFalse(MistralAdapter.isMistralModel(null));
        }

        @Test
        @DisplayName("is case-insensitive")
        void caseInsensitive() {
            assertTrue(MistralAdapter.isMistralModel("MISTRAL-LARGE-LATEST"));
            assertTrue(MistralAdapter.isMistralModel("Pixtral-12B"));
        }

    }

    @Nested
    @DisplayName("isReasoningModel")
    class IsReasoningModel {

        @Test
        @DisplayName("recognizes magistral-medium-latest as reasoning")
        void recognizesMagistralMedium() {
            assertTrue(MistralAdapter.isReasoningModel("magistral-medium-latest"));
        }

        @Test
        @DisplayName("recognizes magistral-small-latest as reasoning")
        void recognizesMagistralSmall() {
            assertTrue(MistralAdapter.isReasoningModel("magistral-small-latest"));
        }

        @Test
        @DisplayName("rejects mistral-large-latest (not reasoning)")
        void rejectsMistralLargeLatest() {
            assertFalse(MistralAdapter.isReasoningModel("mistral-large-latest"));
        }

        @Test
        @DisplayName("rejects gpt-4o (not Mistral)")
        void rejectsGpt4o() {
            assertFalse(MistralAdapter.isReasoningModel("gpt-4o"));
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertFalse(MistralAdapter.isReasoningModel(null));
        }

    }

    @Nested
    @DisplayName("sanitizeRole")
    class SanitizeRole {

        @Test
        @DisplayName("maps developer -> system")
        void mapsDeveloperToSystem() {
            assertEquals("system", MistralAdapter.sanitizeRole("developer"));
        }

        @Test
        @DisplayName("keeps user as user")
        void keepsUser() {
            assertEquals("user", MistralAdapter.sanitizeRole("user"));
        }

        @Test
        @DisplayName("lowercases System -> system")
        void lowercasesSystem() {
            assertEquals("system", MistralAdapter.sanitizeRole("System"));
        }

        @Test
        @DisplayName("null -> user")
        void nullToUser() {
            assertEquals("user", MistralAdapter.sanitizeRole(null));
        }

        @Test
        @DisplayName("Developer (mixed case) -> system")
        void mixedCaseDeveloper() {
            assertEquals("system", MistralAdapter.sanitizeRole("Developer"));
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
                    .model("mistral-large-latest")
                    .temperature(0.7)
                    .maxTokens(1024)
                    .build();

            Map<String, Object> body = MistralAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertEquals("mistral-large-latest", body.get("model"));
            assertNotNull(body.get("messages"));
            assertEquals(0.7, body.get("temperature"));
            assertEquals(1024, body.get("max_tokens"));
            assertFalse(body.containsKey("tools"), "tools should not be present");
            assertFalse(body.containsKey("response_format"), "response_format should not be present");
            assertFalse(body.containsKey("prompt_mode"), "prompt_mode should not be present for non-reasoning model");
        }

        @Test
        @DisplayName("magistral model -> prompt_mode: reasoning")
        void magistralAddsPromptMode() {
            Agent agent = Agent.builder()
                    .model("magistral-medium-latest")
                    .temperature(0.5)
                    .maxTokens(2048)
                    .build();

            Map<String, Object> body = MistralAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertEquals("reasoning", body.get("prompt_mode"));
        }

        @Test
        @DisplayName("non-empty tools -> body contains tools")
        void toolsIncluded() {
            Agent agent = Agent.builder()
                    .model("mistral-large-latest")
                    .maxTokens(1024)
                    .build();

            Map<String, Object> tool = new HashMap<>();
            tool.put("type", "function");
            Map<String, Object> fn = new HashMap<>();
            fn.put("name", "get_weather");
            tool.put("function", fn);
            List<Map<String, Object>> tools = new ArrayList<>();
            tools.add(tool);

            Map<String, Object> body = MistralAdapter.buildRequestBody(
                    agent, singleUserMessage(), tools, null);

            assertTrue(body.containsKey("tools"));
            assertEquals(tools, body.get("tools"));
        }

        @Test
        @DisplayName("empty tools list -> body does NOT contain tools")
        void emptyToolsExcluded() {
            Agent agent = Agent.builder()
                    .model("mistral-large-latest")
                    .maxTokens(1024)
                    .build();

            Map<String, Object> body = MistralAdapter.buildRequestBody(
                    agent, singleUserMessage(), Collections.emptyList(), null);

            assertFalse(body.containsKey("tools"));
        }

        @Test
        @DisplayName("non-null responseFormat -> body contains response_format")
        void responseFormatIncluded() {
            Agent agent = Agent.builder()
                    .model("mistral-large-latest")
                    .maxTokens(1024)
                    .build();

            Map<String, Object> rf = new HashMap<>();
            rf.put("type", "json_schema");

            Map<String, Object> body = MistralAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, rf);

            assertTrue(body.containsKey("response_format"));
            assertEquals(rf, body.get("response_format"));
        }

        @Test
        @DisplayName("null maxTokens -> defaults to 32768")
        void defaultMaxTokens() {
            Agent agent = Agent.builder()
                    .model("mistral-large-latest")
                    // no maxTokens
                    .build();

            Map<String, Object> body = MistralAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertEquals(32768, body.get("max_tokens"));
        }

        @Test
        @DisplayName("null temperature -> body does NOT include temperature key")
        void nullTemperatureExcluded() {
            Agent agent = Agent.builder()
                    .model("mistral-large-latest")
                    .maxTokens(1024)
                    // no temperature
                    .build();

            Map<String, Object> body = MistralAdapter.buildRequestBody(
                    agent, singleUserMessage(), null, null);

            assertFalse(body.containsKey("temperature"));
        }

    }

}
