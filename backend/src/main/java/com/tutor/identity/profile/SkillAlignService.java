package com.tutor.identity.profile;

import com.tutor.config.Neo4jProperties;
import com.tutor.retrieval.resilience.Neo4jResilience;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 技能实体对齐：画像技能名通过本地缓存和 Neo4j 精确/别名查询映射为图谱节点。 */
@Service
public class SkillAlignService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SkillAlignService.class);
    private final Driver neo4j;
    private final SkillAlignmentStore store;
    private final Neo4jResilience resilience;
    private final Duration queryTimeout;

    @Autowired
    public SkillAlignService(Driver neo4j, SkillAlignmentStore store, Neo4jResilience resilience,
                             Neo4jProperties properties) {
        this.neo4j = neo4j;
        this.store = store;
        this.resilience = resilience;
        this.queryTimeout = Duration.ofSeconds(properties.queryTimeoutSeconds());
    }

    /** 批量对齐；返回 name→nodeId，未命中值为 null。 */
    public Map<String, String> align(List<String> names) {
        Map<String, String> out = new HashMap<>();
        if (names == null || names.isEmpty()) return out;
        Set<String> normalized = names.stream().filter(name -> name != null && !name.isBlank())
                .map(name -> name.toLowerCase(Locale.ROOT).trim())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (normalized.isEmpty()) return out;

        Map<String, String> cached = store.findCached(List.copyOf(normalized));
        List<String> misses = new ArrayList<>();
        for (String key : normalized) {
            if (cached.containsKey(key)) out.put(key, cached.get(key));
            else misses.add(key);
        }
        if (misses.isEmpty()) return restoreInputNames(names, out);

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
        store.saveAll(misses, found);
        return restoreInputNames(names, out);
    }

    private Map<String, String> restoreInputNames(List<String> names, Map<String, String> normalized) {
        Map<String, String> result = new HashMap<>();
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            String key = name.toLowerCase(Locale.ROOT).trim();
            if (normalized.containsKey(key)) result.put(name, normalized.get(key));
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

    /** 缺口技能中可速成的子集：用户具备其直接前置技能。 */
    public Set<String> speedupables(List<String> profileIds, List<String> missingIds) {
        if (profileIds == null || profileIds.isEmpty() || missingIds == null || missingIds.isEmpty()) return Set.of();
        Neo4jResilience.QueryResult<Set<String>> query = resilience.execute("skill-speedupables", () -> {
            try (Session session = neo4j.session()) {
                return new HashSet<>(session.run("""
                        MATCH (p:Skill)-[:PREREQUISITE]->(r:Skill)
                        WHERE p.node_id IN $profileIds AND r.node_id IN $missingIds
                        RETURN DISTINCT r.node_id AS id
                        """, Map.of("profileIds", profileIds, "missingIds", missingIds),
                        TransactionConfig.builder().withTimeout(queryTimeout).build())
                        .list(r -> r.get("id").asString()));
            }
        });
        return query.available() ? query.value() : Set.of();
    }
}
