package com.agentplatform.security;

import java.util.UUID;

/**
 * Authenticated principal — derived from a verified JWT or from gateway-injected
 * trust headers.
 *
 * @param type    Either {@code "user"} or {@code "device"}.
 * @param subject The {@code sub} claim — userId for user tokens, deviceId for device tokens.
 * @param userId  For device tokens, the owning user's id (claim {@code uid}).
 *                For user tokens, identical to {@code subject}.
 * @param jti     Token ID, used for revocation lookups. May be null when reconstructed
 *                from gateway headers (the gateway already verified+revocation-checked).
 */
public record Principal(String type, String subject, String userId, String jti) {

    public static final String TYPE_USER = "user";
    public static final String TYPE_DEVICE = "device";

    public boolean isUser() { return TYPE_USER.equals(type); }
    public boolean isDevice() { return TYPE_DEVICE.equals(type); }

    public UUID userIdAsUuid() { return UUID.fromString(userId); }
    public UUID subjectAsUuid() { return UUID.fromString(subject); }
}
