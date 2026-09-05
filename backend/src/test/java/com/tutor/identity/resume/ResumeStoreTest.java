package com.tutor.identity.resume;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResumeStoreTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ResumeStore store = new ResumeStore(jdbc);

    @Test
    void writesResumeUsingEncryptedRawTextAndVectorProjection() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(17L);

        assertThat(store.insert(7L, "raw text", "enc-key", "{\"skills\":[]}", "[0.1,0.2]")).isEqualTo(17L);

        verify(jdbc).queryForObject(
                org.mockito.ArgumentMatchers.contains("pgp_sym_encrypt"), eq(Long.class), any(Object[].class));
    }

    @Test
    void readsOnlyTheLatestStructuredResume() {
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq(7L)))
                .thenReturn(List.of("{\"skills\":[\"Java\"]}"));

        assertThat(store.latestStructuredJson(7L)).hasValue("{\"skills\":[\"Java\"]}");
    }
}
