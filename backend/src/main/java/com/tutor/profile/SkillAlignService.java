package com.tutor.profile;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public SkillAlignService(Driver neo4j, JdbcTemplate jdbc) {
        this.neo4j = neo4j;
        this.jdbc = jdbc;
    }

    /** 批量对齐; 返回 name→nodeId, 未命中值为null */
    public Map<String, String> align(List<String> names) {
        Map<String, String> out = new HashMap<>();
        if (names.isEmpty()) return out;

        // 1) 查缓存
        List<String> misses = new java.util.ArrayList<>();
        for (String name : names) {
            List<String> hit = jdbc.query(
                    "SELECT node_id FROM skill_alignments WHERE raw_name = ?",
                    (rs, i) -> rs.getString(1), name.toLowerCase());
            if (hit.isEmpty()) misses.add(name);
            else out.put(name, hit.get(0)); // 可能为null(缓存过的miss)
        }
        if (misses.isEmpty()) return out;

        // 2) Neo4j 精确/别名命中
        Map<String, String> found = new HashMap<>();
        try (Session session = neo4j.session()) {
            session.run("""
                    UNWIND $names AS nm
                    MATCH (s:Skill)
                    WHERE toLower(s.name) = toLower(nm)
                       OR any(a IN s.aliases WHERE toLower(a) = toLower(nm))
                    RETURN nm, s.node_id AS id
                    """, Map.of("names", misses))
                    .forEachRemaining(r -> found.putIfAbsent(r.get("nm").asString(), r.get("id").asString()));
        }
        for (String name : misses) {
            String nodeId = found.get(name);
            out.put(name, nodeId);
            jdbc.update("""
                    INSERT INTO skill_alignments (raw_name, node_id, method, score) VALUES (?,?,?,1.0)
                    ON CONFLICT (raw_name) DO UPDATE SET node_id=EXCLUDED.node_id, method=EXCLUDED.method
                    """, name.toLowerCase(), nodeId, nodeId != null ? "exact_or_alias" : "miss");
            if (nodeId == null) log.info("技能对齐未命中: {} (进待对齐队列)", name);
        }
        return out;
    }

    /** 缺口技能中"可速成"的子集: 用户已具备其直接前置 (V3 6.1 prerequisite半分) */
    public java.util.Set<String> speedupables(List<String> profileIds, List<String> missingIds) {
        if (profileIds.isEmpty() || missingIds.isEmpty()) return java.util.Set.of();
        try (Session session = neo4j.session()) {
            return new java.util.HashSet<>(session.run("""
                    MATCH (p:Skill)-[:PREREQUISITE]->(r:Skill)
                    WHERE p.node_id IN $profileIds AND r.node_id IN $missingIds
                    RETURN DISTINCT r.node_id AS id
                    """, Map.of("profileIds", profileIds, "missingIds", missingIds))
                    .list(r -> r.get("id").asString()));
        }
    }
}
