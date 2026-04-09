package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.model.FunctionConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the optional {@code disableTaskOver} and
 * {@code maxIterationsUnlimited} flags on {@link Agent}, exercised through the
 * package-private hooks on {@link AutonomousAgentRunner}.
 *
 * <p>These tests deliberately avoid constructing a full AgentService stack —
 * they only hit {@link AutonomousAgentRunner#buildVirtualAgent(Agent)} and the
 * static {@link AutonomousAgentRunner#isMaxIterationsExceeded(Agent, int, int)}
 * helper, which is enough to validate the new behaviour without touching the
 * LLM HTTP pipeline.
 */
class AutonomousAgentRunnerTest {

    // buildVirtualAgent uses config only when agent.resultClass != null, so for
    // these tests (no resultClass) we can safely pass nulls to the runner.
    private final AutonomousAgentRunner runner = new AutonomousAgentRunner(null, null, null);

    // ============================================================
    // disableTaskOver
    // ============================================================

    @Test
    void defaultAgent_injectsTaskOverFunctionAndInstruction() {
        Agent agent = Agent.builder()
                .id("default-agent")
                .model("gpt-4o")
                .instructions("Do the thing.")
                .autonomous(true)
                .functions(List.of(sampleFunction("search")))
                .build();

        Agent virtual = runner.buildVirtualAgent(agent);

        assertTrue(containsFunction(virtual, ToolBuilder.TASK_OVER_FUNCTION_NAME),
                "default behaviour must inject the task_over tool");
        assertEquals(2, virtual.getFunctions().size(),
                "virtual agent must expose user tools + task_over");
        assertTrue(virtual.getInstructions().contains(ToolBuilder.TASK_OVER_FUNCTION_NAME),
                "default behaviour must mention task_over in the instructions");
    }

    @Test
    void disableTaskOver_removesTaskOverFunctionAndInstruction() {
        Agent agent = Agent.builder()
                .id("immortal-agent")
                .model("gpt-4o")
                .instructions("Just keep going.")
                .autonomous(true)
                .disableTaskOver(true)
                .functions(List.of(sampleFunction("look_around"), sampleFunction("walk")))
                .build();

        Agent virtual = runner.buildVirtualAgent(agent);

        assertFalse(containsFunction(virtual, ToolBuilder.TASK_OVER_FUNCTION_NAME),
                "disableTaskOver=true must strip the task_over tool");
        assertEquals(2, virtual.getFunctions().size(),
                "only the user-supplied tools should remain");
        assertFalse(virtual.getInstructions().contains(ToolBuilder.TASK_OVER_FUNCTION_NAME),
                "instructions must not mention task_over when disableTaskOver=true");
        assertEquals("Just keep going.", virtual.getInstructions(),
                "instructions must be left untouched when disableTaskOver=true");
    }

    @Test
    void disableTaskOver_preservesNullInstructionsAsEmpty() {
        Agent agent = Agent.builder()
                .id("bare-agent")
                .model("gpt-4o")
                .autonomous(true)
                .disableTaskOver(true)
                .build();

        Agent virtual = runner.buildVirtualAgent(agent);

        assertNotNull(virtual.getInstructions(),
                "virtual agent instructions must never be null");
        assertEquals("", virtual.getInstructions(),
                "instructions must stay empty when the original had none and disableTaskOver=true");
        assertTrue(virtual.getFunctions().isEmpty(),
                "virtual agent functions list must stay empty when no user tools and disableTaskOver=true");
    }

    // ============================================================
    // maxIterationsUnlimited
    // ============================================================

    @Test
    void maxIterationsExceeded_defaultAgent_exceedsAtLimit() {
        Agent agent = Agent.builder()
                .id("bounded")
                .model("gpt-4o")
                .autonomous(true)
                .maxIterations(5)
                .build();

        assertFalse(AutonomousAgentRunner.isMaxIterationsExceeded(agent, 4, 5),
                "iteration 4 < 5 must NOT be flagged as exceeded");
        assertTrue(AutonomousAgentRunner.isMaxIterationsExceeded(agent, 5, 5),
                "iteration 5 == max 5 must be flagged as exceeded");
        assertTrue(AutonomousAgentRunner.isMaxIterationsExceeded(agent, 12, 5),
                "iteration 12 > max 5 must be flagged as exceeded");
    }

    @Test
    void maxIterationsUnlimited_neverExceeds() {
        Agent agent = Agent.builder()
                .id("immortal")
                .model("gpt-4o")
                .autonomous(true)
                .maxIterations(5)          // kept for documentation
                .maxIterationsUnlimited(true)
                .build();

        assertFalse(AutonomousAgentRunner.isMaxIterationsExceeded(agent, 5, 5),
                "unlimited must never flag the loop as exceeded at the nominal max");
        assertFalse(AutonomousAgentRunner.isMaxIterationsExceeded(agent, 10_000, 5),
                "unlimited must never flag the loop as exceeded even well past max");
        assertFalse(AutonomousAgentRunner.isMaxIterationsExceeded(agent, Integer.MAX_VALUE, 5),
                "unlimited must hold up at the integer ceiling");
    }

    // ============================================================
    // Default values for the new flags (backwards compatibility)
    // ============================================================

    @Test
    void newFlagsDefaultToFalse_forLegacyAgents() {
        Agent agent = Agent.builder()
                .id("legacy")
                .model("gpt-4o")
                .autonomous(true)
                .build();

        assertFalse(Boolean.TRUE.equals(agent.getDisableTaskOver()),
                "disableTaskOver must default to false for legacy agents");
        assertFalse(Boolean.TRUE.equals(agent.getMaxIterationsUnlimited()),
                "maxIterationsUnlimited must default to false for legacy agents");
        assertNull(agent.getMaxConversationTokens(),
                "maxConversationTokens must default to null (disabled) for legacy agents");
    }

    // ============================================================
    // maxConversationTokens — per-iteration truncation (OPT-1, 1.16.0)
    // ============================================================
    //
    // These tests exercise the exact call the runner makes inside
    // executeLoop: conversationManager.truncateByTokenBudget(convId, budget).
    // They avoid standing up a full AgentService + HTTP stack, which would
    // be needed to drive a real iteration — per the worker prompt this level
    // of unit coverage is sufficient and the integration is covered
    // downstream by the agent_simulation smoke test.

    @Test
    void perIterationTruncation_isNoOpWhenUnderBudget() {
        Agent agent = Agent.builder()
                .id("npc-under-budget")
                .model("gpt-4o")
                .autonomous(true)
                .maxIterationsUnlimited(true)
                .disableTaskOver(true)
                .maxConversationTokens(10_000)
                .build();

        ConversationManager cm = new ConversationManager();
        String convId = cm.createConversation();
        // Five small assistant messages — well under 10k tokens
        for (int i = 0; i < 5; i++) {
            cm.addAssistantMessage(convId, "short message #" + i);
        }
        int sizeBefore = cm.getHistory(convId).size();
        assertEquals(5, sizeBefore, "precondition: conversation seeded with 5 messages");

        int removed = cm.truncateByTokenBudget(convId, agent.getMaxConversationTokens());

        assertEquals(0, removed,
                "under-budget conversation must not lose any messages");
        assertEquals(5, cm.getHistory(convId).size(),
                "under-budget conversation size must be unchanged");
    }

    @Test
    void perIterationTruncation_fires_whenOverBudget() {
        Agent agent = Agent.builder()
                .id("npc-over-budget")
                .model("gpt-4o")
                .autonomous(true)
                .maxIterationsUnlimited(true)
                .disableTaskOver(true)
                .maxConversationTokens(200)
                .build();

        ConversationManager cm = new ConversationManager();
        String convId = cm.createConversation();

        // 10 large assistant messages, ~100 estimated tokens each
        // (estimateTokens uses chars/4 + small overhead per message; 400 chars ≈ 100 tokens).
        String bigPayload = repeat('a', 400);
        for (int i = 0; i < 10; i++) {
            cm.addAssistantMessage(convId, bigPayload);
        }
        int sizeBefore = cm.getHistory(convId).size();
        assertEquals(10, sizeBefore, "precondition: conversation seeded with 10 large messages");

        int removed = cm.truncateByTokenBudget(convId, agent.getMaxConversationTokens());

        assertTrue(removed > 0,
                "over-budget conversation must drop at least one message");
        int sizeAfter = cm.getHistory(convId).size();
        assertTrue(sizeAfter < sizeBefore,
                "conversation size must shrink after truncation");
        // Sanity check: the remaining messages' estimated token cost must be ≤ budget.
        // A second call with the same budget is a no-op on a now-in-budget conversation.
        int secondRemoved = cm.truncateByTokenBudget(convId, agent.getMaxConversationTokens());
        assertEquals(0, secondRemoved,
                "after truncation the conversation must already be within budget");
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static FunctionConfig sampleFunction(String name) {
        return FunctionConfig.builder()
                .name(name)
                .description("test function " + name)
                .build();
    }

    private static boolean containsFunction(Agent agent, String name) {
        if (agent.getFunctions() == null) return false;
        for (FunctionConfig fc : agent.getFunctions()) {
            if (name.equals(fc.getName())) return true;
        }
        return false;
    }
}
