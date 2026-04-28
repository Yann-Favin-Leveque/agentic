package io.github.yannfavinleveque.agentic.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.core.Instance;
import io.github.yannfavinleveque.agentic.agent.core.Provider;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import io.github.yannfavinleveque.agentic.agent.service.AgentManager;
import io.github.yannfavinleveque.agentic.agent.service.ClaudeAdapter;
import io.github.yannfavinleveque.agentic.agent.service.DeepSeekAdapter;
import io.github.yannfavinleveque.agentic.agent.service.GeminiAdapter;
import io.github.yannfavinleveque.agentic.agent.service.GrokAdapter;
import io.github.yannfavinleveque.agentic.agent.service.InstanceRouter;
import io.github.yannfavinleveque.agentic.agent.service.UnifiedRequestService;
import io.github.yannfavinleveque.agentic.support.HttpHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the new natively wired providers:
 * {@link Provider#GROK Grok}, {@link Provider#AZURE_GROK Azure Grok},
 * {@link Provider#DEEPSEEK DeepSeek} and {@link Provider#GEMINI Gemini}.
 *
 * <p>Two test groups:</p>
 * <ul>
 *   <li>Body-construction tests on each adapter's {@code buildRequestBody} (no HTTP).</li>
 *   <li>Routing tests with a JDK {@link HttpServer} stub: assert the outgoing path,
 *       header, and response parsing (notably DeepSeek's {@code reasoning_content}
 *       prepending with {@code [REASONING]} markers).</li>
 * </ul>
 */
class NewProvidersIntegrationTest {

    private static Map<String, Object> textMessage(String role, String content) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    // ==================== GROK BODY ====================

    @Nested
    @DisplayName("Grok body construction & routing")
    class GrokBody {

        @Test
        @DisplayName("grok-4 + reasoningEffort=high -> body contains reasoning_effort")
        void grokReasoningModelEmitsReasoningEffort() {
            Agent agent = Agent.builder()
                    .model("grok-4")
                    .reasoningEffort("high")
                    .maxTokens(2048)
                    .build();
            List<Map<String, Object>> messages = Arrays.asList(textMessage("user", "Hi"));

            Map<String, Object> body = GrokAdapter.buildRequestBody(agent, messages, null, null);

            assertEquals("high", body.get("reasoning_effort"),
                    "grok-4 must accept reasoning_effort");
            assertEquals("grok-4", body.get("model"));
            assertEquals(messages, body.get("messages"));
            assertEquals(2048, body.get("max_tokens"));
        }

        @Test
        @DisplayName("grok-3 + reasoningEffort=high -> body has NO reasoning_effort")
        void grokNonReasoningModelStripsReasoningEffort() {
            Agent agent = Agent.builder()
                    .model("grok-3")
                    .reasoningEffort("high")
                    .maxTokens(1024)
                    .build();
            List<Map<String, Object>> messages = Arrays.asList(textMessage("user", "Hi"));

            Map<String, Object> body = GrokAdapter.buildRequestBody(agent, messages, null, null);

            assertFalse(body.containsKey("reasoning_effort"),
                    "non-reasoning Grok models must NOT carry reasoning_effort (xAI returns 400 otherwise)");
            assertEquals("grok-3", body.get("model"));
        }

        @Test
        @DisplayName("grok-3-mini + reasoningEffort=medium -> body contains reasoning_effort")
        void grokMiniIsReasoningCapable() {
            Agent agent = Agent.builder()
                    .model("grok-3-mini")
                    .reasoningEffort("medium")
                    .maxTokens(1024)
                    .build();

            Map<String, Object> body = GrokAdapter.buildRequestBody(
                    agent, Arrays.asList(textMessage("user", "Hi")), null, null);

            assertEquals("medium", body.get("reasoning_effort"));
        }

        @Test
        @DisplayName("isGrokModel detects grok-* prefixes (route -> executeGrokRequest)")
        void grokModelDetection() {
            assertTrue(GrokAdapter.isGrokModel("grok-4"));
            assertTrue(GrokAdapter.isGrokModel("grok-4-fast"));
            assertTrue(GrokAdapter.isGrokModel("grok-3-mini"));
            assertTrue(GrokAdapter.isGrokModel("Grok-2-Vision-1212"));
            assertFalse(GrokAdapter.isGrokModel("gpt-4o"));
            assertFalse(GrokAdapter.isGrokModel("claude-sonnet-4-5"));
            assertFalse(GrokAdapter.isGrokModel(null));
        }
    }

    // ==================== DEEPSEEK BODY ====================

    @Nested
    @DisplayName("DeepSeek body construction, routing, and reasoning_content")
    class DeepSeekBody {

        @Test
        @DisplayName("deepseek-reasoner body has NO reasoning_effort (server picks it implicitly)")
        void reasonerBodyHasNoReasoningEffort() {
            Agent agent = Agent.builder()
                    .model("deepseek-reasoner")
                    .reasoningEffort("high")
                    .maxTokens(4096)
                    .build();

            Map<String, Object> body = DeepSeekAdapter.buildRequestBody(
                    agent, Arrays.asList(textMessage("user", "Solve x^2=9")), null, null);

            assertFalse(body.containsKey("reasoning_effort"),
                    "DeepSeek API does not accept reasoning_effort");
            assertEquals("deepseek-reasoner", body.get("model"));
        }

        @Test
        @DisplayName("deepseek-chat body sanity: model + messages + max_tokens")
        void deepseekChatBasicBody() {
            Agent agent = Agent.builder()
                    .model("deepseek-chat")
                    .temperature(0.3)
                    .maxTokens(1024)
                    .build();
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(textMessage("system", "You are concise."));
            messages.add(textMessage("user", "Hi"));

            Map<String, Object> body = DeepSeekAdapter.buildRequestBody(agent, messages, null, null);

            assertEquals("deepseek-chat", body.get("model"));
            assertEquals(messages, body.get("messages"));
            assertEquals(1024, body.get("max_tokens"));
            assertEquals(0.3, body.get("temperature"));
        }

        @Test
        @DisplayName("isDeepSeekModel detects deepseek-* prefixes")
        void deepseekModelDetection() {
            assertTrue(DeepSeekAdapter.isDeepSeekModel("deepseek-chat"));
            assertTrue(DeepSeekAdapter.isDeepSeekModel("deepseek-reasoner"));
            assertTrue(DeepSeekAdapter.isDeepSeekModel("DeepSeek-Reasoner"));
            assertFalse(DeepSeekAdapter.isDeepSeekModel("gpt-4o"));
            assertFalse(DeepSeekAdapter.isDeepSeekModel(null));
        }

        @Test
        @DisplayName("extractReasoningContent recovers reasoning_content from message map")
        void extractReasoningContentDirect() {
            Map<String, Object> messageMap = new HashMap<>();
            messageMap.put("role", "assistant");
            messageMap.put("content", "x = 3 or x = -3");
            messageMap.put("reasoning_content", "We solve x^2 = 9 by taking square roots.");

            String reasoning = DeepSeekAdapter.extractReasoningContent(messageMap);
            assertEquals("We solve x^2 = 9 by taking square roots.", reasoning);
        }

        @Test
        @DisplayName("extractReasoningContent returns null when field is absent")
        void extractReasoningContentMissing() {
            Map<String, Object> messageMap = new HashMap<>();
            messageMap.put("role", "assistant");
            messageMap.put("content", "Plain answer");

            assertNull(DeepSeekAdapter.extractReasoningContent(messageMap));
            assertNull(DeepSeekAdapter.extractReasoningContent(null));
        }
    }

    // ==================== GEMINI BODY ====================

    @Nested
    @DisplayName("Gemini body construction & routing")
    class GeminiBody {

        @Test
        @DisplayName("gemini-2.5-pro + reasoningEffort=high -> body contains reasoning_effort")
        void gemini25ProEmitsReasoningEffort() {
            Agent agent = Agent.builder()
                    .model("gemini-2.5-pro")
                    .reasoningEffort("high")
                    .maxTokens(8192)
                    .build();

            Map<String, Object> body = GeminiAdapter.buildRequestBody(
                    agent, Arrays.asList(textMessage("user", "Reason about X")), null, null);

            assertEquals("high", body.get("reasoning_effort"),
                    "gemini-2.5-pro is a thinking model and must carry reasoning_effort via the shim");
            assertEquals("gemini-2.5-pro", body.get("model"));
        }

        @Test
        @DisplayName("gemini-2.0-flash + reasoningEffort=high -> body has NO reasoning_effort (non-thinking)")
        void gemini20FlashStripsReasoningEffort() {
            Agent agent = Agent.builder()
                    .model("gemini-2.0-flash")
                    .reasoningEffort("high")
                    .maxTokens(4096)
                    .build();

            Map<String, Object> body = GeminiAdapter.buildRequestBody(
                    agent, Arrays.asList(textMessage("user", "Hi")), null, null);

            assertFalse(body.containsKey("reasoning_effort"),
                    "gemini-2.0-flash is a non-thinking model — reasoning_effort must be stripped");
        }

        @Test
        @DisplayName("Gemini body sanity: model + messages + max_tokens always present")
        void geminiBasicBody() {
            Agent agent = Agent.builder()
                    .model("gemini-2.5-flash")
                    .temperature(0.5)
                    .maxTokens(2048)
                    .build();
            List<Map<String, Object>> messages = Arrays.asList(textMessage("user", "Hi"));

            Map<String, Object> body = GeminiAdapter.buildRequestBody(agent, messages, null, null);

            assertNotNull(body.get("model"));
            assertNotNull(body.get("messages"));
            assertNotNull(body.get("max_tokens"));
            assertEquals(messages, body.get("messages"));
            assertEquals(2048, body.get("max_tokens"));
            assertEquals(0.5, body.get("temperature"));
        }

        @Test
        @DisplayName("isGeminiModel detects gemini-* and text-embedding-004")
        void geminiModelDetection() {
            assertTrue(GeminiAdapter.isGeminiModel("gemini-2.5-pro"));
            assertTrue(GeminiAdapter.isGeminiModel("gemini-2.5-flash"));
            assertTrue(GeminiAdapter.isGeminiModel("gemini-2.0-flash"));
            assertTrue(GeminiAdapter.isGeminiModel("text-embedding-004"));
            assertFalse(GeminiAdapter.isGeminiModel("gpt-4o"));
            assertFalse(GeminiAdapter.isGeminiModel(null));
        }

        @Test
        @DisplayName("Tools and response_format pass through verbatim on Gemini body")
        void geminiToolsAndResponseFormat() {
            Agent agent = Agent.builder()
                    .model("gemini-2.5-flash")
                    .maxTokens(1024)
                    .build();

            Map<String, Object> tool = new HashMap<>();
            tool.put("type", "function");
            Map<String, Object> fn = new HashMap<>();
            fn.put("name", "get_weather");
            tool.put("function", fn);
            List<Map<String, Object>> tools = new ArrayList<>();
            tools.add(tool);

            Map<String, Object> rf = new HashMap<>();
            rf.put("type", "json_schema");

            Map<String, Object> body = GeminiAdapter.buildRequestBody(
                    agent, Arrays.asList(textMessage("user", "Hi")), tools, rf);

            assertEquals(tools, body.get("tools"));
            assertEquals(rf, body.get("response_format"));
        }
    }

    // ==================== ROUTING (HTTP STUB) ====================

    /**
     * End-to-end routing tests: spin up a JDK {@link HttpServer} stub, point a {@code Provider.GROK}
     * (resp. DEEPSEEK / GEMINI) instance at it, and assert the path / response handling matches
     * what {@code UnifiedRequestService} sends/parses for each native provider.
     */
    @Nested
    @DisplayName("UnifiedRequestService routing for new providers (HTTP stub)")
    class Routing {

        private HttpServer server;
        private int port;
        private final ObjectMapper objectMapper = new ObjectMapper();

        // Capture of the latest request and the canned response.
        private String capturedPath;
        private Map<String, String> capturedHeaders;
        private Map<String, Object> cannedResponse;

        @BeforeEach
        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = server.getAddress().getPort();
            server.createContext("/", this::handle);
            server.setExecutor(null);
            server.start();
            cannedResponse = baseChatCompletionResponse(
                    "stub answer",
                    null /* no reasoning by default */);
        }

        @AfterEach
        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        private void handle(HttpExchange ex) throws IOException {
            capturedPath = ex.getRequestURI().getPath();
            capturedHeaders = new HashMap<>();
            for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
                capturedHeaders.put(h.getKey().toLowerCase(), h.getValue().get(0));
            }
            // Drain the body but we don't need to inspect it for these tests.
            ex.getRequestBody().readAllBytes();

            byte[] bytes = objectMapper.writeValueAsBytes(cannedResponse);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }

        private Map<String, Object> baseChatCompletionResponse(String content, String reasoningContent) {
            Map<String, Object> message = new HashMap<>();
            message.put("role", "assistant");
            message.put("content", content);
            if (reasoningContent != null) {
                message.put("reasoning_content", reasoningContent);
            }
            Map<String, Object> choice = new HashMap<>();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", "stop");
            Map<String, Object> usage = new HashMap<>();
            usage.put("prompt_tokens", 10);
            usage.put("completion_tokens", 5);
            usage.put("total_tokens", 15);
            Map<String, Object> resp = new HashMap<>();
            resp.put("id", "chatcmpl-stub");
            resp.put("object", "chat.completion");
            resp.put("model", "stub");
            resp.put("choices", Collections.singletonList(choice));
            resp.put("usage", usage);
            return resp;
        }

        private UnifiedRequestService buildService(Instance instance) {
            InstanceRouter router = new InstanceRouter(Collections.singletonList(instance), 50);
            AgentServiceConfig config = AgentServiceConfig.builder()
                    .requestsPerSecond(50)
                    .maxConcurrentStreamsPerInstance(10)
                    .build();
            HttpHelper helper = new HttpHelper(10);
            ClaudeAdapter claude = new ClaudeAdapter(helper);
            AgentManager mgr = new AgentManager(config, helper, router, objectMapper);
            return new UnifiedRequestService(config, helper, router, claude, objectMapper, mgr);
        }

        private Instance grokInstance() {
            return Instance.builder()
                    .id("grok-stub")
                    .baseUrl("http://127.0.0.1:" + port)
                    .apiKey("xai-key")
                    .provider(Provider.GROK)
                    .deployedModels(Collections.singletonList("grok-4"))
                    .build();
        }

        private Instance deepseekInstance() {
            return Instance.builder()
                    .id("deepseek-stub")
                    .baseUrl("http://127.0.0.1:" + port)
                    .apiKey("ds-key")
                    .provider(Provider.DEEPSEEK)
                    .deployedModels(Arrays.asList("deepseek-reasoner", "deepseek-chat"))
                    .build();
        }

        private Instance geminiInstance() {
            return Instance.builder()
                    .id("gemini-stub")
                    .baseUrl("http://127.0.0.1:" + port)
                    .apiKey("gemini-key")
                    .provider(Provider.GEMINI)
                    .deployedModels(Collections.singletonList("gemini-2.5-flash"))
                    .build();
        }

        @Test
        @DisplayName("Grok route -> POST /v1/chat/completions with Bearer auth")
        void grokRoutesToChatCompletions() throws Exception {
            UnifiedRequestService svc = buildService(grokInstance());

            AgentResult result = svc.requestModel("grok-4", "Hello!").get();

            assertEquals("/v1/chat/completions", capturedPath);
            assertEquals("Bearer xai-key", capturedHeaders.get("authorization"));
            assertNotNull(result);
            assertTrue(result.toString().contains("stub answer"),
                    "Grok response should contain the stubbed answer; got: " + result);
        }

        @Test
        @DisplayName("DeepSeek route -> reasoning_content prepended with [REASONING] markers")
        void deepseekReasoningPrepended() throws Exception {
            cannedResponse = baseChatCompletionResponse(
                    "x = 3 or x = -3",
                    "We solve x^2 = 9 by taking square roots.");

            UnifiedRequestService svc = buildService(deepseekInstance());
            AgentResult result = svc.requestModel("deepseek-reasoner", "Solve x^2=9").get();

            String text = result.toString();
            assertEquals("/v1/chat/completions", capturedPath);
            assertTrue(text.contains("[REASONING]"),
                    "[REASONING] opening marker missing: " + text);
            assertTrue(text.contains("[/REASONING]"),
                    "[/REASONING] closing marker missing: " + text);
            assertTrue(text.contains("We solve x^2 = 9"),
                    "reasoning body missing: " + text);
            assertTrue(text.contains("x = 3 or x = -3"),
                    "final answer missing: " + text);
            // Reasoning must come BEFORE the final answer.
            assertTrue(text.indexOf("[REASONING]") < text.indexOf("x = 3 or x = -3"),
                    "[REASONING] block must precede the final answer");
        }

        @Test
        @DisplayName("DeepSeek route without reasoning_content -> behaves like plain chat completions")
        void deepseekWithoutReasoning() throws Exception {
            cannedResponse = baseChatCompletionResponse("plain answer", null);

            UnifiedRequestService svc = buildService(deepseekInstance());
            AgentResult result = svc.requestModel("deepseek-chat", "Hi").get();

            String text = result.toString();
            assertFalse(text.contains("[REASONING]"),
                    "no [REASONING] marker should appear when reasoning_content is absent");
            assertTrue(text.contains("plain answer"));
        }

        @Test
        @DisplayName("Gemini route -> POST /v1beta/openai/chat/completions (the OpenAI shim path)")
        void geminiRoutesToShimPath() throws Exception {
            UnifiedRequestService svc = buildService(geminiInstance());

            AgentResult result = svc.requestModel("gemini-2.5-flash", "Hi").get();

            assertEquals("/v1beta/openai/chat/completions", capturedPath,
                    "Gemini must hit the OpenAI-compat shim path on generativelanguage.googleapis.com");
            assertEquals("Bearer gemini-key", capturedHeaders.get("authorization"));
            assertNotNull(result);
        }
    }
}
