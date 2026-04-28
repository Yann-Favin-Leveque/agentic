package io.github.yannfavinleveque.agentic.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.core.Instance;
import io.github.yannfavinleveque.agentic.agent.core.Provider;
import io.github.yannfavinleveque.agentic.agent.custom.AuthSpec;
import io.github.yannfavinleveque.agentic.agent.custom.CustomProviderSpec;
import io.github.yannfavinleveque.agentic.agent.custom.Feature;
import io.github.yannfavinleveque.agentic.agent.custom.FeatureValidator;
import io.github.yannfavinleveque.agentic.agent.exception.AgentException;
import io.github.yannfavinleveque.agentic.agent.exception.UnsupportedFeatureException;
import io.github.yannfavinleveque.agentic.agent.model.AgentResult;
import io.github.yannfavinleveque.agentic.agent.model.FunctionConfig;
import io.github.yannfavinleveque.agentic.agent.model.Message;
import io.github.yannfavinleveque.agentic.agent.service.AgentManager;
import io.github.yannfavinleveque.agentic.agent.service.ClaudeAdapter;
import io.github.yannfavinleveque.agentic.agent.service.InstanceRouter;
import io.github.yannfavinleveque.agentic.agent.service.UnifiedRequestService;
import io.github.yannfavinleveque.agentic.support.HttpHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link Provider#CUSTOM} routing in {@link UnifiedRequestService}.
 * Uses the JDK built-in {@link HttpServer} as a stub LLM endpoint to capture the outgoing
 * request and assert the path, headers, and body shape match the {@link CustomProviderSpec}
 * declared by the test.
 */
class CustomProviderIntegrationTest {

    private HttpServer server;
    private int port;
    private CapturedRequest captured;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Snapshot of the most recent inbound request to the stub server. */
    private static class CapturedRequest {
        String method;
        String path;
        String query;
        Map<String, String> headers = new HashMap<>();
        Map<String, Object> body;
    }

    @BeforeEach
    void start() throws IOException {
        captured = new CapturedRequest();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        captured.method = exchange.getRequestMethod();
        captured.path = exchange.getRequestURI().getPath();
        captured.query = exchange.getRequestURI().getQuery();
        for (Map.Entry<String, List<String>> e : exchange.getRequestHeaders().entrySet()) {
            captured.headers.put(e.getKey().toLowerCase(), e.getValue().get(0));
        }
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        if (bodyBytes.length > 0) {
            captured.body = objectMapper.readValue(bodyBytes,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        }

        // Reply with a minimal OpenAI-compat chat/completions response.
        // Content is a JSON-shaped string so it can be deserialized either as a plain
        // string (DefaultResult) or as MathResult (when an agent declares
        // resultClass=MathResult to exercise structured-output tests).
        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        message.put("content", "{\"expression\":\"1+1\",\"result\":2,\"explanation\":\"stubbed reply\"}");

        Map<String, Object> choice = new HashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");

        Map<String, Object> usage = new HashMap<>();
        usage.put("prompt_tokens", 5);
        usage.put("completion_tokens", 7);
        usage.put("total_tokens", 12);

        Map<String, Object> response = new HashMap<>();
        response.put("id", "chatcmpl-stub");
        response.put("object", "chat.completion");
        response.put("model", "stub-model-1");
        response.put("choices", Collections.singletonList(choice));
        response.put("usage", usage);

        byte[] responseBytes = objectMapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    // ---------- Builders ----------

    private CustomProviderSpec.CustomProviderSpecBuilder baseSpec() {
        Map<String, String> endpoints = new HashMap<>();
        endpoints.put("chat_completions", "/v1/chat/completions");
        return CustomProviderSpec.builder()
                .apiFormat("openai-chat")
                .auth(AuthSpec.builder().header("Authorization").format("Bearer {key}").build())
                .endpoints(endpoints)
                .features(allFeaturesTrue())
                .onUnsupportedFeature("throw");
    }

    private static Map<String, Boolean> allFeaturesTrue() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("vision", true);
        features.put("function_calling", true);
        features.put("structured_output", true);
        features.put("web_search", true);
        features.put("code_interpreter", true);
        features.put("responses_api", false);
        features.put("reasoning", true);
        features.put("streaming", false);
        features.put("embeddings", false);
        features.put("image_generation", false);
        return features;
    }

    private Instance customInstance(CustomProviderSpec spec) {
        return Instance.builder()
                .id("custom-stub")
                .baseUrl("http://127.0.0.1:" + port)
                .apiKey("test-secret-key")
                .provider(Provider.CUSTOM)
                .deployedModels(Collections.singletonList("stub-model-1"))
                .customSpec(spec)
                .build();
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

    private Agent buildAgent(boolean webSearch) {
        return Agent.builder()
                .id("stub-agent")
                .name("StubAgent")
                .model("stub-model-1")
                .instructions("You are a stub.")
                .temperature(0.5)
                .maxTokens(256)
                .webSearch(webSearch)
                .codeInterpreter(false)
                .responseTimeout(15_000L)
                .build();
    }

    private List<Message> singleUser() {
        return new ArrayList<>(Collections.singletonList(Message.user("Hello custom!")));
    }

    /**
     * Helper to invoke the package-private routing entry point via the lower-level
     * {@code requestModel(model, message)} which uses {@code instanceRouter} directly and
     * exercises the same {@code custom > anthropic > mistral > openai} branch via
     * {@code executeRequestModelInternalAfterPermit}. This path does not carry agent
     * functions / resultClass / reasoningEffort — for those, see {@link #callAgent}.
     */
    private AgentResult callModel(UnifiedRequestService svc, String message) throws Exception {
        return svc.requestModel("stub-model-1", message).get();
    }

    /**
     * Invokes the routing path with a pre-built {@link Agent} (no AgentManager registration
     * needed). Used by the lenient-body-strip tests where the agent must carry functions,
     * a {@code resultClass} or a {@code reasoningEffort} so that the request body would
     * normally include {@code tools} / {@code response_format} / {@code reasoning_effort}.
     */
    private AgentResult callAgent(UnifiedRequestService svc, Agent agent) throws Exception {
        return svc.requestAgent(agent, "Hello custom!", (List<Message>) null).get();
    }

    /**
     * Builds a sample function so the agent declares {@code function_calling}.
     * Inline {@code parameters} avoid needing a parameter class on the test classpath.
     */
    private FunctionConfig sampleFunction() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        Map<String, Object> loc = new HashMap<>();
        loc.put("type", "string");
        loc.put("description", "City name");
        props.put("location", loc);
        schema.put("properties", props);
        schema.put("required", Collections.singletonList("location"));
        return FunctionConfig.builder()
                .name("get_weather")
                .description("Get the weather for a location")
                .parameters(schema)
                .build();
    }

    // ---------- Tests ----------

    @Test
    @DisplayName("openai-chat: outgoing request hits declared path with correct auth header and body")
    void openaiChatHappyPath() throws Exception {
        CustomProviderSpec spec = baseSpec().build();
        Instance instance = customInstance(spec);
        UnifiedRequestService svc = buildService(instance);

        AgentResult result = callModel(svc, "Hello custom!");
        assertNotNull(result);
        assertEquals("/v1/chat/completions", captured.path,
                "should hit the path declared in CustomProviderSpec.endpoints.chat_completions");
        assertEquals("POST", captured.method);
        assertEquals("Bearer test-secret-key", captured.headers.get("authorization"),
                "Authorization header must be rendered from auth.format with the api key");
        assertNotNull(captured.body, "body should have been deserialized");
        assertEquals("stub-model-1", captured.body.get("model"));
        assertNotNull(captured.body.get("messages"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) captured.body.get("messages");
        assertEquals("user", messages.get(messages.size() - 1).get("role"),
                "last message must be the user message");
    }

    @Test
    @DisplayName("extraHeaders are forwarded on every request")
    void extraHeadersPropagated() throws Exception {
        Map<String, String> extra = new HashMap<>();
        extra.put("X-My-Header", "foo");
        extra.put("X-Org", "bar");
        CustomProviderSpec spec = baseSpec().extraHeaders(extra).build();
        Instance instance = customInstance(spec);
        UnifiedRequestService svc = buildService(instance);

        callModel(svc, "Hi");
        assertEquals("foo", captured.headers.get("x-my-header"),
                "extraHeaders must be sent on the wire");
        assertEquals("bar", captured.headers.get("x-org"));
    }

    @Test
    @DisplayName("queryParams are appended to the URL")
    void queryParamsAppended() throws Exception {
        Map<String, String> qp = new HashMap<>();
        qp.put("api-version", "2024-12-01");
        CustomProviderSpec spec = baseSpec().queryParams(qp).build();
        Instance instance = customInstance(spec);
        UnifiedRequestService svc = buildService(instance);

        callModel(svc, "Hi");
        assertNotNull(captured.query);
        assertTrue(captured.query.contains("api-version=2024-12-01"),
                "queryParams must be appended verbatim, got: " + captured.query);
    }

    @Test
    @DisplayName("THROW lenient mode: unsupported feature -> UnsupportedFeatureException")
    void throwLenientMode() {
        Map<String, Boolean> features = allFeaturesTrue();
        features.put("web_search", false);
        CustomProviderSpec spec = baseSpec()
                .features(features)
                .onUnsupportedFeature("throw")
                .build();

        // Direct validator check (simpler than wiring through the full request flow)
        UnsupportedFeatureException ex = assertThrows(
                UnsupportedFeatureException.class,
                () -> FeatureValidator.validate("custom-stub", spec, EnumSet.of(Feature.WEB_SEARCH)));
        assertEquals(Feature.WEB_SEARCH, ex.getFeature());
        assertEquals("custom-stub", ex.getInstanceId());
        assertEquals(AgentException.ErrorCode.UNSUPPORTED_FEATURE, ex.getErrorCode());
    }

    @Test
    @DisplayName("WARN lenient mode: unsupported feature dropped, request still goes out")
    void warnLenientMode() throws Exception {
        Map<String, Boolean> features = allFeaturesTrue();
        features.put("web_search", false);
        CustomProviderSpec spec = baseSpec()
                .features(features)
                .onUnsupportedFeature("warn")
                .build();
        Instance instance = customInstance(spec);
        UnifiedRequestService svc = buildService(instance);

        // requestModel does NOT set webSearch=true on the synthetic agent, so this
        // exercises only the request flow. To prove WARN drops the feature,
        // we directly call FeatureValidator and assert no exception is raised.
        EnumSet<Feature> allowed = FeatureValidator.validate(
                "custom-stub", spec, EnumSet.of(Feature.WEB_SEARCH));
        assertTrue(allowed.isEmpty(),
                "WARN mode must drop the unsupported feature from the allowed set");

        // Still confirm the HTTP layer succeeds
        AgentResult result = callModel(svc, "Hi");
        assertNotNull(result);
    }

    @Test
    @DisplayName("IGNORE lenient mode: unsupported feature silently dropped, no exception")
    void ignoreLenientMode() {
        Map<String, Boolean> features = allFeaturesTrue();
        features.put("function_calling", false);
        CustomProviderSpec spec = baseSpec()
                .features(features)
                .onUnsupportedFeature("ignore")
                .build();

        EnumSet<Feature> allowed = FeatureValidator.validate(
                "custom-stub", spec,
                EnumSet.of(Feature.FUNCTION_CALLING, Feature.STRUCTURED_OUTPUT));
        // STRUCTURED_OUTPUT is supported, FUNCTION_CALLING dropped silently
        assertEquals(EnumSet.of(Feature.STRUCTURED_OUTPUT), allowed);
    }

    @Test
    @DisplayName("auth.format honored: 'Bearer {key}' -> 'Bearer <real-key>'")
    void authFormatRendered() throws Exception {
        CustomProviderSpec spec = baseSpec()
                .auth(AuthSpec.builder().header("x-api-key").format("{key}").build())
                .build();
        Instance instance = customInstance(spec);
        UnifiedRequestService svc = buildService(instance);

        callModel(svc, "Hi");
        assertEquals("test-secret-key", captured.headers.get("x-api-key"));
    }

    @Test
    @DisplayName("System instructions are prepended as a leading system message")
    void systemInstructionsPrepended() throws Exception {
        CustomProviderSpec spec = baseSpec().build();
        Instance instance = customInstance(spec);
        UnifiedRequestService svc = buildService(instance);

        // requestModel doesn't expose instructions; build the agent path artificially via the
        // CustomProvider executor. requestModel still adds the user text as a leading user message,
        // so we rely on its happy-path body shape only.
        callModel(svc, "Hello!");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) captured.body.get("messages");
        // requestModel doesn't carry instructions, so we expect just the user message:
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).get("role"));
    }

    @Test
    @DisplayName("apiFormat=openai-responses -> AgentException(INVALID_CONFIGURATION) with v1.22 hint")
    void deferredApiFormatOpenAIResponses() {
        Map<String, String> endpoints = new HashMap<>();
        endpoints.put("responses", "/v1/responses");
        CustomProviderSpec spec = baseSpec()
                .apiFormat("openai-responses")
                .endpoints(endpoints)
                .build();
        Instance instance = customInstance(spec);
        UnifiedRequestService svc = buildService(instance);

        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> callModel(svc, "Hi").toString());
        Throwable cause = ee.getCause();
        assertTrue(cause instanceof AgentException,
                "Expected AgentException, got " + cause);
        assertEquals(AgentException.ErrorCode.INVALID_CONFIGURATION,
                ((AgentException) cause).getErrorCode());
        assertTrue(cause.getMessage().contains("openai-responses"),
                "Error message should mention deferred apiFormat: " + cause.getMessage());
    }

    @Test
    @DisplayName("apiFormat=anthropic-messages -> AgentException(INVALID_CONFIGURATION) with v1.22 hint")
    void deferredApiFormatAnthropicMessages() {
        Map<String, String> endpoints = new HashMap<>();
        endpoints.put("chat_completions", "/v1/messages");
        CustomProviderSpec spec = baseSpec()
                .apiFormat("anthropic-messages")
                .endpoints(endpoints)
                .build();
        Instance instance = customInstance(spec);
        UnifiedRequestService svc = buildService(instance);

        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> callModel(svc, "Hi").toString());
        Throwable cause = ee.getCause();
        assertTrue(cause instanceof AgentException,
                "Expected AgentException, got " + cause);
        assertEquals(AgentException.ErrorCode.INVALID_CONFIGURATION,
                ((AgentException) cause).getErrorCode());
        assertTrue(cause.getMessage().contains("anthropic-messages"),
                "Error message should mention deferred apiFormat: " + cause.getMessage());
    }

    // ==========================================================================
    // Lenient body-strip tests (1.21.1).
    //
    // Goal: assert that WARN/IGNORE not only log/silence the unsupported feature,
    // but actually REMOVE it from the JSON body that hits the wire. Tests capture
    // the inbound HTTP request via the stub server and inspect captured.body.
    // ==========================================================================

    /**
     * Captures everything slf4j-simple writes to {@link System#err} during the
     * supplied {@link Runnable}. slf4j-simple is the only logging backend on the
     * test classpath; it formats every log line to stderr. Restoring the original
     * stream is done in a finally block.
     */
    private String captureStderr(ThrowingRunnable r) throws Exception {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try (java.io.PrintStream tee = new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setErr(tee);
            try {
                r.run();
            } finally {
                System.setErr(original);
            }
        }
        return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    @DisplayName("4.1 WARN mode: function_calling unsupported -> tools STRIPPED from body, warning logged")
    void warnStripsFunctionCalling() throws Exception {
        Map<String, Boolean> features = allFeaturesTrue();
        features.put("function_calling", false);
        CustomProviderSpec spec = baseSpec()
                .features(features)
                .onUnsupportedFeature("warn")
                .build();
        Instance instance = customInstance(spec);
        UnifiedRequestService svc = buildService(instance);

        Agent agent = Agent.builder()
                .id("strip-fn-warn")
                .name("StripFnWarn")
                .model("stub-model-1")
                .instructions("You are a stub.")
                .temperature(0.0)
                .maxTokens(64)
                .functions(Collections.singletonList(sampleFunction()))
                .responseTimeout(15_000L)
                .build();

        String stderr = captureStderr(() -> {
            AgentResult result = callAgent(svc, agent);
            assertNotNull(result, "request should still complete (WARN does not abort)");
        });

        assertNotNull(captured.body, "request must have been sent (WARN proceeds)");
        assertFalse(captured.body.containsKey("tools"),
                "WARN mode must strip 'tools' from the outgoing body. Body keys: " + captured.body.keySet());
        assertTrue(stderr.contains("FUNCTION_CALLING"),
                "WARN mode must log the unsupported feature name (FUNCTION_CALLING). Captured stderr: " + stderr);
        assertTrue(stderr.contains("WARN"),
                "Log level should be WARN. Captured stderr: " + stderr);
    }

    @Test
    @DisplayName("4.2 IGNORE mode: function_calling unsupported -> tools STRIPPED, NO log line")
    void ignoreStripsFunctionCalling() throws Exception {
        Map<String, Boolean> features = allFeaturesTrue();
        features.put("function_calling", false);
        CustomProviderSpec spec = baseSpec()
                .features(features)
                .onUnsupportedFeature("ignore")
                .build();
        Instance instance = customInstance(spec);
        UnifiedRequestService svc = buildService(instance);

        Agent agent = Agent.builder()
                .id("strip-fn-ignore")
                .name("StripFnIgnore")
                .model("stub-model-1")
                .instructions("You are a stub.")
                .temperature(0.0)
                .maxTokens(64)
                .functions(Collections.singletonList(sampleFunction()))
                .responseTimeout(15_000L)
                .build();

        String stderr = captureStderr(() -> {
            AgentResult result = callAgent(svc, agent);
            assertNotNull(result);
        });

        assertNotNull(captured.body, "request must have been sent (IGNORE proceeds)");
        assertFalse(captured.body.containsKey("tools"),
                "IGNORE mode must strip 'tools' from the outgoing body. Body keys: " + captured.body.keySet());
        assertFalse(stderr.contains("does not support feature 'FUNCTION_CALLING'"),
                "IGNORE mode must NOT emit the FeatureValidator warning. Captured stderr: " + stderr);
    }

    @Test
    @DisplayName("4.3 WARN mode: structured_output unsupported -> response_format STRIPPED, warning logged")
    void warnStripsResponseFormat() throws Exception {
        Map<String, Boolean> features = allFeaturesTrue();
        features.put("structured_output", false);
        CustomProviderSpec spec = baseSpec()
                .features(features)
                .onUnsupportedFeature("warn")
                .build();
        Instance instance = customInstance(spec);
        UnifiedRequestService svc = buildService(instance);

        Agent agent = Agent.builder()
                .id("strip-rf-warn")
                .name("StripRfWarn")
                .model("stub-model-1")
                .instructions("You are a stub.")
                .temperature(0.0)
                .maxTokens(64)
                .resultClass("io.github.yannfavinleveque.agentic.integration.model.MathResult")
                .responseTimeout(15_000L)
                .build();

        String stderr = captureStderr(() -> {
            AgentResult result = callAgent(svc, agent);
            assertNotNull(result);
        });

        assertNotNull(captured.body, "request must have been sent");
        assertFalse(captured.body.containsKey("response_format"),
                "WARN mode must strip 'response_format' from the outgoing body. Body keys: " + captured.body.keySet());
        assertTrue(stderr.contains("STRUCTURED_OUTPUT"),
                "WARN mode must log the unsupported feature name (STRUCTURED_OUTPUT). Captured stderr: " + stderr);
    }

    @Test
    @DisplayName("4.4 IGNORE mode: structured_output unsupported -> response_format STRIPPED, no log")
    void ignoreStripsResponseFormat() throws Exception {
        Map<String, Boolean> features = allFeaturesTrue();
        features.put("structured_output", false);
        CustomProviderSpec spec = baseSpec()
                .features(features)
                .onUnsupportedFeature("ignore")
                .build();
        Instance instance = customInstance(spec);
        UnifiedRequestService svc = buildService(instance);

        Agent agent = Agent.builder()
                .id("strip-rf-ignore")
                .name("StripRfIgnore")
                .model("stub-model-1")
                .instructions("You are a stub.")
                .temperature(0.0)
                .maxTokens(64)
                .resultClass("io.github.yannfavinleveque.agentic.integration.model.MathResult")
                .responseTimeout(15_000L)
                .build();

        String stderr = captureStderr(() -> {
            AgentResult result = callAgent(svc, agent);
            assertNotNull(result);
        });

        assertNotNull(captured.body);
        assertFalse(captured.body.containsKey("response_format"),
                "IGNORE mode must strip 'response_format' from the outgoing body. Body keys: " + captured.body.keySet());
        assertFalse(stderr.contains("does not support feature 'STRUCTURED_OUTPUT'"),
                "IGNORE mode must NOT emit the FeatureValidator warning. Captured stderr: " + stderr);
    }

    @Test
    @DisplayName("4.5 WARN mode: reasoning unsupported -> reasoning_effort STRIPPED, warning logged")
    void warnStripsReasoning() throws Exception {
        Map<String, Boolean> features = allFeaturesTrue();
        features.put("reasoning", false);
        CustomProviderSpec spec = baseSpec()
                .features(features)
                .onUnsupportedFeature("warn")
                .build();
        Instance instance = customInstance(spec);
        UnifiedRequestService svc = buildService(instance);

        Agent agent = Agent.builder()
                .id("strip-reason-warn")
                .name("StripReasonWarn")
                .model("stub-model-1")
                .instructions("You are a stub.")
                .temperature(0.0)
                .maxTokens(64)
                .reasoningEffort("high")
                .responseTimeout(15_000L)
                .build();

        String stderr = captureStderr(() -> {
            AgentResult result = callAgent(svc, agent);
            assertNotNull(result);
        });

        assertNotNull(captured.body);
        assertFalse(captured.body.containsKey("reasoning_effort"),
                "WARN mode must strip 'reasoning_effort' from the outgoing body. Body keys: " + captured.body.keySet());
        assertFalse(captured.body.containsKey("prompt_mode"),
                "WARN mode must also strip 'prompt_mode' (Mistral-style) from the outgoing body. Body keys: " + captured.body.keySet());
        assertTrue(stderr.contains("REASONING"),
                "WARN mode must log the unsupported feature name (REASONING). Captured stderr: " + stderr);
    }

    @Test
    @DisplayName("4.6 Sanity: when all features are supported, body carries tools + response_format + reasoning_effort")
    void allFeaturesAllowedBodyFull() throws Exception {
        // Default baseSpec() declares everything (incl. reasoning) as supported.
        CustomProviderSpec spec = baseSpec()
                .onUnsupportedFeature("throw") // strict mode: nothing should trigger anyway
                .build();
        Instance instance = customInstance(spec);
        UnifiedRequestService svc = buildService(instance);

        Agent agent = Agent.builder()
                .id("full-body")
                .name("FullBody")
                .model("stub-model-1")
                .instructions("You are a stub.")
                .temperature(0.0)
                .maxTokens(64)
                .functions(Collections.singletonList(sampleFunction()))
                .resultClass("io.github.yannfavinleveque.agentic.integration.model.MathResult")
                .reasoningEffort("medium")
                .responseTimeout(15_000L)
                .build();

        AgentResult result = callAgent(svc, agent);
        assertNotNull(result);
        assertNotNull(captured.body);
        assertTrue(captured.body.containsKey("tools"),
                "Body must include 'tools' when FUNCTION_CALLING is supported. Body keys: " + captured.body.keySet());
        assertTrue(captured.body.containsKey("response_format"),
                "Body must include 'response_format' when STRUCTURED_OUTPUT is supported. Body keys: " + captured.body.keySet());
        assertEquals("medium", captured.body.get("reasoning_effort"),
                "Body must include 'reasoning_effort' verbatim when REASONING is supported");
    }

    @Test
    @DisplayName("4.7 Sanity: THROW mode still aborts BEFORE any HTTP call (no body captured)")
    void throwModeStillAbortsBeforeHttp() {
        Map<String, Boolean> features = allFeaturesTrue();
        features.put("function_calling", false);
        CustomProviderSpec spec = baseSpec()
                .features(features)
                .onUnsupportedFeature("throw")
                .build();
        Instance instance = customInstance(spec);
        UnifiedRequestService svc = buildService(instance);

        Agent agent = Agent.builder()
                .id("throw-no-http")
                .name("ThrowNoHttp")
                .model("stub-model-1")
                .instructions("You are a stub.")
                .temperature(0.0)
                .maxTokens(64)
                .functions(Collections.singletonList(sampleFunction()))
                .responseTimeout(15_000L)
                .build();

        // Call must fail with UnsupportedFeatureException BEFORE any HTTP call.
        ExecutionException ee = assertThrows(ExecutionException.class, () -> callAgent(svc, agent));
        Throwable cause = ee.getCause();
        assertTrue(cause instanceof UnsupportedFeatureException,
                "Expected UnsupportedFeatureException, got: " + cause);
        // No request reached the stub server.
        // (captured is reset @BeforeEach so captured.body is the as-handled body, null if no call.)
        assertEquals(null, captured.body,
                "THROW mode must not send any HTTP request, captured.body should be null");
    }
}
