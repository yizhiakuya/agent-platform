package com.agentplatform.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InternalTokenTest {

    @Test
    void rejects_missing_or_wrong_token() {
        assertThat(InternalToken.isValid("secret", null)).isFalse();
        assertThat(InternalToken.isValid("secret", "wrong")).isFalse();
        assertThat(InternalToken.isValid("", "anything")).isFalse();
    }

    @Test
    void accepts_matching_token() {
        assertThat(InternalToken.isValid("secret", "secret")).isTrue();
    }
}
