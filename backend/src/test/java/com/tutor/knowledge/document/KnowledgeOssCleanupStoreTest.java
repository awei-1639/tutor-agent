package com.tutor.knowledge.document;

import com.tutor.platform.jobs.LeasedJobQueue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeOssCleanupStoreTest {
    private final JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    private final OssStorage oss = Mockito.mock(OssStorage.class);
    private final TransactionTemplate transactions = Mockito.mock(TransactionTemplate.class);
    private final LeasedJobQueue queue = new LeasedJobQueue(jdbc);
    private final KnowledgeOssCleanupStore store = new KnowledgeOssCleanupStore(jdbc, oss, transactions, queue);

    @Test
    void doesNothingWhenNoClaimableJob() {
        claimReturnsNothing();

        store.processOne();

        verify(oss, never()).delete(anyString());
    }

    @Test
    void completesFencedAfterSuccessfulDelete() {
        UUID token = UUID.randomUUID();
        claimReturnsJob(1, token);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        store.processOne();

        verify(oss).delete("a/b.txt");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        // 第一次 update 是租约耗尽回收，第二次才是完成围栏。
        verify(jdbc, Mockito.times(2)).update(sql.capture(), args.capture());
        assertThat(sql.getAllValues().get(1)).contains("SET status='completed', lease_token=NULL, lease_until=NULL, finished_at=now(), last_error=NULL");
        assertThat(sql.getAllValues().get(1)).contains("status='processing' AND lease_token=? AND lease_until > now()");
        assertThat(args.getAllValues().get(1)[1]).isEqualTo(token);
    }

    @Test
    void retriesWhenAttemptsRemain() {
        UUID token = UUID.randomUUID();
        claimReturnsJob(3, token);
        Mockito.doThrow(new RuntimeException("boom")).when(oss).delete(anyString());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        store.processOne();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        // 第一次 update 是租约耗尽回收，第二次才是失败围栏。
        verify(jdbc, Mockito.times(2)).update(sql.capture(), args.capture());
        assertThat(sql.getAllValues().get(1)).contains("SET status=?, lease_token=NULL, lease_until=NULL, next_attempt_at=now() + (LEAST(attempts, 8) * interval '1 minute'), last_error=?");
        // 目标状态、错误信息、围栏 id/token 的绑定顺序。
        assertThat(args.getAllValues().get(1)[0]).isEqualTo("retryable_failed");
        assertThat(args.getAllValues().get(1)[1]).isEqualTo("boom");
        assertThat(args.getAllValues().get(1)[3]).isEqualTo(token);
    }

    @Test
    void goesTerminalWhenAttemptsExhausted() {
        UUID token = UUID.randomUUID();
        claimReturnsJob(8, token);
        Mockito.doThrow(new RuntimeException("boom")).when(oss).delete(anyString());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        store.processOne();

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, Mockito.times(2)).update(anyString(), args.capture());
        assertThat(args.getAllValues().get(1)[0]).isEqualTo("failed");
    }

    @Test
    void sweepsExhaustedLeasesBeforeClaiming() {
        claimReturnsNothing();

        store.processOne();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, Mockito.atLeastOnce()).update(sql.capture(), args.capture());
        assertThat(sql.getAllValues().getFirst()).contains("WHERE status='processing' AND lease_until < now() AND attempts >= ?");
        assertThat(args.getAllValues().getFirst()).containsExactly("任务租约已耗尽", 8);
    }

    @Test
    void claimSqlTakesOverStaleProcessingRows() {
        claimReturnsNothing();

        store.processOne();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, Mockito.atLeastOnce()).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getAllValues().getFirst())
                .contains("(status IN ('pending','retryable_failed') AND next_attempt_at <= now())")
                .contains("OR (status='processing' AND lease_until < now() AND attempts < 8)")
                .contains("FOR UPDATE SKIP LOCKED");
    }

    private void claimReturnsNothing() {
        when(transactions.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    }

    private void claimReturnsJob(int attempts, UUID token) {
        when(transactions.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new KnowledgeOssCleanupStore.Job(UUID.randomUUID(), "a/b.txt", attempts, token)));
    }
}
