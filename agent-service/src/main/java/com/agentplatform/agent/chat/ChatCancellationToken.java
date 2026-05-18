package com.agentplatform.agent.chat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per chat-run cancellation signal.
 *
 * <p>A browser/SSE disconnect is not the same thing as the user asking the
 * agent to stop. This token is cancelled only by an explicit stop request or
 * server-side timeout, so a transient client disconnect can still finish and
 * persist the assistant reply for session history.
 */
public final class ChatCancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Runnable> cancelAction = new AtomicReference<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean cancel() {
        boolean first = cancelled.compareAndSet(false, true);
        if (first) {
            Runnable action = cancelAction.get();
            if (action != null) {
                action.run();
            }
        }
        return first;
    }

    public void setCancelAction(Runnable action) {
        cancelAction.set(action);
        if (cancelled.get() && action != null) {
            action.run();
        }
    }

    public void clearCancelAction(Runnable action) {
        cancelAction.compareAndSet(action, null);
    }
}
