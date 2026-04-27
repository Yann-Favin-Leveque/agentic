package io.github.yannfavinleveque.agentic.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yannfavinleveque.agentic.agent.core.Instance;
import io.github.yannfavinleveque.agentic.agent.core.Provider;
import io.github.yannfavinleveque.agentic.agent.exception.NoInstanceAvailableException;
import io.github.yannfavinleveque.agentic.agent.model.AgentDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the per-agent instance allow-list feature added in v1.20.2.
 * Covers:
 * <ul>
 *     <li>{@link InstanceRouter#getNextInstanceForModel(String, List)} overload</li>
 *     <li>{@link AgentDefinition#getInstances()} JSON deserialization (new + legacy)</li>
 * </ul>
 */
class InstanceRouterAllowListTest {

    private static Instance instance(String id, String... models) {
        return Instance.builder()
                .id(id)
                .baseUrl("https://" + id + ".example.com")
                .apiKey("test-key")
                .provider(Provider.OPENAI)
                .deployedModels(Arrays.asList(models))
                .build();
    }

    private InstanceRouter routerWithThreeInstances() {
        // openai-main: gpt-4o, gpt-4o-mini
        // azure-2:    gpt-4o
        // claude-1:   claude-sonnet-4-5
        return new InstanceRouter(
                Arrays.asList(
                        instance("openai-main", "gpt-4o", "gpt-4o-mini"),
                        instance("azure-2", "gpt-4o"),
                        instance("claude-1", "claude-sonnet-4-5")),
                10);
    }

    @Nested
    @DisplayName("InstanceRouter allow-list overload")
    class RouterTests {

        @Test
        @DisplayName("null allow-list → legacy behavior (all compatible instances)")
        void nullAllowListUsesLegacy() {
            InstanceRouter router = routerWithThreeInstances();

            // gpt-4o is on openai-main (idx=0) and azure-2 (idx=1).
            // Round-robin should yield both indexes over 4 calls.
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < 4; i++) {
                seen.add(router.getNextInstanceForModel("gpt-4o", null));
            }
            assertEquals(new HashSet<>(Arrays.asList(0, 1)), seen);
        }

        @Test
        @DisplayName("empty allow-list → legacy behavior (all compatible instances)")
        void emptyAllowListUsesLegacy() {
            InstanceRouter router = routerWithThreeInstances();

            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < 4; i++) {
                seen.add(router.getNextInstanceForModel("gpt-4o", Collections.emptyList()));
            }
            assertEquals(new HashSet<>(Arrays.asList(0, 1)), seen);
        }

        @Test
        @DisplayName("specific match: single id → router always picks that instance")
        void specificMatchSingleId() {
            InstanceRouter router = routerWithThreeInstances();

            for (int i = 0; i < 5; i++) {
                int idx = router.getNextInstanceForModel("gpt-4o", Collections.singletonList("openai-main"));
                assertEquals(0, idx, "Should always pick openai-main");
            }
        }

        @Test
        @DisplayName("specific match: two ids → round-robin within allow-list only")
        void specificMatchTwoIds() {
            InstanceRouter router = routerWithThreeInstances();

            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < 6; i++) {
                int idx = router.getNextInstanceForModel("gpt-4o",
                        Arrays.asList("openai-main", "azure-2"));
                seen.add(idx);
            }
            assertEquals(new HashSet<>(Arrays.asList(0, 1)), seen);
        }

        @Test
        @DisplayName("allow-list excludes the only instance with the model → exception")
        void modelNotExposedByAllowedInstance() {
            InstanceRouter router = routerWithThreeInstances();

            NoInstanceAvailableException ex = assertThrows(NoInstanceAvailableException.class,
                    () -> router.getNextInstanceForModel("claude-sonnet-4-5",
                            Collections.singletonList("openai-main")));
            assertNotNull(ex.getMessage());
            assertTrue(ex.getMessage().contains("claude-sonnet-4-5"),
                    "Message must mention the model: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("openai-main"),
                    "Message must mention the allow-list entry: " + ex.getMessage());
        }

        @Test
        @DisplayName("allow-list contains only an unknown id → exception")
        void allowListUnknownId() {
            InstanceRouter router = routerWithThreeInstances();

            NoInstanceAvailableException ex = assertThrows(NoInstanceAvailableException.class,
                    () -> router.getNextInstanceForModel("gpt-4o",
                            Collections.singletonList("nonexistent")));
            assertTrue(ex.getMessage().contains("nonexistent"),
                    "Message must mention the unknown id: " + ex.getMessage());
        }

        @Test
        @DisplayName("specific match: id present but model not deployed there → exception")
        void allowedInstanceLacksModel() {
            InstanceRouter router = routerWithThreeInstances();

            // azure-2 has only gpt-4o, not gpt-4o-mini.
            assertThrows(NoInstanceAvailableException.class,
                    () -> router.getNextInstanceForModel("gpt-4o-mini",
                            Collections.singletonList("azure-2")));
        }

        @Test
        @DisplayName("legacy single-arg overload still works")
        void legacyOverloadStillWorks() {
            InstanceRouter router = routerWithThreeInstances();

            int idx = router.getNextInstanceForModel("claude-sonnet-4-5");
            assertEquals(2, idx);
        }
    }

    @Nested
    @DisplayName("AgentDefinition JSON deserialization")
    class JsonTests {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("new field 'instances' parses to a List<String>")
        void newInstancesField() throws Exception {
            String json = "{\"id\":\"a1\",\"name\":\"A\",\"model\":\"gpt-4o\","
                    + "\"instances\":[\"openai-main\",\"azure-2\"]}";
            AgentDefinition def = mapper.readValue(json, AgentDefinition.class);

            assertEquals(Arrays.asList("openai-main", "azure-2"), def.getInstances());
        }

        @Test
        @DisplayName("legacy field 'instanceId' is mapped to a singleton list")
        void legacyInstanceIdMapped() throws Exception {
            String json = "{\"id\":\"a1\",\"name\":\"A\",\"model\":\"gpt-4o\","
                    + "\"instanceId\":\"openai-main\"}";
            AgentDefinition def = mapper.readValue(json, AgentDefinition.class);

            assertEquals(Collections.singletonList("openai-main"), def.getInstances());
        }

        @Test
        @DisplayName("when both fields are present, 'instances' wins")
        void newWinsOverLegacy() throws Exception {
            // Field-order independent: assert the array form takes precedence regardless of which
            // appears first in the JSON.
            String legacyFirst = "{\"id\":\"a1\",\"name\":\"A\",\"model\":\"gpt-4o\","
                    + "\"instanceId\":\"legacy\",\"instances\":[\"new-main\"]}";
            String newFirst = "{\"id\":\"a1\",\"name\":\"A\",\"model\":\"gpt-4o\","
                    + "\"instances\":[\"new-main\"],\"instanceId\":\"legacy\"}";

            assertEquals(Collections.singletonList("new-main"),
                    mapper.readValue(legacyFirst, AgentDefinition.class).getInstances());
            assertEquals(Collections.singletonList("new-main"),
                    mapper.readValue(newFirst, AgentDefinition.class).getInstances());
        }

        @Test
        @DisplayName("absent field → null")
        void absentFieldNull() throws Exception {
            String json = "{\"id\":\"a1\",\"name\":\"A\",\"model\":\"gpt-4o\"}";
            AgentDefinition def = mapper.readValue(json, AgentDefinition.class);

            assertNull(def.getInstances());
        }

        @Test
        @DisplayName("empty 'instanceId' string is ignored (does not produce singleton)")
        void emptyLegacyIdIgnored() throws Exception {
            String json = "{\"id\":\"a1\",\"name\":\"A\",\"model\":\"gpt-4o\",\"instanceId\":\"\"}";
            AgentDefinition def = mapper.readValue(json, AgentDefinition.class);

            assertNull(def.getInstances());
        }
    }
}
