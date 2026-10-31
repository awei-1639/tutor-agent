package com.tutor.knowledge.retrieval.graph;

import com.tutor.platform.config.Neo4jProperties;
import com.tutor.knowledge.retrieval.GraphScope;
import com.tutor.knowledge.retrieval.resilience.Neo4jResilience;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Neo4j 图谱查询封装。白名单边一跳扩展 (Spike3 验证的模式) */
@Component
public class GraphStore {
    private final Driver driver;
    private final Neo4jResilience resilience;
    private final Duration queryTimeout;

    @Autowired
    public GraphStore(Driver driver, Neo4jResilience resilience, Neo4jProperties properties) {
        this.driver = driver;
        this.resilience = resilience;
        this.queryTimeout = Duration.ofSeconds(properties.queryTimeoutSeconds());
    }

    public record Neighbor(String srcId, String rel, String dstId, String dstName,
                           GraphExpansionPolicy.Direction direction,
                           double confidence, String source, String status, String dstType) {
    }
    /**
     * 受控一跳扩展: 固定关系/方向白名单, 每源配额+总量双限。
     * 每源配额(子查询LIMIT)防止REQUIRES这类高扇出边耗尽全局名额——首轮评估教训。
     */
    public List<Neighbor> expand(List<String> nodeIds, int perSource, int limit,
                                 GraphExpansionPolicy policy, GraphScope scope) {
        if (nodeIds == null || nodeIds.isEmpty() || policy == null || !policy.enabled()
                || perSource <= 0 || limit <= 0) return List.of();
        GraphScope effectiveScope = scope == null ? GraphScope.publicOnly() : scope;
        List<String> ids = nodeIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) return List.of();
        String branches = policy.rules().stream()
                .map(GraphStore::branch)
                .collect(Collectors.joining(" UNION ALL "));
        String cypher = ("""
                MATCH (n:Seed)
                WHERE n.node_id IN $ids AND %s
                CALL (n) {
                    %s
                }
                RETURN n.node_id AS src, rel, m.node_id AS dst,
                       coalesce(m.name, m.title) AS dstName, direction,
                       confidence, edge_source, edge_status,
                       CASE WHEN m:Resource THEN 'resource'
                            WHEN m:Skill THEN 'skill'
                            WHEN m:Job THEN 'job'
                            WHEN m:Company THEN 'company'
                            ELSE 'unknown' END AS dst_type
                ORDER BY src, confidence DESC, rel, dst, direction
                """).formatted(scopePredicate("n"), branches);
        Neo4jResilience.QueryResult<List<Neighbor>> result = resilience.execute("graph-expand", () -> {
            try (Session session = driver.session()) {
                Map<String, Object> params = new java.util.HashMap<>();
                params.put("ids", ids);
                params.put("perSource", perSource);
                params.put("userId", effectiveScope.userId());
                params.put("tenantId", effectiveScope.tenantId());
                params.put("includePublic", effectiveScope.includePublic());
                List<Neighbor> raw = session.run(cypher, params,
                        TransactionConfig.builder().withTimeout(queryTimeout).build())
                        .list(rec -> new Neighbor(
                                rec.get("src").asString(), rec.get("rel").asString(),
                                rec.get("dst").asString(), rec.get("dstName").asString(""),
                                GraphExpansionPolicy.Direction.valueOf(rec.get("direction").asString()),
                                rec.get("confidence").asDouble(1.0D),
                                rec.get("edge_source").asString("seed"),
                                rec.get("edge_status").asString("active"),
                                rec.get("dst_type").asString("unknown")));
                return applyQuotas(raw, perSource, limit);
            }
        });
        return result.available() ? result.value() : List.of();
    }

    /**
     * 在图查询后同时应用两类扩展配额。查询在每个策略分支内有一个 LIMIT；
     * 第二次处理使配额跨越全部允许的关系类型和方向，作为单个源节点预算生效。
     */
    static List<Neighbor> applyQuotas(List<Neighbor> neighbors, int perSource, int limit) {
        if (neighbors == null || neighbors.isEmpty() || perSource <= 0 || limit <= 0) {
            return List.of();
        }
        Map<String, List<Neighbor>> bySource = new LinkedHashMap<>();
        for (Neighbor neighbor : neighbors) {
            if (neighbor != null) {
                bySource.computeIfAbsent(neighbor.srcId(), ignored -> new java.util.ArrayList<>())
                        .add(neighbor);
            }
        }
        Set<String> seen = new java.util.HashSet<>();
        List<Neighbor> bounded = new java.util.ArrayList<>(Math.min(neighbors.size(), limit));
        for (List<Neighbor> sourceNeighbors : bySource.values()) {
            sourceNeighbors.sort(Comparator.comparingDouble(Neighbor::confidence).reversed()
                    .thenComparing(Neighbor::rel)
                    .thenComparing(Neighbor::dstId)
                    .thenComparing(neighbor -> neighbor.direction().name()));
            int count = 0;
            for (Neighbor neighbor : sourceNeighbors) {
                String edge = neighbor.srcId() + "|" + neighbor.rel() + "|"
                        + neighbor.dstId() + "|" + neighbor.direction();
                if (count >= perSource || !seen.add(edge)) continue;
                count++;
                bounded.add(neighbor);
                if (bounded.size() >= limit) return bounded;
            }
        }
        return bounded;
    }

    /** 解析精确的 Seed 名称/别名，使罕见术语即便未被稠密/稀疏分块检索命中，
     * 仍可作为图扩展起点。 */
    public List<String> findSeedIds(List<String> aliases, int limit, GraphScope scope) {
        if (aliases == null || aliases.isEmpty() || limit <= 0) return List.of();
        List<String> normalized = aliases.stream().filter(a -> a != null && !a.isBlank())
                .map(a -> a.trim().toLowerCase(java.util.Locale.ROOT)).distinct().limit(32).toList();
        if (normalized.isEmpty()) return List.of();
        GraphScope effectiveScope = scope == null ? GraphScope.publicOnly() : scope;
        String cypher = "MATCH (n:Seed) WHERE " + scopePredicate("n")
                + " WITH n, toLower(coalesce(n.name, n.title, '')) AS nodeName,"
                + " [a IN coalesce(n.aliases, []) | toLower(a)] AS nodeAliases"
                + " WHERE nodeName IN $aliases"
                + " OR any(a IN nodeAliases WHERE a IN $aliases)"
                + " OR any(alias IN $aliases WHERE size(alias) >= 3 AND "
                + "(nodeName CONTAINS alias"
                + " OR any(a IN nodeAliases WHERE a CONTAINS alias OR alias CONTAINS a)))"
                + " WITH n, nodeName, nodeAliases,"
                + " CASE WHEN nodeName IN $aliases THEN 300"
                + " WHEN any(a IN nodeAliases WHERE a IN $aliases) THEN 200 ELSE 100 END AS matchQuality,"
                + " reduce(best = 0, alias IN $aliases |"
                + " CASE WHEN size(alias) >= 3 AND (nodeName CONTAINS alias"
                + " OR any(a IN nodeAliases WHERE a CONTAINS alias OR alias CONTAINS a))"
                + " THEN CASE WHEN size(alias) > best THEN size(alias) ELSE best END ELSE best END) AS matchLength"
                + " RETURN n.node_id AS node_id ORDER BY matchQuality DESC, matchLength DESC, n.node_id LIMIT $limit";
        Neo4jResilience.QueryResult<List<String>> result = resilience.execute("graph-seed-lookup", () -> {
            try (Session session = driver.session()) {
                Map<String, Object> params = new java.util.HashMap<>();
                params.put("aliases", normalized);
                params.put("limit", limit);
                params.put("userId", effectiveScope.userId());
                params.put("tenantId", effectiveScope.tenantId());
                params.put("includePublic", effectiveScope.includePublic());
                return session.run(cypher, params,
                                TransactionConfig.builder().withTimeout(queryTimeout).build())
                        .list(record -> record.get("node_id").asString());
            }
        });
        return result.available() ? result.value() : List.of();
    }

    /**
     * 两跳前置的置信度折扣：保证直接前置永远排在传递前置之前，第二跳只补位不抢位。
     *
     * <p>取值 0.85 是算出来的，不是拍的：第二跳得分 = alpha * weight * dampen / (rrfK + seedRank)，
     * 要挤进 top5 必须高于 dense 第 5 名的 1/(rrfK+5)=0.0667。dampen=0.6 时只有 seedRank≤5 才够，
     * 实测第二跳恰好停在 9-12 名；0.85 可覆盖 seedRank≤10，同时仍低于第一跳（1.0），
     * 保持「直接前置优先于传递前置」的语义。
     */
    static final double SECOND_HOP_DAMPEN = 0.85D;

    private static String branch(GraphExpansionPolicy.Rule rule) {
        return rule.maxHops() > 1 ? multiHopBranch(rule) : singleHopBranch(rule);
    }

    /**
     * 两跳扩展 (LEARNING + PREREQUISITE)。评测集 gold 标的是前置关系的传递闭包，
     * 单跳只能覆盖直接前置，第二跳前置（python-basics / calculus 这类最底层基础）
     * 连 top20 都进不来。这里用变长路径一次查到 1..2 跳，按深度打折后排序；
     * 同一邻居的多条路径只保留得分最高的一条，避免重复占满 perSource 名额。
     */
    private static String multiHopBranch(GraphExpansionPolicy.Rule rule) {
        String relation = rule.relation();
        String visible = scopePredicate("m");
        String walk = switch (rule.direction()) {
            case INCOMING -> "(m:Seed)-[:" + relation + "*1.." + rule.maxHops() + "]->(n)";
            case OUTGOING -> "(n)-[:" + relation + "*1.." + rule.maxHops() + "]->(m:Seed)";
        };
        String pathEdge = "x IN relationships(p)";
        String bestEdge = "x IN relationships(bestPath)";
        return "MATCH p = " + walk + " "
                + "WHERE m.node_id <> n.node_id AND " + visible + " "
                + "AND all(" + pathEdge + " WHERE coalesce(x.status, 'active')='active' "
                + "AND coalesce(x.confidence, 1.0) >= " + rule.minConfidence() + ") "
                + "WITH m, p, length(p) AS depth, "
                + "reduce(mn = 1.0, " + pathEdge + " | CASE WHEN coalesce(x.confidence, 1.0) < mn "
                + "THEN coalesce(x.confidence, 1.0) ELSE mn END) AS pathConfidence "
                + "WITH m, p, pathConfidence * CASE WHEN depth = 1 THEN 1.0 ELSE "
                + SECOND_HOP_DAMPEN + " END AS weighted "
                + "ORDER BY weighted DESC, m.node_id "
                + "WITH m, collect(p)[0] AS bestPath, collect(weighted)[0] AS bestConfidence "
                + "RETURN '" + relation + "' AS rel, m, '" + rule.direction().name() + "' AS direction, "
                + "bestConfidence AS confidence, "
                + "head([" + bestEdge + " | coalesce(x.source, 'seed')]) AS edge_source, "
                + "head([" + bestEdge + " | coalesce(x.status, 'active')]) AS edge_status "
                + "ORDER BY confidence DESC, m.node_id LIMIT $perSource";
    }

    private static String singleHopBranch(GraphExpansionPolicy.Rule rule) {
        String relation = rule.relation();
        String visible = scopePredicate("m");
        String quality = "coalesce(r.status, 'active')='active' AND coalesce(r.confidence, 1.0) >= "
                + rule.minConfidence();
        return switch (rule.direction()) {
            case OUTGOING -> "MATCH (n)-[r:" + relation + "]->(m:Seed) "
                    + "WHERE " + visible + " AND " + quality + " "
                    + "RETURN type(r) AS rel, m, 'OUTGOING' AS direction, "
                    + "coalesce(r.confidence, 1.0) AS confidence, "
                    + "coalesce(r.source, 'seed') AS edge_source, "
                    + "coalesce(r.status, 'active') AS edge_status "
                    + "ORDER BY coalesce(r.confidence, 1.0) DESC, m.node_id LIMIT $perSource";
            case INCOMING -> "MATCH (m:Seed)-[r:" + relation + "]->(n) "
                    + "WHERE " + visible + " AND " + quality + " "
                    + "RETURN type(r) AS rel, m, 'INCOMING' AS direction, "
                    + "coalesce(r.confidence, 1.0) AS confidence, "
                    + "coalesce(r.source, 'seed') AS edge_source, "
                    + "coalesce(r.status, 'active') AS edge_status "
                    + "ORDER BY coalesce(r.confidence, 1.0) DESC, m.node_id LIMIT $perSource";
        };
    }

    private static String scopePredicate(String variable) {
        return "(($includePublic AND coalesce(" + variable + ".visibility, 'public')='public') "
                + "OR " + variable + ".owner_user_id=$userId "
                + "OR ($tenantId IS NOT NULL AND " + variable + ".tenant_id=$tenantId))";
    }
}
