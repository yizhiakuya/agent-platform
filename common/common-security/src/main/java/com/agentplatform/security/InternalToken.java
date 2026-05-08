package com.agentplatform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class InternalToken {

    public static final String HEADER = "X-Internal-Token";

    private InternalToken() {}

    public static boolean isValid(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
