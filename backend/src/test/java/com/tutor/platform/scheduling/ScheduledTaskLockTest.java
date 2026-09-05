package com.tutor.platform.scheduling;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class ScheduledTaskLockTest {

    @Test
    void acquiresWhenUpsertAffectsRow() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        ScheduledTaskLock lock = new ScheduledTaskLock(jdbc);

        assertThat(lock.tryAcquire("push", 60)).isTrue();
    }

    @Test
    void doesNotAcquireWhenLockHeldByAnother() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        ScheduledTaskLock lock = new ScheduledTaskLock(jdbc);

        assertThat(lock.tryAcquire("push", 60)).isFalse();
    }

    @Test
    void skipsTaskWhenNotLeader() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        ScheduledTaskLock lock = new ScheduledTaskLock(jdbc);
        AtomicBoolean ran = new AtomicBoolean();

        lock.runIfLeader("push", 60, () -> ran.set(true));

        assertThat(ran).isFalse();
    }

    @Test
    void skipsTaskWhenStorageUnavailable() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
        ScheduledTaskLock lock = new ScheduledTaskLock(jdbc);
        AtomicBoolean ran = new AtomicBoolean();

        lock.runIfLeader("push", 60, () -> ran.set(true));

        assertThat(ran).isFalse();
    }
}
