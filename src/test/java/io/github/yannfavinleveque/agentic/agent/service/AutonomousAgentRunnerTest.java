package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.model.FunctionConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
