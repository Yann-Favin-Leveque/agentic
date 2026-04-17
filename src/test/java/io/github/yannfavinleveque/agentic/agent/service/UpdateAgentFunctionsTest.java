package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.config.AgentServiceConfig;
import io.github.yannfavinleveque.agentic.agent.core.Agent;
import io.github.yannfavinleveque.agentic.agent.model.FunctionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AgentService#updateAgentFunctions(String, List)} — the mid-loop function-list
 * refresh API. Exercises the propagation to virtual autonomous children, the group filter, the
 * task_over auto-injection re-application, the unknown-parent noop and concurrent-safety.
 *
 * <p>No LLM calls are made; we construct an {@link AgentService} with an empty instances JSON
 * (degraded mode) and register agents programmatically.
 */
class UpdateAgentFunctionsTest {

    private AgentService agentService;

    @BeforeEach
    void setup(@TempDir Path tempDir) {
        // Degraded mode (no instances) + a real empty folder avoids the classpath extraction path.
        AgentServiceConfig config = AgentServiceConfig.builder()
                .agentJsonFolderPath(tempDir.toString())
                .build();
        agentService = new AgentService(config);
    }

    // ============================================================
    // propagation to parent + virtual child
    // ============================================================

    @Test
    void updates_propagate_to_parent_and_virtual_child_with_group_filter() {
        String parentId = "parent-" + UUID.randomUUID();
        Agent parent = Agent.builder()
                .id(parentId)
                .model("gpt-4o")
                .instructions("do stuff")
                .autonomous(true)
                .enabledToolGroups(Set.of("web"))
                .functions(List.of(fn("a", null), fn("b", "web"), fn("c", "shell")))
                .build();
        agentService.registerAgent(parent);

        // Register a simulated virtual child (as AutonomousAgentRunner would).
        Agent child = Agent.builder()
                .id(parentId + "-autonomous-abcd1234")
                .model("gpt-4o")
                .instructions("virtual")
                .autonomous(false)
                // Initial child state: after applyGroupFilter + task_over injection.
                .functions(new ArrayList<>(List.of(fn("a", null), fn("b", "web"))))
                .build();
        agentService.registerAgent(child);

        List<FunctionConfig> newList = new ArrayList<>(List.of(
                fn("a", null), fn("b", "web"), fn("c", "shell"),
                fn("d", "web"), fn("e", "pinned:42")));

        int updated = agentService.updateAgentFunctions(parentId, newList);

        assertEquals(2, updated, "parent + 1 child updated");
        // Parent stores the full unfiltered list.
        List<FunctionConfig> parentFns = agentService.getAgent(parentId).getFunctions();
        assertEquals(5, parentFns.size());
        assertEquals("a", parentFns.get(0).getName());

        // Child gets the filtered list (group=web only) + auto task_over appended.
        List<FunctionConfig> childFns = agentService.getAgent(child.getId()).getFunctions();
        List<String> childNames = new ArrayList<>();
        for (FunctionConfig fc : childFns) childNames.add(fc.getName());
        assertTrue(childNames.contains("a"), "child keeps ungrouped function");
        assertTrue(childNames.contains("b"), "child keeps enabled-group function");
        assertTrue(childNames.contains("d"), "child keeps newly added enabled-group function");
        assertTrue(!childNames.contains("c"), "child drops disabled-group function");
        assertTrue(!childNames.contains("e"), "child drops unenabled pinned group");
        assertTrue(childNames.contains(ToolBuilder.TASK_OVER_FUNCTION_NAME),
                "task_over re-injected after update");
    }

    // ============================================================
    // concurrent writes
    // ============================================================

    @Test
    void concurrent_updates_complete_without_exception() throws Exception {
        String parentId = "parent-" + UUID.randomUUID();
        Agent parent = Agent.builder()
                .id(parentId)
                .model("gpt-4o")
                .autonomous(true)
                .functions(new ArrayList<>(List.of(fn("a", null))))
                .build();
        agentService.registerAgent(parent);

        List<FunctionConfig> listA = List.of(fn("a", null), fn("b", null));
        List<FunctionConfig> listB = List.of(fn("a", null), fn("c", null));

        CompletableFuture<Void> t1 = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 200; i++) agentService.updateAgentFunctions(parentId, listA);
        });
        CompletableFuture<Void> t2 = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 200; i++) agentService.updateAgentFunctions(parentId, listB);
        });
        AtomicReference<Throwable> readerError = new AtomicReference<>();
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < 2000; i++) {
                    List<FunctionConfig> snap = agentService.getAgent(parentId).getFunctions();
                    // Just iterate — must not blow up.
                    for (FunctionConfig fc : snap) { assertNotNull(fc.getName()); }
                }
            } catch (Throwable t) {
                readerError.set(t);
            }
        });
        CompletableFuture.allOf(t1, t2, reader).get();
        if (readerError.get() != null) throw new AssertionError("reader threw", readerError.get());

        // Final state must be one of the two writes (both have size 2).
        List<FunctionConfig> finalFns = agentService.getAgent(parentId).getFunctions();
        assertEquals(2, finalFns.size());
    }

    // ============================================================
    // unknown parent
    // ============================================================

    @Test
    void unknown_parent_returns_zero_without_exception() {
        int updated = agentService.updateAgentFunctions("does-not-exist-" + UUID.randomUUID(),
                List.of(fn("x", null)));
        assertEquals(0, updated);
    }

    // ============================================================
    // task_over auto-injection preserved on update
    // ============================================================

    @Test
    void child_without_endsTurn_function_gets_task_over_reinjected() {
        String parentId = "parent-" + UUID.randomUUID();
        Agent parent = Agent.builder()
                .id(parentId)
                .model("gpt-4o")
                .autonomous(true)
                .functions(new ArrayList<>(List.of(fn("a", null))))
                .build();
        agentService.registerAgent(parent);

        Agent child = Agent.builder()
                .id(parentId + "-autonomous-11111111")
                .model("gpt-4o")
                .autonomous(false)
                .functions(new ArrayList<>(List.of(fn("a", null))))
                .build();
        agentService.registerAgent(child);

        agentService.updateAgentFunctions(parentId, List.of(fn("x", null), fn("y", null)));
        List<FunctionConfig> childFns = agentService.getAgent(child.getId()).getFunctions();
        boolean hasTaskOver = false;
        for (FunctionConfig fc : childFns) if (ToolBuilder.TASK_OVER_FUNCTION_NAME.equals(fc.getName())) hasTaskOver = true;
        assertTrue(hasTaskOver, "task_over must be appended after update");
        assertEquals(3, childFns.size(), "x + y + task_over");
    }

    private static FunctionConfig fn(String name, String group) {
        return FunctionConfig.builder()
                .name(name)
                .description("desc " + name)
                .group(group)
                .build();
    }
}
