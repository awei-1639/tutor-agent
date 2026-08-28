package com.tutor.retrieval.graph;

import com.tutor.retrieval.graph.GraphStore;
import com.tutor.retrieval.GraphScope;
import com.tutor.config.Neo4jProperties;
import com.tutor.retrieval.resilience.Neo4jResilience;
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

            Neo4jProperties properties = new Neo4jProperties(2, 3, 30);
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
