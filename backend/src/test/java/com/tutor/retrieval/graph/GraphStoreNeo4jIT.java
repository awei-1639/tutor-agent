package com.tutor.retrieval.graph;

import com.tutor.retrieval.graph.GraphStore;
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

            List<GraphStore.Neighbor> neighbors = new GraphStore(driver)
                    .expand(List.of("skill:java", "res:spring-guide"), 5, 10);

            assertThat(neighbors).containsExactlyInAnyOrder(
                    new GraphStore.Neighbor("skill:java", "PREREQUISITE", "skill:spring", "Spring Boot"),
                    new GraphStore.Neighbor("res:spring-guide", "TEACHES", "skill:spring", "Spring Boot"));
            assertThat(neighbors).noneMatch(n -> n.dstId().equals("skill:hidden"));
        }
    }
}
