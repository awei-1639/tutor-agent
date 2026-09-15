package com.tutor.platform.jobs;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeasedJobQueueTest {
    private final JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    private final LeasedJobQueue queue = new LeasedJobQueue(jdbc);
    private final LeasedJobQueue.LeaseTable table =
            LeasedJobQueue.LeaseTable.of("chat_turns", "RUNNING", "COMPLETED", "id=?::uuid");

    @Test
    @SuppressWarnings("unchecked")
    void claimNextBuildsSkipLockedClaim() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of("job"));

        var job = queue.claimNext(table, "status='ACCEPTED'", "status='RUNNING', attempts=j.attempts+1",
                "created_at", "id, lease_token", (rs, i) -> "job", "tkn", 120);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).isEqualTo("""
                WITH candidate AS (
                  SELECT id FROM chat_turns
                  WHERE status='ACCEPTED'
                  ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE chat_turns j SET status='RUNNING', attempts=j.attempts+1
                FROM candidate WHERE j.id=candidate.id
                RETURNING id, lease_token
                """);
        assertThat(args.getValue()).containsExactly("tkn", 120);
        assertThat(job).contains("job");
    }

    @Test
    void ownsChecksFenceColumns() {
        when(jdbc.queryForObject(anyString(), Mockito.eq(Integer.class), any(Object[].class))).thenReturn(1);
        UUID token = UUID.randomUUID();

        assertThat(queue.owns(table, "job-1", token)).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(sql.capture(), Mockito.eq(Integer.class), args.capture());
        assertThat(sql.getValue()).isEqualTo("""
                SELECT count(*) FROM chat_turns
                WHERE id=?::uuid AND status='RUNNING' AND lease_token=? AND lease_until > now()
                """);
        assertThat(args.getValue()).containsExactly("job-1", token);
    }

    @Test
    void renewsOnlyWithinFence() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        UUID token = UUID.randomUUID();

        assertThat(queue.renew(table, "job-1", token, 120L)).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).isEqualTo("""
                UPDATE chat_turns SET lease_until=now() + (? * interval '1 second')
                WHERE id=?::uuid AND status='RUNNING' AND lease_token=? AND lease_until > now()
                """);
        assertThat(args.getValue()).containsExactly(120L, "job-1", token);
    }

    @Test
    void completeClearsLeaseAndAppliesExtraSet() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        UUID token = UUID.randomUUID();

        assertThat(queue.complete(table, "job-1", token, ", finished_at=now(), answer_message_id=?", 42L)).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).isEqualTo("""
                UPDATE chat_turns SET status='COMPLETED', lease_token=NULL, lease_until=NULL, finished_at=now(), answer_message_id=?
                WHERE id=?::uuid AND status='RUNNING' AND lease_token=? AND lease_until > now()
                """);
        // SET 子句占位符在前，WHERE 围栏占位符在后。
        assertThat(args.getValue()).containsExactly(42L, "job-1", token);
    }

    @Test
    void failPassesStatusThenExtraArgsThenFence() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        UUID token = UUID.randomUUID();

        assertThat(queue.fail(table, "job-1", token, "RETRYABLE_FAILED",
                ", last_error=?, next_attempt_at=now() + interval '5 seconds'", "boom")).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).isEqualTo("""
                UPDATE chat_turns SET status=?, lease_token=NULL, lease_until=NULL, last_error=?, next_attempt_at=now() + interval '5 seconds'
                WHERE id=?::uuid AND status='RUNNING' AND lease_token=? AND lease_until > now()
                """);
        assertThat(args.getValue()).containsExactly("RETRYABLE_FAILED", "boom", "job-1", token);
    }

    @Test
    void fencedUpdateKeepsRowRunningAndAppliesSet() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        UUID token = UUID.randomUUID();

        assertThat(queue.fencedUpdate(table, "job-1", token, "evidence_status='completed', stage=?", "validating")).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).isEqualTo("""
                UPDATE chat_turns SET evidence_status='completed', stage=?
                WHERE id=?::uuid AND status='RUNNING' AND lease_token=? AND lease_until > now()
                """);
        assertThat(args.getValue()).containsExactly("validating", "job-1", token);
    }

    @Test
    void expireExhaustedSweepsDeadLeases() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(2);

        int swept = queue.expireExhausted(table, "status='FAILED', last_error=?, finished_at=now()",
                new Object[]{"任务租约已耗尽"}, "attempts >= ?", 3);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).isEqualTo("""
                UPDATE chat_turns SET status='FAILED', last_error=?, finished_at=now()
                WHERE status='RUNNING' AND lease_until < now() AND attempts >= ?
                """);
        assertThat(args.getValue()).containsExactly("任务租约已耗尽", 3);
        assertThat(swept).isEqualTo(2);
    }
}
