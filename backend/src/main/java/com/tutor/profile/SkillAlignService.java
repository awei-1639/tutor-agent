package com.tutor.profile;

import com.tutor.config.Neo4jProperties;
import com.tutor.retrieval.resilience.Neo4jResilience;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 技能实体对齐 (V3 6.0 / 实现设计 4.3): 画像技能名 → 图谱skill节点id。
 * 三级策略的前两级: ①精确/别名命中 ②(向量最近邻留待未命中率超标时接入)。
 * 结果缓存 skill_alignments; 未命中也缓存(method=miss), 未命中率可观测。
 */
@Service
public class SkillAlignService {
    private static final Logger log = LoggerFactory.getLogger(SkillAlignService.class);
    private final Driver neo4j;
    private final JdbcTemplate jdbc;
    private final Neo4jResilience resilience;
    private final Duration queryTimeout;

    public SkillAlignService(Driver neo4j, JdbcTemplate jdbc) {
        this(neo4j, jdbc, new Neo4jResilience(Neo4jProperties.defaults()), Neo4jProperties.defaults());
    }

    @Autowired
    public SkillAlignService(Driver neo4j, JdbcTemplate jdbc, Neo4jResilience resilience,
                             Neo4jProperties properties) {
        this.neo4j = neo4j;
        this.jdbc = jdbc;
        this.resilience = resilience;
        this.queryTimeout = Duration.ofSeconds(properties.queryTimeoutSeconds());
    }

    /** 批量对齐; 返回 name→nodeId, 未命中值为null */
    public Map<String, String> align(List<String> names) {
        Map<String, String> out = new HashMap<>();
        if (names.isEmpty()) return out;

        Set<String> normalized = names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.toLowerCase(Locale.ROOT).trim())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (normalized.isEmpty()) return out;

        // 1) 一次查询整个缓存集合，避免画像技能数 N 次往返 PostgreSQL。
        String placeholders = String.join(",", java.util.Collections.nCopies(normalized.size(), "?"));
        Map<String, String> cached = new HashMap<>();
        jdbc.query("SELECT raw_name, node_id FROM skill_alignments WHERE raw_name IN (" + placeholders + ")",
                (rs, rowNum) -> new String[]{rs.getString(1), rs.getString(2)}, normalized.toArray())
                .forEach(row -> cached.put(row[0], row[1]));

        List<String> misses = new ArrayList<>();
        for (String key : normalized) {
            if (cached.containsKey(key)) {
                out.put(key, cached.get(key)); // 可能为null(缓存过的miss)
            } else {
                misses.add(key);
            }
        }
        if (misses.isEmpty()) return restoreInputNames(names, out);

        // 2) Neo4j 精确/别名命中
        Neo4jResilience.QueryResult<Map<String, String>> query = resilience.execute("skill-align", () -> {
            Map<String, String> found = new HashMap<>();
            try (Session session = neo4j.session()) {
                session.run("""
                    UNWIND $names AS nm
                    MATCH (s:Skill)
                    WHERE toLower(s.name) = toLower(nm)
                    OR any(a IN s.aliases WHERE toLower(a) = toLower(nm))
                    RETURN nm, s.node_id AS id
                    """, Map.of("names", misses),
                        TransactionConfig.builder().withTimeout(queryTimeout).build())
                        .forEachRemaining(r -> found.putIfAbsent(r.get("nm").asString(), r.get("id").asString()));
            }
            return found;
        });
        if (!query.available()) return restoreInputNames(names, out);
        Map<String, String> found = query.value();
        for (String name : misses) {
            String nodeId = found.get(name);
            out.put(name, nodeId);
            if (nodeId == null) log.info("技能对齐未命中: {} (进待对齐队列)", name);
        }
        // 2) 批量回填缓存，避免每个未命中技能再单独执行一次写入。
        jdbc.batchUpdate("""
                INSERT INTO skill_alignments (raw_name, node_id, method, score) VALUES (?,?,?,1.0)
                ON CONFLICT (raw_name) DO UPDATE SET node_id=EXCLUDED.node_id, method=EXCLUDED.method
                """, misses, misses.size(), (ps, name) -> {
            String nodeId = found.get(name);
            ps.setString(1, name);
            ps.setString(2, nodeId);
            ps.setString(3, nodeId != null ? "exact_or_alias" : "miss");
        });
        return restoreInputNames(names, out);
    }

    private Map<String, String> restoreInputNames(List<String> names, Map<String, String> normalized) {
        Map<String, String> result = new HashMap<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                String key = name.toLowerCase(Locale.ROOT).trim();
                if (normalized.containsKey(key)) result.put(name, normalized.get(key));
            }
        }
        return result;
    }

    /** 应用就绪后提前建立 Neo4j 连接，避免首个画像请求承担驱动冷启动成本。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmupNeo4j() {
        Thread.startVirtualThread(() -> resilience.execute("skill-align-warmup", () -> {
            try (Session session = neo4j.session()) {
                session.run("RETURN 1", Map.of()).consume();
            }
            return null;
        }));
    }

    /** 缺口技能中"可速成"的子集: 用户已具备其直接前置 (V3 6.1 prerequisite半分) */
    public java.util.Set<String> speedupables(List<String> profileIds, List<String> missingIds) {
        if (profileIds.isEmpty() || missingIds.isEmpty()) return java.util.Set.of();
        Neo4jResilience.QueryResult<java.util.Set<String>> query = resilience.execute("skill-speedupables", () -> {
            try (Session session = neo4j.session()) {
                return new java.util.HashSet<>(session.run("""
                    MATCH (p:Skill)-[:PREREQUISITE]->(r:Skill)
                    WHERE p.node_id IN $profileIds AND r.node_id IN $missingIds
                    RETURN DISTINCT r.node_id AS id
                    """, Map.of("profileIds", profileIds, "missingIds", missingIds),
                        TransactionConfig.builder().withTimeout(queryTimeout).build())
                        .list(r -> r.get("id").asString()));
            }
        });
        return query.available() ? query.value() : java.util.Set.of();
    }
}
