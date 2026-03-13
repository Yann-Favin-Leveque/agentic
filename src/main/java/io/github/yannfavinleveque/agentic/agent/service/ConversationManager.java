package io.github.yannfavinleveque.agentic.agent.service;

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
