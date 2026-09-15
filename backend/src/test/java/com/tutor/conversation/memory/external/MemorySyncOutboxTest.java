package com.tutor.conversation.memory.external;

import com.tutor.conversation.memory.policy.MemoryAdmissionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemorySyncOutboxTest {
    @Test
    void claimNextKeepsStructuredUpsertPayload() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MemoryAdmissionPolicy admission = mock(MemoryAdmissionPolicy.class);
        UUID token = UUID.randomUUID();

        // 内核 CTE 领取：RETURNING 的 attempt_count 是自增后的值。
        ResultSet rs = mock(ResultSet.class);
        Array topics = mock(Array.class);
        Array openItems = mock(Array.class);
        when(rs.getLong(1)).thenReturn(101L);
        when(rs.getLong(2)).thenReturn(42L);
        when(rs.getLong(3)).thenReturn(7L);
        when(rs.getString(4)).thenReturn("upsert_memory");
        when(rs.getObject(5, Long.class)).thenReturn(9001L);
        when(rs.getString(6)).thenReturn("remote-uuid");
        when(rs.getString(7)).thenReturn("已准入的摘要");
        when(rs.getArray(8)).thenReturn(topics);
        when(rs.getArray(9)).thenReturn(openItems);
        when(rs.getInt(10)).thenReturn(5);
        when(rs.getObject(11, UUID.class)).thenReturn(token);
        when(topics.getArray()).thenReturn(new Object[]{"Java"});
        when(openItems.getArray()).thenReturn(new Object[]{"复习泛型"});
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });

        MemorySyncOutbox outbox = new MemorySyncOutbox(jdbc, mock(org.springframework.transaction.support.TransactionTemplate.class),
                admission);

        MemorySyncOutbox.Job job = outbox.claimNext().orElseThrow();

        assertThat(job.id()).isEqualTo(101L);
        assertThat(job.memoryId()).isEqualTo(9001L);
        assertThat(job.remoteMemoryId()).isEqualTo("remote-uuid");
        assertThat(job.summary()).isEqualTo("已准入的摘要");
        assertThat(job.topics()).containsExactly("Java");
        assertThat(job.openItems()).containsExactly("复习泛型");
        assertThat(job.attemptCount()).isEqualTo(5);
        assertThat(job.leaseToken()).isEqualTo(token);
    }
}
