package io.github.yannfavinleveque.agentic.integration;

import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.service.MistralAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for Mistral request body construction. Verifies that
 * {@link MistralAdapter#buildRequestBody} produces a body shape the OpenAI-compat
 * /v1/chat/completions endpoint expects, including the Magistral-specific
 * {@code prompt_mode: "reasoning"} field.
 *
 * <p>This test does NOT exercise the HTTP layer — see {@code CustomProviderIntegrationTest}
 * for end-to-end HTTP routing through a stub server.</p>
 */
class MistralIntegrationTest {

    private static Map<String, Object> textMessage(String role, String content) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @Test
    @DisplayName("Mistral body: model, messages, max_tokens, temperature included")
    void buildsBasicBody() {
        Agent agent = Agent.builder()
                .model("mistral-large-latest")
                .temperature(0.7)
                .maxTokens(2048)
                .build();

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(textMessage("system", "You are a helpful assistant."));
        messages.add(textMessage("user", "Hello!"));

        Map<String, Object> body = MistralAdapter.buildRequestBody(agent, messages, null, null);

        assertEquals("mistral-large-latest", body.get("model"));
        assertEquals(0.7, body.get("temperature"));
        assertEquals(2048, body.get("max_tokens"));
        assertEquals(messages, body.get("messages"));
        assertFalse(body.containsKey("prompt_mode"),
                "non-reasoning Mistral models should not carry prompt_mode");
    }

    @Test
    @DisplayName("Magistral body: prompt_mode = 'reasoning' is auto-added")
    void magistralAddsPromptMode() {
        Agent agent = Agent.builder()
                .model("magistral-medium-latest")
                .maxTokens(4096)
                .build();

        List<Map<String, Object>> messages = Arrays.asList(textMessage("user", "Solve x^2 = 9"));
        Map<String, Object> body = MistralAdapter.buildRequestBody(agent, messages, null, null);

        assertEquals("reasoning", body.get("prompt_mode"),
                "Magistral models must carry prompt_mode='reasoning'");
        assertEquals("magistral-medium-latest", body.get("model"));
    }

    @Test
    @DisplayName("Mistral body: tools array passes through verbatim")
    void toolsPassThrough() {
        Agent agent = Agent.builder()
                .model("mistral-large-latest")
                .maxTokens(1024)
                .build();

        Map<String, Object> tool = new HashMap<>();
        tool.put("type", "function");
        Map<String, Object> fn = new HashMap<>();
        fn.put("name", "get_weather");
        fn.put("description", "Lookup current weather");
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        fn.put("parameters", params);
        tool.put("function", fn);

        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool);

        Map<String, Object> body = MistralAdapter.buildRequestBody(
                agent, Arrays.asList(textMessage("user", "Hi")), tools, null);

        assertTrue(body.containsKey("tools"));
        assertEquals(tools, body.get("tools"));
    }

    @Test
    @DisplayName("Mistral body: response_format pass-through (json_schema)")
    void responseFormatPassThrough() {
        Agent agent = Agent.builder()
                .model("mistral-large-latest")
                .maxTokens(1024)
                .build();

        Map<String, Object> rf = new HashMap<>();
        rf.put("type", "json_schema");
        Map<String, Object> jsonSchema = new HashMap<>();
        jsonSchema.put("name", "weather_response");
        jsonSchema.put("strict", true);
        rf.put("json_schema", jsonSchema);

        Map<String, Object> body = MistralAdapter.buildRequestBody(
                agent, Arrays.asList(textMessage("user", "Hi")), null, rf);

        assertNotNull(body.get("response_format"));
        assertEquals(rf, body.get("response_format"));
    }

    @Test
    @DisplayName("MistralAdapter.sanitizeRole maps developer->system without touching system/user/assistant")
    void roleSanitization() {
        assertEquals("system", MistralAdapter.sanitizeRole("developer"));
        assertEquals("system", MistralAdapter.sanitizeRole("Developer"));
        assertEquals("system", MistralAdapter.sanitizeRole("system"));
        assertEquals("user", MistralAdapter.sanitizeRole("user"));
        assertEquals("assistant", MistralAdapter.sanitizeRole("assistant"));
        assertEquals("tool", MistralAdapter.sanitizeRole("tool"));
        assertEquals("user", MistralAdapter.sanitizeRole(null));
    }
}
