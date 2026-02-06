package io.github.yannfavinleveque.agentic.agent.exception;

/**
 * Thrown when a requested thread is not found.
 */
public class ThreadNotFoundException extends AgentException {

    private final String threadId;

    public ThreadNotFoundException(String threadId) {
        super(ErrorCode.THREAD_NOT_FOUND, "Thread not found: " + threadId);
        this.threadId = threadId;
    }

    public String getThreadId() {
        return threadId;
    }
}
