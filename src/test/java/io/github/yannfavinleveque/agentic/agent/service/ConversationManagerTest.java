package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConversationManager}. Focused on the
 * {@link ConversationManager#truncateBefore(String, int)} method added in
 * 1.13.0 and the
 * {@link ConversationManager#truncateByTokenBudget(String, int)} method added
 * in 1.14.0.
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

    // ==================== truncateByTokenBudget (1.14.0) ====================

    /**
     * Replaces the default seed with {@code count} assistant messages of
     * {@code charsPerMessage} characters each. Token estimate per message is
     * {@code charsPerMessage / 4}.
     */
    private void seedWithFixedSizeMessages(int count, int charsPerMessage) {
        manager = new ConversationManager();
        convId = manager.createConversation();
        char[] chars = new char[charsPerMessage];
        for (int i = 0; i < count; i++) {
            chars[0] = (char) ('0' + (i % 10)); // tag with index so we can check ordering
            for (int j = 1; j < charsPerMessage; j++) {
                chars[j] = 'x';
            }
            manager.addAssistantMessage(convId, new String(chars));
        }
        assertEquals(count, manager.getMessageCount(convId),
                "precondition: " + count + " messages after seeding");
    }

    @Test
    void truncateByTokenBudget_keepsMessagesUnderBudget() {
        // 10 messages * 400 chars = ~100 tokens each, ~1000 total
        seedWithFixedSizeMessages(10, 400);
        String lastContent = manager.getHistory(convId).get(9).getTextContent();

        int removed = manager.truncateByTokenBudget(convId, 500);

        assertTrue(removed > 0, "should remove at least some messages");
        int remaining = manager.getMessageCount(convId);
        // Each remaining message contributes ~100 tokens; total must fit in budget.
        assertTrue(remaining * 100 <= 500 + 100,
                "remaining total tokens should be within budget (with per-message slack)");
        List<Message> kept = manager.getHistory(convId);
        // The last kept message must be the most recent one from the original seed.
        assertEquals(lastContent, kept.get(kept.size() - 1).getTextContent(),
                "should keep the most recent messages");
    }

    @Test
    void truncateByTokenBudget_exactlyAtBudgetIsNoop() {
        // 5 messages * 400 chars = ~100 tokens each, ~500 total
        seedWithFixedSizeMessages(5, 400);
        int removed = manager.truncateByTokenBudget(convId, 500);
        assertEquals(0, removed);
        assertEquals(5, manager.getMessageCount(convId));
    }

    @Test
    void truncateByTokenBudget_zeroBudgetClearsAll() {
        int removed = manager.truncateByTokenBudget(convId, 0);
        assertEquals(20, removed);
        assertEquals(0, manager.getMessageCount(convId));
    }

    @Test
    void truncateByTokenBudget_negativeBudgetClearsAll() {
        int removed = manager.truncateByTokenBudget(convId, -123);
        assertEquals(20, removed);
        assertEquals(0, manager.getMessageCount(convId));
    }

    @Test
    void truncateByTokenBudget_alreadyBelowBudget_returnsZero() {
        // Default seed: 20 short messages (~2-3 tokens each); budget is huge.
        int removed = manager.truncateByTokenBudget(convId, 1_000_000);
        assertEquals(0, removed);
        assertEquals(20, manager.getMessageCount(convId));
    }

    @Test
    void truncateByTokenBudget_unknownConversation_returnsZero() {
        int removed = manager.truncateByTokenBudget("does-not-exist", 100);
        assertEquals(0, removed);
    }

    @Test
    void truncateByTokenBudget_nullConversation_returnsZero() {
        int removed = manager.truncateByTokenBudget(null, 100);
        assertEquals(0, removed);
    }

    @Test
    void truncateByTokenBudget_nullTextContentMessagesAreIgnored() {
        // Fresh 3 heavy messages, with a null-content message injected in the middle.
        manager = new ConversationManager();
        convId = manager.createConversation();
        char[] chars = new char[400];
        for (int j = 0; j < chars.length; j++) chars[j] = 'x';
        String heavy = new String(chars); // ~100 tokens

        manager.addAssistantMessage(convId, heavy);                                // oldest, ~100 tokens
        manager.addMessage(convId, Message.builder().role("assistant").content((String) null).build()); // 0 tokens
        manager.addAssistantMessage(convId, heavy);                                // ~100 tokens
        manager.addAssistantMessage(convId, heavy);                                // newest, ~100 tokens
        assertEquals(4, manager.getMessageCount(convId));

        // Budget = 200 tokens. The two newest heavy messages (~200 tokens) fit
        // exactly; the null-content message in between counts as 0 tokens and
        // should be kept for free, but only if it sits inside the kept tail.
        // Walking backwards: newest (100) + second newest (100) = 200, adding
        // the null message keeps total at 200, adding the oldest heavy would
        // overflow to 300. Expected result: 1 message removed (the oldest
        // heavy), 3 kept.
        int removed = manager.truncateByTokenBudget(convId, 200);
        assertEquals(1, removed, "only the oldest heavy message should be removed");
        assertEquals(3, manager.getMessageCount(convId));

        List<Message> kept = manager.getHistory(convId);
        // After dropping the oldest heavy message, the null-content message
        // now sits at the head of the kept tail, followed by the two heavy
        // ones — the null message is counted as 0 tokens so it does not
        // evict any real content.
        assertEquals(null, kept.get(0).getTextContent());
        assertEquals(heavy, kept.get(1).getTextContent());
        assertEquals(heavy, kept.get(2).getTextContent());
    }
}
