package io.github.yannfavinleveque.agentic.agent.service;

import io.github.yannfavinleveque.agentic.agent.model.FunctionCall;
import io.github.yannfavinleveque.agentic.agent.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages conversation history for multi-turn interactions.
 * <p>
 * Provides virtual threads (in-memory conversation storage) for all providers.
 * This allows stateless API calls while maintaining conversation context.
 * </p>
 *
 * <pre>{@code
 * // Create a conversation
 * String convId = agentService.createConversation();
 *
 * // Send messages - history is managed automatically
 * AgentResult r1 = agentService.requestAgent("agent", "Hello", convId).join();
 * AgentResult r2 = agentService.requestAgent("agent", "Follow up", convId).join();
 *
 * // Clean up when done
 * agentService.deleteConversation(convId);
 * }</pre>
 */
public class ConversationManager {

    private static final Logger logger = LoggerFactory.getLogger(ConversationManager.class);

    /**
     * In-memory storage for conversation histories.
     * Key: conversationId, Value: list of messages
     */
    private final Map<String, List<Message>> conversations = new ConcurrentHashMap<>();

    /**
     * Creates a new conversation and returns its ID.
     *
     * @return Unique conversation ID
     */
    public String createConversation() {
        String conversationId = UUID.randomUUID().toString();
        conversations.put(conversationId, new ArrayList<>());
        logger.debug("Created conversation: {}", conversationId);
        return conversationId;
    }

    /**
     * Gets the message history for a conversation.
     *
     * @param conversationId Conversation ID
     * @return List of messages (empty list if conversation doesn't exist)
     */
    public List<Message> getHistory(String conversationId) {
        if (conversationId == null) {
            return null;
        }
        List<Message> history = conversations.get(conversationId);
        if (history == null) {
            logger.warn("Conversation not found: {} - returning empty history", conversationId);
            return new ArrayList<>();
        }
        return new ArrayList<>(history); // Return copy to prevent external modification
    }

    /**
     * Adds a user message to the conversation.
     *
     * @param conversationId Conversation ID
     * @param userMessage    User message content
     */
    public void addUserMessage(String conversationId, String userMessage) {
        if (conversationId == null) return;

        List<Message> history = conversations.get(conversationId);
        if (history != null) {
            history.add(Message.user(userMessage));
            logger.debug("Added user message to conversation {} (now {} messages)",
                    conversationId, history.size());
        }
    }

    /**
     * Adds a user message with images to the conversation.
     *
     * @param conversationId Conversation ID
     * @param userMessage    User message content
     * @param imagesBase64   List of base64-encoded images
     */
    public void addUserMessageWithImages(String conversationId, String userMessage, List<String> imagesBase64) {
        if (conversationId == null) return;

        List<Message> history = conversations.get(conversationId);
        if (history != null) {
            List<Message.ContentPart> parts = new ArrayList<>();
            parts.add(Message.ContentPart.text(userMessage));

            if (imagesBase64 != null) {
                for (String img : imagesBase64) {
                    parts.add(Message.ContentPart.pngBase64(img));
                }
            }

            history.add(Message.builder()
                    .role("user")
                    .content(parts)
                    .build());
            logger.debug("Added user message with {} images to conversation {} (now {} messages)",
                    imagesBase64 != null ? imagesBase64.size() : 0, conversationId, history.size());
        }
    }

    /**
     * Adds an assistant response to the conversation.
     *
     * @param conversationId Conversation ID
     * @param response       Assistant response content
     */
    public void addAssistantMessage(String conversationId, String response) {
        if (conversationId == null) return;

        List<Message> history = conversations.get(conversationId);
        if (history != null) {
            history.add(Message.assistant(response));
            logger.debug("Added assistant message to conversation {} (now {} messages)",
                    conversationId, history.size());
        }
    }

    /**
     * Adds an arbitrary message to the conversation.
     * Used by autonomous mode to add tool results and assistant-with-tool-calls messages.
     *
     * @param conversationId Conversation ID
     * @param message        Message to add
     */
    public void addMessage(String conversationId, Message message) {
        if (conversationId == null) return;

        List<Message> history = conversations.get(conversationId);
        if (history != null) {
            history.add(message);
            logger.debug("Added {} message to conversation {} (now {} messages)",
                    message.getRole(), conversationId, history.size());
        }
    }

    /**
     * Replaces the last assistant message in a conversation with a new message.
     * Used by autonomous mode to replace the text summary with a proper assistant-with-tool-calls message.
     *
     * @param conversationId Conversation ID
     * @param replacement    Replacement message
     */
    public void replaceLastAssistantMessage(String conversationId, Message replacement) {
        if (conversationId == null) return;

        List<Message> history = conversations.get(conversationId);
        if (history != null && !history.isEmpty()) {
            // Find and replace the last assistant message
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("assistant".equals(history.get(i).getRole())) {
                    history.set(i, replacement);
                    logger.debug("Replaced last assistant message in conversation {}", conversationId);
                    return;
                }
            }
        }
        // No assistant message found - just add the replacement
        if (history != null) {
            history.add(replacement);
        }
    }

    /**
     * Compacts tool result messages in a conversation by clearing their content.
     * Preserves the message structure (role, toolCallId, toolName) so the LLM API
     * doesn't reject the request, but replaces the bulky response data with a
     * minimal placeholder.
     * <p>
     * Keeps all tool results from the most recent iteration intact (i.e. all tool
     * results after the last assistant message). This correctly handles parallel
     * tool calls where the LLM returns multiple tool calls in a single response.
     * <p>
     * Additionally keeps the {@code keepLastNIterations - 1} previous iterations'
     * tool results intact.
     *
     * @param conversationId      Conversation ID
     * @param keepLastNIterations Number of most recent iterations whose tool results are kept intact (minimum 1)
     * @return Number of tool results compacted
     */
    public int compactToolResults(String conversationId, int keepLastNIterations) {
        if (conversationId == null) return 0;
        keepLastNIterations = Math.max(1, keepLastNIterations);

        List<Message> history = conversations.get(conversationId);
        if (history == null) return 0;

        // Find boundaries: each "assistant" message starts a new iteration.
        // Tool results after the Nth-to-last assistant message are kept.
        // Walk backwards to find the Nth assistant message.
        int assistantCount = 0;
        int keepFromIndex = history.size(); // default: keep nothing extra
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("assistant".equals(history.get(i).getRole())) {
                assistantCount++;
                if (assistantCount >= keepLastNIterations) {
                    keepFromIndex = i;
                    break;
                }
            }
        }

        // Compact all tool results before keepFromIndex
        int compactCount = 0;
        for (int i = 0; i < keepFromIndex; i++) {
            Message msg = history.get(i);
            if ("tool".equals(msg.getRole()) && !"[compacted]".equals(msg.getTextContent())) {
                history.set(i, Message.toolResult(
                        msg.getToolCallId(),
                        msg.getToolName(),
                        "[compacted]"));
                compactCount++;
            }
        }

        if (compactCount > 0) {
            logger.debug("Compacted {} tool results in conversation {} (kept last {} iteration(s))",
                    compactCount, conversationId, keepLastNIterations);
        }
        return compactCount;
    }

    /**
     * Deletes a conversation and frees its memory.
     *
     * @param conversationId Conversation ID
     * @return true if conversation existed and was deleted
     */
    public boolean deleteConversation(String conversationId) {
        if (conversationId == null) return false;

        boolean removed = conversations.remove(conversationId) != null;
        if (removed) {
            logger.debug("Deleted conversation: {}", conversationId);
        } else {
            logger.debug("Conversation not found for deletion: {}", conversationId);
        }
        return removed;
    }

    /**
     * Checks if a conversation exists.
     *
     * @param conversationId Conversation ID
     * @return true if conversation exists
     */
    public boolean exists(String conversationId) {
        return conversationId != null && conversations.containsKey(conversationId);
    }

    /**
     * Keeps only the {@code keepLastN} most recent messages of a conversation
     * and drops the older ones. Useful for token-budget-based memory compaction
     * where a higher-level summary replaces the dropped context.
     *
     * <p>When {@code keepLastN} is zero or negative, this behaves like
     * {@link #clearHistory(String)}. When it is larger than the current
     * conversation size, nothing is removed.
     *
     * @param conversationId Conversation ID
     * @param keepLastN      Number of most recent messages to preserve
     * @return Number of messages actually removed
     */
    public int truncateBefore(String conversationId, int keepLastN) {
        if (conversationId == null) return 0;
        List<Message> history = conversations.get(conversationId);
        if (history == null) return 0;
        int toKeep = Math.max(0, keepLastN);
        if (history.size() <= toKeep) return 0;
        int removed = history.size() - toKeep;
        // Replace with a new ArrayList containing only the last N messages so
        // external iterators holding references do not trip on concurrent removes.
        List<Message> kept = new ArrayList<>(history.subList(removed, history.size()));
        history.clear();
        history.addAll(kept);
        logger.debug("Truncated conversation {}: removed {} messages, kept last {}",
                conversationId, removed, toKeep);
        return removed;
    }

    /**
     * Drops the oldest messages from a conversation until the sum of estimated
     * tokens across the remaining messages is at most {@code maxTokens}. Token
     * counts are estimated using the common OpenAI convention of
     * {@code textContent.length() / 4}; messages with a {@code null} text content
     * are counted as zero tokens. Intended for token-budget-based memory
     * compaction schemes where a higher-level summary (stored outside the
     * conversation) replaces the dropped turns.
     *
     * <p>When {@code maxTokens} is zero or negative this behaves like
     * {@link #clearHistory(String)} and returns the previous size. When the
     * conversation is already at or below budget nothing is removed.
     *
     * @param conversationId Conversation ID
     * @param maxTokens      Maximum total estimated tokens to keep
     * @return Number of messages actually removed
     */
    public int truncateByTokenBudget(String conversationId, int maxTokens) {
        if (conversationId == null) return 0;
        List<Message> history = conversations.get(conversationId);
        if (history == null) return 0;
        if (history.isEmpty()) return 0;

        if (maxTokens <= 0) {
            int removed = history.size();
            history.clear();
            logger.debug("Truncated conversation {} by token budget: cleared all {} messages (budget <= 0)",
                    conversationId, removed);
            return removed;
        }

        // Walk backwards from the most recent message, accumulating token
        // estimates until adding another message would exceed the budget.
        int total = 0;
        int keepFromIndex = history.size();
        for (int i = history.size() - 1; i >= 0; i--) {
            int tokens = estimateTokens(history.get(i));
            if (total + tokens > maxTokens) {
                break;
            }
            total += tokens;
            keepFromIndex = i;
        }

        if (keepFromIndex == 0) return 0;

        int removed = keepFromIndex;
        // Replace with a new ArrayList containing only the kept tail so external
        // iterators holding references do not trip on concurrent removes.
        List<Message> kept = new ArrayList<>(history.subList(keepFromIndex, history.size()));
        history.clear();
        history.addAll(kept);
        logger.info("Truncated conversation {} by token budget: removed {} messages, kept {} (~{} tokens, budget {})",
                conversationId, removed, kept.size(), total, maxTokens);
        return removed;
    }

    /**
     * Estimates the token cost of a message using the OpenAI chars/4 rule.
     *
     * <p>Counts every payload field that the LLM sees: plain text content,
     * multimodal text parts, and assistant tool-call arguments. Tool-call
     * heavy conversations would otherwise be massively underestimated, since
     * an {@code assistant} message that only carries {@code functionCalls} has
     * a {@code null} {@code textContent} but still costs real tokens for the
     * function name + arguments JSON. We add a small per-message overhead for
     * role/structural framing so the budget stays conservative.
     */
    private static int estimateTokens(Message message) {
        if (message == null) return 0;
        int chars = 0;

        // Plain text body (also covers tool-result messages, which keep their
        // result in textContent).
        String text = message.getTextContent();
        if (text != null) chars += text.length();

        // Multimodal text parts.
        if (message.getContentParts() != null) {
            for (Message.ContentPart part : message.getContentParts()) {
                if (part != null && part.getText() != null) {
                    chars += part.getText().length();
                }
            }
        }

        // Assistant function_call payload (name + arguments JSON).
        if (message.getFunctionCalls() != null) {
            for (FunctionCall call : message.getFunctionCalls()) {
                if (call == null) continue;
                if (call.getName() != null) chars += call.getName().length();
                if (call.getArguments() != null) chars += call.getArguments().length();
            }
        }

        // Tool-result framing (call id + tool name).
        if (message.getToolCallId() != null) chars += message.getToolCallId().length();
        if (message.getToolName() != null) chars += message.getToolName().length();

        // ~4 tokens of structural framing per message (role, separators).
        return (chars / 4) + 4;
    }

    /**
     * Clears all messages from a conversation without deleting it.
     * Useful for retrying an autonomous loop from scratch while keeping the same conversation ID.
     *
     * @param conversationId Conversation ID
     */
    public void clearHistory(String conversationId) {
        if (conversationId == null) return;
        List<Message> history = conversations.get(conversationId);
        if (history != null) {
            history.clear();
            logger.debug("Cleared history for conversation: {}", conversationId);
        }
    }

    /**
     * Gets the number of messages in a conversation.
     *
     * @param conversationId Conversation ID
     * @return Number of messages, or 0 if conversation doesn't exist
     */
    public int getMessageCount(String conversationId) {
        if (conversationId == null) return 0;
        List<Message> history = conversations.get(conversationId);
        return history != null ? history.size() : 0;
    }

    /**
     * Gets the number of active conversations.
     *
     * @return Number of active conversations
     */
    public int getActiveConversationCount() {
        return conversations.size();
    }

    /**
     * Clears all conversations. Use with caution.
     */
    public void clearAll() {
        int count = conversations.size();
        conversations.clear();
        logger.info("Cleared all {} conversations", count);
    }
}
