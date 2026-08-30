package com.tutor.memory.local;

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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语义事实存储测试：不连真实 DB，验证 SQL 形态（幂等键、软失效 fencing、密钥轮换）
 * 与纯函数（hash/category 归一）。加密真实读写由 UserFactsPostgresIT 覆盖。
 */
@ExtendWith(MockitoExtension.class)
class FactStoreTest {

    @Mock JdbcTemplate jdbc;
    FactStore store;

    @BeforeEach
    void setUp() {
        store = new FactStore(jdbc);
    }

    @Test
    @DisplayName("明文路径: 插入带幂等键 ON CONFLICT, 不含 pgcrypto")
    void plaintextInsertUsesNaturalKeyConflict() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(7L));

        long id = store.insertIfAbsentReturningId(1L, 10L, 3L, "用户目标：秋招后端岗", "goal", 0.8);

        assertThat(id).isEqualTo(7L);
        verify(jdbc).query(contains("ON CONFLICT (user_id, fact_hash) WHERE status = 'active' DO NOTHING"),
                any(RowMapper.class), any(Object[].class));
        verify(jdbc).query(org.mockito.AdditionalMatchers.and(contains("INSERT INTO user_facts"),
                org.mockito.AdditionalMatchers.not(contains("pgp_sym_encrypt"))),
                any(RowMapper.class), any(Object[].class));
    }

    @Test
    @DisplayName("加密路径: fact_text 投影 + pgp_sym_encrypt 密文双写")
    void encryptedInsertWritesBothProjectionAndCiphertext() {
        FactStore encrypted = new FactStore(jdbc, "enc-key", "v2", "", "");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(1L));

        encrypted.insertIfAbsentReturningId(1L, null, 0L, "用户偏好：中文讲解", "preference", 0.7);

        verify(jdbc).query(org.mockito.AdditionalMatchers.and(contains("pgp_sym_encrypt"),
                org.mockito.AdditionalMatchers.not(contains("pgp_sym_decrypt"))),
                any(RowMapper.class), any(Object[].class));
    }

    @Test
    @DisplayName("幂等冲突: ON CONFLICT 无返回行时得 0")
    void returnsZeroWhenConflictSuppressed() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        assertThat(store.insertIfAbsentReturningId(1L, 10L, 3L, "同一事实", "goal", 0.5)).isZero();
    }

    @Test
    @DisplayName("软失效携带记忆代际 fencing")
    void markSupersededChecksMemoryGeneration() {
        when(jdbc.update(anyString(), eq(9L), eq(5L), eq(1L), eq(1L), eq(42L))).thenReturn(1);

        assertThat(store.markSuperseded(1L, 5L, 9L, 42L)).isTrue();

        verify(jdbc).update(contains("status='superseded'"), eq(9L), eq(5L), eq(1L), eq(1L), eq(42L));
        verify(jdbc).update(contains("memory_generation"), eq(9L), eq(5L), eq(1L), eq(1L), eq(42L));
    }

    @Test
    @DisplayName("单条删除限定本人 active 事实")
    void deleteByIdScopedToUserAndActive() {
        when(jdbc.update(anyString(), eq(5L), eq(1L))).thenReturn(1);

        assertThat(store.deleteByIdForUser(5L, 1L)).isTrue();

        verify(jdbc).update(contains("id=? AND user_id=? AND status='active'"), eq(5L), eq(1L));
    }

    @Test
    @DisplayName("hashOf: 去标点空白且大小写不敏感, 文本不同则哈希不同")
    void factHashIsCanonicalAndStable() {
        String first = FactStore.hashOf("用户 目标：秋招后端岗！");
        String second = FactStore.hashOf("用户目标秋招后端岗");
        String other = FactStore.hashOf("用户目标：算法岗");

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(other);
        assertThat(first).hasSize(64);
    }

    @Test
    @DisplayName("category 归一: 非法值回退 background")
    void normalizesUnknownCategory() {
        assertThat(FactStore.normalizeCategory("GOAL")).isEqualTo("goal");
        assertThat(FactStore.normalizeCategory("hobby")).isEqualTo("background");
        assertThat(FactStore.normalizeCategory(null)).isEqualTo("background");
    }

    @Test
    @DisplayName("密钥轮换期间按版本选择当前或旧密钥读取")
    void readsWithCurrentAndPreviousEncryptionKey() {
        FactStore rotating = new FactStore(jdbc, "new-key", "v2", "old-key", "v1");
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<FactStore.UserFact>>any(),
                eq("v2"), eq("new-key"), eq("v1"), eq("old-key"), eq(1L), eq(50)))
                .thenReturn(List.of());

        assertThat(rotating.activeByUser(1L, 50)).isEmpty();

        verify(jdbc).query(contains("fact_encryption_key_id"),
                org.mockito.ArgumentMatchers.<RowMapper<FactStore.UserFact>>any(),
                eq("v2"), eq("new-key"), eq("v1"), eq("old-key"), eq(1L), eq(50));
    }

    @Test
    @DisplayName("明文路径 activeByUser 直接传尾参")
    void activeByUserPlaintextArgOrder() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<FactStore.UserFact>>any(),
                eq(1L), eq(50)))
                .thenReturn(List.of());

        assertThat(store.activeByUser(1L, 50)).isEmpty();
        verify(jdbc).query(contains("status = 'active'"),
                org.mockito.ArgumentMatchers.<RowMapper<FactStore.UserFact>>any(), eq(1L), eq(50));
    }
}
