package com.tutor.identity.profile;

import com.tutor.platform.config.Neo4jProperties;
import com.tutor.knowledge.retrieval.resilience.Neo4jResilience;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillAlignServiceTest {
    @Mock Driver neo4j;
    @Mock SkillAlignmentStore store;
    SkillAlignService service;

    @BeforeEach
    void setUp() {
        service = new SkillAlignService(neo4j, store,
                new Neo4jResilience(2, java.time.Duration.ofSeconds(3)), Neo4jProperties.defaults());
    }

    @Test
    @DisplayName("缓存命中 exact_or_alias → 直接返回，不查 Neo4j")
    void cacheHitSkipsNeo4j() {
        when(store.findCached(any())).thenReturn(Map.of("python", "skill:python-basics"));
        assertThat(service.align(List.of("python"))).containsEntry("python", "skill:python-basics");
    }

    @Test
    @DisplayName("缓存命中 miss → 返回 null，不再查 Neo4j")
    void missCacheReturnsNull() {
        Map<String, String> cached = new HashMap<>();
        cached.put("概率论", null);
        when(store.findCached(any())).thenReturn(cached);
        assertThat(service.align(List.of("概率论"))).containsEntry("概率论", null);
    }

    @Test
    @DisplayName("空输入 → 空输出，不查 DB")
    void emptyInput() {
        assertThat(service.align(List.of())).isEmpty();
    }

    @Test
    @DisplayName("Neo4j 不可用 → 返回当前缓存结果，不把临时故障写成 miss")
    void neo4jUnavailableFallsBackWithoutPoisoningCache() {
        when(store.findCached(any())).thenReturn(Map.of());
        when(neo4j.session()).thenThrow(new IllegalStateException("neo4j unavailable"));
        assertThat(service.align(List.of("java"))).isEmpty();
    }
}
