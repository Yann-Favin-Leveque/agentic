package io.github.sashirestela.openai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Agent} POJO.
 */
class AgentTest {

    @Test
    void testAgentBuilder() {
        var agent = Agent.builder()
                .id("101")
                .name("Test Agent")
                .model("gpt-4o")
                .instructions("Test instructions")
                .resultClass("com.example.TestResult")
                .temperature(0.7)
                .retrieval(true)
                .responseTimeout(60000L)
                .agentType("CHAT")
                .assistantIds(List.of("asst_openai_123", "asst_azure_1", "asst_azure_2"))
                .status("active")
                .threadId("thread_456")
                .createOnAppStart(true)
                .isOpenAI(true)
                .maxTokens(4096)
                .build();

        assertEquals("101", agent.getId());
        assertEquals("Test Agent", agent.getName());
        assertEquals("gpt-4o", agent.getModel());
        assertEquals("Test instructions", agent.getInstructions());
        assertEquals("com.example.TestResult", agent.getResultClass());
        assertEquals(0.7, agent.getTemperature());
        assertTrue(agent.getRetrieval());
        assertEquals(60000L, agent.getResponseTimeout());
        assertEquals("CHAT", agent.getAgentType());
        assertEquals(List.of("asst_openai_123", "asst_azure_1", "asst_azure_2"), agent.getAssistantIds());
        assertEquals("active", agent.getStatus());
        assertEquals("thread_456", agent.getThreadId());
        assertTrue(agent.getCreateOnAppStart());
        assertTrue(agent.getIsOpenAI());
        assertEquals(4096, agent.getMaxTokens());
    }

    @Test
    void testAgentNoArgsConstructor() {
        var agent = new Agent();
        assertNull(agent.getId());
        assertNull(agent.getName());
        assertNull(agent.getModel());
    }

    @Test
    void testAgentAllArgsConstructor() {
        // Order: id, name, assistantIds, model, instructions, resultClass, temperature,
        //        status, threadId, responseTimeout, retrieval, agentType, createOnAppStart, isOpenAI, maxTokens
        var agent = new Agent(
                "101",                                  // id
                "Test Agent",                           // name
                List.of("asst_openai_123", "asst_azure_1"),  // assistantIds
                "gpt-4o",                               // model
                "Test instructions",                    // instructions
                "com.example.TestResult",               // resultClass
                0.7,                                    // temperature
                "active",                               // status
                "thread_456",                           // threadId
                60000L,                                 // responseTimeout
                true,                                   // retrieval
                "CHAT",                                 // agentType
                true,                                   // createOnAppStart
                true,                                   // isOpenAI
                4096                                    // maxTokens
        );

        assertEquals("101", agent.getId());
        assertEquals("Test Agent", agent.getName());
        assertEquals(true, agent.getIsOpenAI());
        assertEquals(4096, agent.getMaxTokens());
    }

    @Test
    void testAgentSettersAndGetters() {
        var agent = new Agent();

        agent.setId("202");
        agent.setName("Updated Agent");
        agent.setModel("gpt-4o-mini");
        agent.setInstructions("Updated instructions");
        agent.setResultClass("UpdatedResult");
        agent.setTemperature(0.5);
        agent.setRetrieval(false);
        agent.setResponseTimeout(30000L);
        agent.setAgentType("TASK");
        agent.setAssistantIds(List.of("asst_openai_456", "asst_azure_3"));
        agent.setStatus("inactive");
        agent.setThreadId("thread_789");
        agent.setCreateOnAppStart(true);
        agent.setIsOpenAI(false);
        agent.setMaxTokens(8192);

        assertEquals("202", agent.getId());
        assertEquals("Updated Agent", agent.getName());
        assertEquals("gpt-4o-mini", agent.getModel());
        assertEquals("Updated instructions", agent.getInstructions());
        assertEquals("UpdatedResult", agent.getResultClass());
        assertEquals(0.5, agent.getTemperature());
        assertFalse(agent.getRetrieval());
        assertEquals(30000L, agent.getResponseTimeout());
        assertEquals("TASK", agent.getAgentType());
        assertEquals(List.of("asst_openai_456", "asst_azure_3"), agent.getAssistantIds());
        assertEquals("inactive", agent.getStatus());
        assertEquals("thread_789", agent.getThreadId());
        assertTrue(agent.getCreateOnAppStart());
        assertFalse(agent.getIsOpenAI());
        assertEquals(8192, agent.getMaxTokens());
    }

    @Test
    void testAgentMinimalConfiguration() {
        var agent = Agent.builder()
                .id("303")
                .model("gpt-4o")
                .build();

        assertEquals("303", agent.getId());
        assertEquals("gpt-4o", agent.getModel());
        assertNull(agent.getName());
        assertNull(agent.getInstructions());
        assertNull(agent.getTemperature());
    }

    @Test
    void testAgentEqualsAndHashCode() {
        var agent1 = Agent.builder()
                .id("101")
                .name("Test Agent")
                .build();

        var agent2 = Agent.builder()
                .id("101")
                .name("Test Agent")
                .build();

        assertEquals(agent1, agent2);
        assertEquals(agent1.hashCode(), agent2.hashCode());
    }

    @Test
    void testAgentToString() {
        var agent = Agent.builder()
                .id("101")
                .name("Test Agent")
                .model("gpt-4o")
                .build();

        String toString = agent.toString();
        assertTrue(toString.contains("101"));
        assertTrue(toString.contains("Test Agent"));
        assertTrue(toString.contains("gpt-4o"));
    }

}
