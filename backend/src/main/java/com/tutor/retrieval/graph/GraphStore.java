package com.tutor.retrieval.graph;

import com.tutor.config.Neo4jProperties;
import com.tutor.retrieval.resilience.Neo4jResilience;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Neo4j 图谱查询封装。白名单边一跳扩展 (Spike3 验证的模式) */
@Component
public class GraphStore {
    private final Driver driver;
    private final Neo4jResilience resilience;
    private final Duration queryTimeout;

    public GraphStore(Driver driver) {
        this(driver, new Neo4jResilience(Neo4jProperties.defaults()), Neo4jProperties.defaults());
    }

    @Autowired
    public GraphStore(Driver driver, Neo4jResilience resilience, Neo4jProperties properties) {
        this.driver = driver;
        this.resilience = resilience;
        this.queryTimeout = Duration.ofSeconds(properties.queryTimeoutSeconds());
    }

    public record Neighbor(String srcId, String rel, String dstId, String dstName) {}

    /**
     * 受控一跳扩展: 白名单边, 无向匹配, 每源配额+总量双限。
     * 每源配额(子查询LIMIT)防止REQUIRES这类高扇出边耗尽全局名额——首轮评估教训。
     */
    public List<Neighbor> expand(List<String> nodeIds, int perSource, int limit) {
        if (nodeIds.isEmpty()) return List.of();
        Neo4jResilience.QueryResult<List<Neighbor>> result = resilience.execute("graph-expand", () -> {
            try (Session session = driver.session()) {
                return session.run("""
                    MATCH (n:Seed) WHERE n.node_id IN $ids
                    CALL (n) {
                        MATCH (n)-[r:PREREQUISITE|TEACHES|LEADS_TO|REQUIRES]-(m:Seed)
                        RETURN type(r) AS rel, m LIMIT $perSource
                    }
                    RETURN n.node_id AS src, rel, m.node_id AS dst,
                           coalesce(m.name, m.title) AS dstName
                    LIMIT $limit
                    """, Map.of("ids", nodeIds, "perSource", perSource, "limit", limit),
                        TransactionConfig.builder().withTimeout(queryTimeout).build())
                        .list(rec -> new Neighbor(
                                rec.get("src").asString(), rec.get("rel").asString(),
                                rec.get("dst").asString(), rec.get("dstName").asString("")));
            }
        });
        return result.available() ? result.value() : List.of();
    }
}
