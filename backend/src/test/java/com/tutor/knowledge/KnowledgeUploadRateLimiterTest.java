package com.tutor.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeUploadRateLimiterTest {
    @Test
    void limitsAnAdminWithinCurrentHour() {
        KnowledgeUploadRateLimiter limiter = new KnowledgeUploadRateLimiter(2);

        assertThat(limiter.allow(7L)).isTrue();
        assertThat(limiter.allow(7L)).isTrue();
        assertThat(limiter.allow(7L)).isFalse();
        assertThat(limiter.allow(8L)).isTrue();
    }
}
