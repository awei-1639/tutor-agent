package com.tutor.knowledge.retrieval.graph;

import com.tutor.knowledge.retrieval.graph.GraphStore;
import com.tutor.knowledge.retrieval.GraphScope;
import com.tutor.platform.config.Neo4jProperties;
import com.tutor.knowledge.retrieval.resilience.Neo4jResilience;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Neo4j 真实容器验证 GraphStore 的一跳白名单扩展。 */
@Testcontainers
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class GraphStoreNeo4jIT {
    @Container
    static final Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5-community");

    @Test
    void expandsOnlyWhitelistedRelationshipsFromSeedNodes() {
        try (Driver driver = GraphDatabase.driver(neo4j.getBoltUrl(),
                AuthTokens.basic("neo4j", neo4j.getAdminPassword()));
             Session session = driver.session()) {
            session.run("""
                    CREATE (java:Seed:Skill {node_id: 'skill:java', name: 'Java'})
                    CREATE (spring:Seed:Skill {node_id: 'skill:spring', name: 'Spring Boot'})
                    CREATE (resource:Seed:Resource {node_id: 'res:spring-guide', title: 'Spring Guide'})
                    CREATE (hidden:Seed:Skill {node_id: 'skill:hidden', name: 'Hidden'})
                    CREATE (java)-[:PREREQUISITE]->(spring)
                    CREATE (resource)-[:TEACHES]->(spring)
                    CREATE (java)-[:UNRELATED]->(hidden)
                    """).consume();

            // 这里刻意不使用生产默认的 2s 客户端事务超时：冷启动或宿主内存紧张时首条
            // 查询很容易超过 2s，而 Neo4jResilience 只熔断不重试，GraphStore 会静默返回
            // 空列表，把环境抖动伪装成功能回归。本用例验证的是扩展语义，超时/熔断策略
            // 由 Neo4jResilienceTest 覆盖，因此给它一个宽裕的超时以保证结果确定。
            Neo4jProperties properties = new Neo4jProperties(60, 3, 30);
            GraphStore store = new GraphStore(driver, new Neo4jResilience(properties), properties);
            List<GraphStore.Neighbor> neighbors = store
                    .expand(List.of("skill:java", "res:spring-guide"), 5, 10,
                            GraphExpansionPolicy.of(
                                    new GraphExpansionPolicy.Rule("PREREQUISITE", GraphExpansionPolicy.Direction.OUTGOING),
                                    new GraphExpansionPolicy.Rule("TEACHES", GraphExpansionPolicy.Direction.OUTGOING)),
                            GraphScope.publicOnly());

            assertThat(neighbors).containsExactlyInAnyOrder(
                    new GraphStore.Neighbor("skill:java", "PREREQUISITE", "skill:spring", "Spring Boot",
                            GraphExpansionPolicy.Direction.OUTGOING, 1D, "seed", "active", "skill"),
                    new GraphStore.Neighbor("res:spring-guide", "TEACHES", "skill:spring", "Spring Boot",
                            GraphExpansionPolicy.Direction.OUTGOING, 1D, "seed", "active", "skill"));
            assertThat(neighbors).noneMatch(n -> n.dstId().equals("skill:hidden"));

            List<GraphStore.Neighbor> prerequisites = store
                    .expand(List.of("skill:spring"), 5, 10,
                            GraphExpansionPolicy.of(new GraphExpansionPolicy.Rule(
                                    "PREREQUISITE", GraphExpansionPolicy.Direction.INCOMING)),
                            GraphScope.publicOnly());
            assertThat(prerequisites).containsExactly(
                    new GraphStore.Neighbor("skill:spring", "PREREQUISITE", "skill:java", "Java",
                            GraphExpansionPolicy.Direction.INCOMING, 1D, "seed", "active", "skill"));
        }
    }
}
