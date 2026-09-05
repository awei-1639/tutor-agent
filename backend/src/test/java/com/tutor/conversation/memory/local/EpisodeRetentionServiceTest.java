package com.tutor.conversation.memory.local;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EpisodeRetentionServiceTest {
    @Test
    void removesExpiredAndPerUserOverflowEpisodes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString())).thenReturn(2);
        when(jdbc.update(anyString(), eq(50))).thenReturn(3);

        new EpisodeRetentionService(jdbc, 50).prune();

        verify(jdbc).update(contains("expires_at <= now"));
        verify(jdbc).update(contains("row_number() OVER"), eq(50));
    }

    @Test
    void containsDatabaseFailure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString())).thenThrow(new IllegalStateException("database unavailable"));

        new EpisodeRetentionService(jdbc, 200).prune();

        verify(jdbc, never()).update(contains("row_number() OVER"), (Object[]) any());
    }
}
