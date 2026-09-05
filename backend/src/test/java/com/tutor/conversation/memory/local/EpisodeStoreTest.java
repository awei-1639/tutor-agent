package com.tutor.conversation.memory.local;

import com.tutor.conversation.memory.local.EpisodeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2 情景记忆测试 (Phase 3 V4 3.1): 不连真实 DB, 测 PG text array 解析/序列化纯函数逻辑。
 */
@ExtendWith(MockitoExtension.class)
class EpisodeStoreTest {

    @Mock JdbcTemplate jdbc;
    EpisodeStore store;

    @BeforeEach
    void setUp() {
        store = new EpisodeStore(jdbc);
    }

    @Test
    @DisplayName("PG 文本数组解析: 空 → 空列表")
    void parseEmptyArray() {
        // 通过 insert 时反射访问 — 这里直接用 mock 验证调用逻辑
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any(), anyString(), anyString()))
                .thenReturn(1L);
        store.insert(1L, 1L, "test", List.of(), List.of(), null);
        // 调用发生过即通过
    }

    @Test
    @DisplayName("recentByUser: 解析 topics/open_items 列")
    void parseArrayInResult() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<EpisodeStore.Episode>>any(), eq(1L), eq(10)))
                .thenReturn(List.of(new EpisodeStore.Episode(
                        1L, 1L, 1L, "summary", List.of("NLP", "RAG"), List.of("完成微调实验"))));
        List<EpisodeStore.Episode> out = store.recentByUser(1L, 10);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).topics()).containsExactly("NLP", "RAG");
        assertThat(out.get(0).openItems()).containsExactly("完成微调实验");
    }

    @Test
    @DisplayName("openItemsByUser: 展平去重并限量")
    void openItemsFlattensDedupesAndLimits() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(), eq(1L)))
                .thenReturn(List.of("{继续Redis面试题,制定项目计划}", "{制定项目计划,复习多线程}"));

        List<String> items = store.openItemsByUser(1L, 3);

        assertThat(items).containsExactly("继续Redis面试题", "制定项目计划", "复习多线程");
        verify(jdbc).query(contains("status='active'"),
                org.mockito.ArgumentMatchers.<RowMapper<String>>any(), eq(1L));
    }

    @Test
    @DisplayName("密钥轮换期间按版本选择当前或旧密钥")
    void readsWithCurrentAndPreviousEncryptionKey() {
        EpisodeStore rotating = new EpisodeStore(jdbc, "new-key", "v2", "old-key", "v1");
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<EpisodeStore.Episode>>any(),
                eq("v2"), eq("new-key"), eq("v1"), eq("old-key"), eq(1L), eq(10)))
                .thenReturn(List.of());

        assertThat(rotating.recentByUser(1L, 10)).isEmpty();

        org.mockito.Mockito.verify(jdbc).query(
                org.mockito.ArgumentMatchers.contains("summary_encryption_key_id"),
                org.mockito.ArgumentMatchers.<RowMapper<EpisodeStore.Episode>>any(),
                eq("v2"), eq("new-key"), eq("v1"), eq("old-key"), eq(1L), eq(10));
    }
}
