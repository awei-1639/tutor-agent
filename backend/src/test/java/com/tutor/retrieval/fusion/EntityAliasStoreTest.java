package com.tutor.retrieval.fusion;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntityAliasStoreTest {
    @Test
    void normalizesAliasesBeforeLookup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("skill:rag", "skill:rag"));

        EntityAliasStore store = new EntityAliasStore(jdbc);
        assertThat(store.resolveNodeIds(List.of(" RAG-Guide "), 4))
                .containsExactly("skill:rag");
        assertThat(EntityAliasStore.normalize(" RAG-Guide ")).isEqualTo("rag guide");
    }
}
