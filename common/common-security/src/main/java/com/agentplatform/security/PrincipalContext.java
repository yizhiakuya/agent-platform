package com.agentplatform.security;

import java.util.UUID;

/**
 * Per-request principal storage. Set by an auth filter, read by controllers/services,
 * cleared in the filter's {@code finally} block.
 */
public final class PrincipalContext {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private PrincipalContext() {}

    public static void set(Principal p) { CURRENT.set(p); }
    public static Principal current() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }

    public static Principal require() {
        Principal p = CURRENT.get();
        if (p == null) {
            throw new IllegalStateException("No authenticated principal in request context");
        }
        return p;
    }

    public static UUID requireUserId() {
        return require().userIdAsUuid();
    }

    public static UUID requireDeviceId() {
        Principal p = require();
        if (!p.isDevice()) {
            throw new IllegalStateException("Expected device principal, got " + p.type());
        }
        return p.subjectAsUuid();
    }
}
