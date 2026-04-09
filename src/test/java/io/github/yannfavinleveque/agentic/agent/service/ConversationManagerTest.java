package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ConversationManager}. Focused on the new
 * {@link ConversationManager#truncateBefore(String, int)} method added in
 * 1.13.0.
 */
class ConversationManagerTest {

    private ConversationManager manager;
    private String convId;

    @BeforeEach
    void setUp() {
        manager = new ConversationManager();
        convId = manager.createConversation();
        for (int i = 0; i < 10; i++) {
            manager.addUserMessage(convId, "user-" + i);
            manager.addAssistantMessage(convId, "assistant-" + i);
        }
        assertEquals(20, manager.getMessageCount(convId),
                "precondition: 20 messages after seeding");
    }

    @Test
    void truncateBefore_keepsOnlyLastN() {
        int removed = manager.truncateBefore(convId, 6);

        assertEquals(14, removed, "should remove everything except the last 6");
        assertEquals(6, manager.getMessageCount(convId));

        List<Message> kept = manager.getHistory(convId);
        // Last 6 messages of the seeded conversation are:
        // assistant-7, user-8, assistant-8, user-9, assistant-9, ... actually:
        // order is u0,a0,u1,a1,...,u9,a9, so last 6 = u7,a7,u8,a8,u9,a9
        assertEquals("user-7", kept.get(0).getTextContent());
        assertEquals("assistant-7", kept.get(1).getTextContent());
        assertEquals("user-8", kept.get(2).getTextContent());
        assertEquals("assistant-8", kept.get(3).getTextContent());
        assertEquals("user-9", kept.get(4).getTextContent());
        assertEquals("assistant-9", kept.get(5).getTextContent());
    }

    @Test
    void truncateBefore_zeroClearsAll() {
        int removed = manager.truncateBefore(convId, 0);
        assertEquals(20, removed);
        assertEquals(0, manager.getMessageCount(convId));
    }

    @Test
    void truncateBefore_negativeIsTreatedAsZero() {
        int removed = manager.truncateBefore(convId, -42);
        assertEquals(20, removed);
        assertEquals(0, manager.getMessageCount(convId));
    }

    @Test
    void truncateBefore_keepLargerThanSizeIsNoop() {
        int removed = manager.truncateBefore(convId, 100);
        assertEquals(0, removed);
        assertEquals(20, manager.getMessageCount(convId));
    }

    @Test
    void truncateBefore_keepExactlyEqualSizeIsNoop() {
        int removed = manager.truncateBefore(convId, 20);
        assertEquals(0, removed);
        assertEquals(20, manager.getMessageCount(convId));
    }

    @Test
    void truncateBefore_unknownConversationReturnsZero() {
        int removed = manager.truncateBefore("does-not-exist", 5);
        assertEquals(0, removed);
    }

    @Test
    void truncateBefore_nullConversationReturnsZero() {
        int removed = manager.truncateBefore(null, 5);
        assertEquals(0, removed);
    }

    @Test
    void truncateBefore_leavesConversationAccessibleForFurtherWrites() {
        manager.truncateBefore(convId, 3);
        manager.addUserMessage(convId, "after-truncate");
        assertEquals(4, manager.getMessageCount(convId));
        assertEquals("after-truncate",
                manager.getHistory(convId).get(3).getTextContent());
    }
}
