package com.tutor.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void carriesTenantAlongsideUser() {
        AuthContext.set(42L, " tenant-a ");
        assertThat(AuthContext.currentUserId()).isEqualTo(42L);
        assertThat(AuthContext.currentTenantId()).isEqualTo("tenant-a");

        AuthContext.set(42L);
        assertThat(AuthContext.currentTenantId()).isNull();
    }
}
