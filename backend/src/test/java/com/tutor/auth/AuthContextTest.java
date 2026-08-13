package com.tutor.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthContextTest {
    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    @Test
    void requiresAnExplicitAuthenticatedUser() {
        assertThatThrownBy(AuthContext::requireUserId)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未认证");
    }
}
