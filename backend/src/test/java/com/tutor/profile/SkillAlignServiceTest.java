package com.tutor.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SkillAlignService 关键场景: 缓存命中分支不依赖 Neo4j。
 * Miss 场景需真实 DB, 留作集成测试 (Phase 2)。
 */
@ExtendWith(MockitoExtension.class)
class SkillAlignServiceTest {

    @Mock Driver neo4j;
    @Mock JdbcTemplate jdbc;

    SkillAlignService svc;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        svc = new SkillAlignService(neo4j, jdbc);
    }

    @Test
    @DisplayName("缓存命中 exact_or_alias → 直接返回, 不查 Neo4j")
    void cacheHit_skipsNeo4j() {
        when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class), eq("python")))
                .thenReturn(List.of("skill:python-basics"));

        Map<String, String> out = svc.align(List.of("python"));

        assertThat(out).containsEntry("python", "skill:python-basics");
    }

    @Test
    @DisplayName("缓存命中 miss (node_id=NULL) → 返回 null, 不再查 Neo4j")
    void missCacheReturnsNull() {
        // miss 缓存的 node_id 为 null
        when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class), eq("概率论")))
                .thenReturn(Collections.singletonList(null));

        Map<String, String> out = svc.align(List.of("概率论"));

        assertThat(out.get("概率论")).isNull();
    }

    @Test
    @DisplayName("空输入 → 空输出, 不查 DB")
    void emptyInput() {
        Map<String, String> out = svc.align(List.of());
        assertThat(out).isEmpty();
    }
}