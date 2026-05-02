package com.agentplatform.agent.ai;

/**
 * Thrown by a {@link ToolPreInterceptor} to abort a remote tool call with a
 * structured, user-presentable reason. The dispatcher is never invoked when
 * this exception bubbles out of the pre-chain.
 */
public class ToolBlockedException extends RuntimeException {

    private final String reason;

    public ToolBlockedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
