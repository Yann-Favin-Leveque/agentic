package io.github.yannfavinleveque.agentic.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yannfavinleveque.agentic.agent.config.InstanceConfig;
import io.github.yannfavinleveque.agentic.agent.model.ClaudeRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the AWS Bedrock provider wiring on {@link ProviderConfig} and the Bedrock-specific
 * shape of {@link ClaudeRequest} (model id in the URL path, {@code anthropic_version} in the body).
 * These are the load-bearing, network-free pieces of the Bedrock integration:
 * <ul>
 *   <li>model-name → Bedrock model-id mapping (bare {@code claude-*} → {@code anthropic.claude-*};
 *       already-qualified ids / EU inference-profile ids pass through),</li>
 *   <li>InvokeModel path {@code /model/{modelId}/invoke},</li>
 *   <li>Bearer auth header (no x-api-key / anthropic-version header),</li>
 *   <li>no query params,</li>
 *   <li>body omits {@code model} and carries {@code anthropic_version}.</li>
 * </ul>
 */
class BedrockProviderConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    @DisplayName("toBedrockModelId")
    class ToBedrockModelId {

        @Test
        @DisplayName("known bare claude-* name maps to its EU inference profile")
        void mapsKnownNameToEuProfile() {
            assertEquals("eu.anthropic.claude-opus-4-8", ProviderConfig.toBedrockModelId("claude-opus-4-8"));
            assertEquals("eu.anthropic.claude-sonnet-4-6", ProviderConfig.toBedrockModelId("claude-sonnet-4-6"));
            assertEquals("eu.anthropic.claude-haiku-4-5-20251001-v1:0",
                    ProviderConfig.toBedrockModelId("claude-haiku-4-5"));
        }

        @Test
        @DisplayName("unknown bare claude-* name falls back to the anthropic. prefix")
        void fallsBackToAnthropicPrefixForUnknown() {
            assertEquals("anthropic.claude-something-new",
                    ProviderConfig.toBedrockModelId("claude-something-new"));
        }

        @Test
        @DisplayName("already-qualified anthropic.* id passes through unchanged")
        void passesThroughQualifiedId() {
            assertEquals("anthropic.claude-opus-4-8", ProviderConfig.toBedrockModelId("anthropic.claude-opus-4-8"));
        }

        @Test
        @DisplayName("EU inference-profile id passes through unchanged")
        void passesThroughInferenceProfile() {
            assertEquals("eu.anthropic.claude-opus-4-8",
                    ProviderConfig.toBedrockModelId("eu.anthropic.claude-opus-4-8"));
        }
    }

    @Nested
    @DisplayName("getPath (Bedrock)")
    class GetPath {

        @Test
        @DisplayName("CHAT_COMPLETIONS → /model/{modelId}/invoke with mapped model id")
        void invokePath() {
            String path = ProviderConfig.getPath(Provider.BEDROCK,
                    ProviderConfig.Endpoint.CHAT_COMPLETIONS, "claude-opus-4-8");
            assertEquals("/model/eu.anthropic.claude-opus-4-8/invoke", path);
        }

        @Test
        @DisplayName("null model is rejected (model is required in the path)")
        void rejectsNullModel() {
            assertThrows(IllegalArgumentException.class, () -> ProviderConfig.getPath(Provider.BEDROCK,
                    ProviderConfig.Endpoint.CHAT_COMPLETIONS, null));
        }

        @Test
        @DisplayName("non-messages endpoints are unsupported")
        void rejectsOtherEndpoints() {
            assertThrows(UnsupportedOperationException.class, () -> ProviderConfig.getPath(Provider.BEDROCK,
                    ProviderConfig.Endpoint.EMBEDDINGS, "claude-opus-4-8"));
        }
    }

    @Nested
    @DisplayName("getHeaders / getQueryParams (Bedrock)")
    class HeadersAndQuery {

        @Test
        @DisplayName("Bearer auth, no x-api-key, no anthropic-version header")
        void bearerHeaders() {
            Map<String, String> headers = ProviderConfig.getHeaders(Provider.BEDROCK, "SECRET", "bedrock-2023-05-31");
            assertEquals("Bearer SECRET", headers.get("Authorization"));
            assertFalse(headers.containsKey("x-api-key"), "Bedrock must not send x-api-key");
            assertFalse(headers.containsKey("anthropic-version"),
                    "Bedrock carries the version in the body, not a header");
        }

        @Test
        @DisplayName("no query params required")
        void noQueryParams() {
            assertTrue(ProviderConfig.getQueryParams(Provider.BEDROCK, "bedrock-2023-05-31",
                    ProviderConfig.Endpoint.CHAT_COMPLETIONS).isEmpty());
        }

        @Test
        @DisplayName("supportsEndpoint: only CHAT_COMPLETIONS")
        void supportsOnlyMessages() {
            assertTrue(ProviderConfig.supportsEndpoint(Provider.BEDROCK, ProviderConfig.Endpoint.CHAT_COMPLETIONS));
            assertFalse(ProviderConfig.supportsEndpoint(Provider.BEDROCK, ProviderConfig.Endpoint.EMBEDDINGS));
        }
    }

    @Nested
    @DisplayName("ClaudeRequest body for Bedrock")
    class BodyShape {

        @Test
        @DisplayName("model omitted, anthropic_version present when serialized for Bedrock")
        void bedrockBodyOmitsModelHasVersion() throws Exception {
            // Mirrors what ClaudeAdapter builds for a BEDROCK instance: no model, anthropic_version set.
            ClaudeRequest req = ClaudeRequest.builder()
                    .model(null)
                    .anthropicVersion("bedrock-2023-05-31")
                    .maxTokens(1024)
                    .messages(List.of(ClaudeRequest.ClaudeMessage.builder()
                            .role("user").content("hi").build()))
                    .build();

            Map<String, Object> json = MAPPER.readValue(MAPPER.writeValueAsString(req),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

            assertFalse(json.containsKey("model"), "Bedrock body must not contain 'model'");
            assertEquals("bedrock-2023-05-31", json.get("anthropic_version"));
            assertEquals(1024, json.get("max_tokens"));
        }

        @Test
        @DisplayName("non-Bedrock body keeps model and omits anthropic_version")
        void nonBedrockBodyKeepsModel() throws Exception {
            ClaudeRequest req = ClaudeRequest.builder()
                    .model("claude-opus-4-8")
                    .maxTokens(1024)
                    .messages(List.of(ClaudeRequest.ClaudeMessage.builder()
                            .role("user").content("hi").build()))
                    .build();

            Map<String, Object> json = MAPPER.readValue(MAPPER.writeValueAsString(req),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

            assertEquals("claude-opus-4-8", json.get("model"));
            assertFalse(json.containsKey("anthropic_version"),
                    "non-Bedrock body must not contain anthropic_version");
        }
    }

    @Nested
    @DisplayName("InstanceConfig parsing")
    class InstanceConfigParsing {

        @Test
        @DisplayName("provider 'bedrock' is recognized and validates")
        void bedrockInstanceValidates() {
            InstanceConfig ic = InstanceConfig.builder()
                    .id("bedrock-eu")
                    .url("https://bedrock-runtime.eu-west-3.amazonaws.com")
                    .key("SECRET")
                    .models("claude-opus-4-8")
                    .provider("Bedrock") // case-insensitive
                    .apiVersion("bedrock-2023-05-31")
                    .build();

            assertTrue(ic.isBedrock());
            assertDoesNotThrow(ic::validate);
        }
    }
}
