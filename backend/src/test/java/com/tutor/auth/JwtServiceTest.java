package com.tutor.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {
    private final JwtService jwt = new JwtService("test_only_jwt_secret_at_least_32_characters_long");

    @Test
    void preservesTenantClaim() {
        String scoped = jwt.issue(7L, "Ada", "tenant-a");
        assertThat(jwt.parse(scoped)).isEqualTo(7L);
        assertThat(jwt.parseTenantId(scoped)).isEqualTo("tenant-a");

    }
}
