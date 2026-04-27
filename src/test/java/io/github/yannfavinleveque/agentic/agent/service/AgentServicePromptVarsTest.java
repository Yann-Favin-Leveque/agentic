package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.config.RetryConfig;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.exception.AgentException;
import io.github.yannfavinleveque.agentic.agent.exception.MissingPromptVariableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the Mustache prompt-variable feature on {@link AgentService}.
 * <p>
 * No real LLM calls — the {@link AgentService} is constructed in degraded mode (no
 * instances) so the only way a request can fail is either:
 * <ul>
 *   <li>{@link MissingPromptVariableException} — thrown synchronously by the
 *       prompt-substitution layer BEFORE any HTTP routing</li>
 *   <li>{@code NO_INSTANCE_AVAILABLE} — thrown asynchronously by the unified
 *       request service when it tries to pick an instance</li>
 * </ul>
 * The discriminator between the two error codes is enough to prove that
 * substitution is wired in correctly: when the template references a missing
 * var, we get the prompt error; when the substitution succeeds, the call
 * proceeds far enough to fail on instance routing instead.
 */
class AgentServicePromptVarsTest {

    private AgentService agentService;

    /** Zero-retry config so NO_INSTANCE_AVAILABLE failures surface immediately
     *  instead of triggering the exponential backoff loop (which would push
     *  individual tests past 10 minutes). */
    private static final RetryConfig NO_RETRIES = RetryConfig.builder()
            .networkRetries(0)
            .maxTokenRetries(0)
            .deserializationRetries(0)
            .maxIterationRetries(0)
            .contentFilterRetries(0)
            .build();

    @BeforeEach
    void setup(@TempDir Path tempDir) {
        AgentServiceConfig config = AgentServiceConfig.builder()
                .agentJsonFolderPath(tempDir.toString())
                .defaultRetryConfig(NO_RETRIES)
                .build();
        agentService = new AgentService(config);
    }

    @Test
    void missingPromptVar_throwsBeforeAnyHttpCall() {
        String id = "missing-" + UUID.randomUUID();
        agentService.registerAgent(Agent.builder()
                .id(id)
                .model("gpt-4o")
                .instructions("Hello {{user}}, welcome.")
                .build());

        // Synchronous throw (var resolution happens before the async request).
        MissingPromptVariableException ex = assertThrows(
                MissingPromptVariableException.class,
                () -> agentService.requestAgent(id, "hi", (String) null, Map.of()).join()
        );
        assertEquals(id, ex.getAgentId());
        assertEquals("user", ex.getVariableName());
        assertEquals(AgentException.ErrorCode.MISSING_PROMPT_VARIABLE, ex.getErrorCode());
    }

    @Test
    void missingPromptVar_alsoThrownByHistoryOverload() {
        String id = "history-" + UUID.randomUUID();
        agentService.registerAgent(Agent.builder()
                .id(id)
                .model("gpt-4o")
                .instructions("Greeting: {{name}}")
                .build());

        MissingPromptVariableException ex = assertThrows(
                MissingPromptVariableException.class,
                () -> agentService.requestAgent(id, "hi", (List<io.github.yannfavinleveque.agentic.agent.model.Message>) null, Map.of()).join()
        );
        assertEquals("name", ex.getVariableName());
    }

    @Test
    void missingPromptVar_alsoThrownBySingleArgOverload() {
        String id = "single-" + UUID.randomUUID();
        agentService.registerAgent(Agent.builder()
                .id(id)
                .model("gpt-4o")
                .instructions("You are {{role}}.")
                .build());

        MissingPromptVariableException ex = assertThrows(
                MissingPromptVariableException.class,
                () -> agentService.requestAgent(id, "hi", Map.of()).join()
        );
        assertEquals("role", ex.getVariableName());
    }

    @Test
    void missingPromptVar_throwsForVisionOverload() {
        String id = "vision-" + UUID.randomUUID();
        agentService.registerAgent(Agent.builder()
                .id(id)
                .model("gpt-4o")
                .instructions("Look at the {{topic}}.")
                .build());

        MissingPromptVariableException ex = assertThrows(
                MissingPromptVariableException.class,
                () -> agentService.requestAgentVision(id, "hi", "fake-base64", Map.of()).join()
        );
        assertEquals("topic", ex.getVariableName());
    }

    @Test
    void substitutionSucceeds_thenFailsOnDegradedInstanceRouting() {
        // When the var IS provided, substitution succeeds and the call proceeds to
        // the unified request service, which fails with NO_INSTANCE_AVAILABLE
        // because we're in degraded mode. Reaching that error code is proof the
        // prompt-substitution stage did not block the call.
        String id = "ok-" + UUID.randomUUID();
        agentService.registerAgent(Agent.builder()
                .id(id)
                .model("gpt-4o")
                .instructions("Hello {{user}}!")
                .build());

        Map<String, Object> vars = new HashMap<>();
        vars.put("user", "Alice");

        Throwable err = assertThrows(
                CompletionException.class,
                () -> agentService.requestAgent(id, "hi", (String) null, vars).join()
        );
        Throwable cause = unwrap(err);
        assertTrue(cause instanceof AgentException, "expected AgentException, got " + cause);
        assertEquals(AgentException.ErrorCode.NO_INSTANCE_AVAILABLE,
                ((AgentException) cause).getErrorCode(),
                "substitution must succeed and the call must reach instance routing");
    }

    @Test
    void backwardCompat_existingOverloadsStillWork_noVarsTemplate() {
        // An agent whose instructions contain NO {{...}} placeholders must work
        // unchanged through the legacy overloads — no clone, no exception.
        String id = "compat-" + UUID.randomUUID();
        agentService.registerAgent(Agent.builder()
                .id(id)
                .model("gpt-4o")
                .instructions("static prompt with no placeholders")
                .build());

        // Legacy 3-arg overload (String conversationId).
        Throwable err = assertThrows(
                CompletionException.class,
                () -> agentService.requestAgent(id, "hi", (String) null).join()
        );
        Throwable cause = unwrap(err);
        assertTrue(cause instanceof AgentException);
        assertEquals(AgentException.ErrorCode.NO_INSTANCE_AVAILABLE,
                ((AgentException) cause).getErrorCode(),
                "legacy overload without promptVars must reach instance routing untouched");
    }

    @Test
    void backwardCompat_templateWithVarsButNullPromptVars_throwsMissingPromptVariable() {
        // null promptVars on the new overload behaves identically to legacy
        // overloads when the template has placeholders: the var IS missing.
        String id = "nullvars-" + UUID.randomUUID();
        agentService.registerAgent(Agent.builder()
                .id(id)
                .model("gpt-4o")
                .instructions("Hello {{user}}")
                .build());

        // Legacy overload (no promptVars param) with a template that has vars
        // -> the var is missing -> throws synchronously.
        MissingPromptVariableException ex = assertThrows(
                MissingPromptVariableException.class,
                () -> agentService.requestAgent(id, "hi", (String) null).join()
        );
        assertEquals("user", ex.getVariableName());
    }

    @Test
    void agentWithoutInstructions_isNotCloned() {
        // Sanity: when the agent has no instructions at all, the resolve helper
        // must return the original agent reference (no allocation, no exception).
        // We verify behaviourally: legacy overload reaches instance routing.
        String id = "noinstructions-" + UUID.randomUUID();
        agentService.registerAgent(Agent.builder()
                .id(id)
                .model("gpt-4o")
                // no instructions
                .build());

        Throwable err = assertThrows(
                CompletionException.class,
                () -> agentService.requestAgent(id, "hi", Map.of("ignored", "value")).join()
        );
        Throwable cause = unwrap(err);
        assertTrue(cause instanceof AgentException);
        assertEquals(AgentException.ErrorCode.NO_INSTANCE_AVAILABLE,
                ((AgentException) cause).getErrorCode());

        // Bonus: confirm the registered agent still has null instructions
        // (resolve must never have mutated it).
        assertNotNull(agentService.getAgent(id));
        assertEquals(null, agentService.getAgent(id).getInstructions());
    }

    @Test
    void registeredAgent_notMutatedBySubstitution() {
        // The whole reason we use toBuilder() is to avoid mutating the agent
        // shared with concurrent callers. Verify the registered agent's
        // instructions are unchanged after a request that did substitution.
        String id = "nomutate-" + UUID.randomUUID();
        String original = "Hello {{user}}!";
        Agent registered = Agent.builder()
                .id(id)
                .model("gpt-4o")
                .instructions(original)
                .build();
        agentService.registerAgent(registered);

        // Trigger a request that performs substitution (will fail on degraded
        // routing, but resolve happens first regardless).
        try {
            agentService.requestAgent(id, "hi", (String) null, Map.of("user", "Alice")).join();
        } catch (Exception ignored) {
            // expected: NO_INSTANCE_AVAILABLE
        }

        Agent after = agentService.getAgent(id);
        assertSame(registered, after, "AgentManager must still hold the same instance");
        assertEquals(original, after.getInstructions(),
                "registered agent's instructions must NOT be mutated by the request");
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException || t instanceof ExecutionException) {
            if (t.getCause() == null) break;
            t = t.getCause();
        }
        return t;
    }
}
