package com.tutor.memory.local;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FactRetentionServiceTest {

    @Mock JdbcTemplate jdbc;

    @Test
    @DisplayName("清理过期事实与超限事实")
    void removesExpiredAndPerUserOverflowFacts() {
        when(jdbc.update(anyString())).thenReturn(2);
        when(jdbc.update(anyString(), eq(50))).thenReturn(3);

        new FactRetentionService(jdbc, 50).prune();

        verify(jdbc).update(contains("expires_at <= now()"));
        verify(jdbc).update(contains("row_number() OVER"), eq(50));
    }

    @Test
    @DisplayName("清理失败只记日志, 不抛出")
    void swallowsRetentionFailures() {
        when(jdbc.update(anyString())).thenThrow(new IllegalStateException("db down"));

        new FactRetentionService(jdbc, 60).prune();
    }
}
