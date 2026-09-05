package com.tutor.knowledge.retrieval.graph;

import com.tutor.knowledge.retrieval.graph.GraphStore;
import com.tutor.knowledge.retrieval.GraphScope;
import com.tutor.knowledge.retrieval.resilience.Neo4jResilience;
import com.tutor.config.Neo4jProperties;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphStoreTest {
    @Test
    void queryFailureFallsBackToEmptyNeighbors() {
        Driver driver = mock(Driver.class);
        when(driver.session()).thenThrow(new IllegalStateException("neo4j unavailable"));
        Neo4jResilience resilience = new Neo4jResilience(1, Duration.ofSeconds(1));
        GraphStore store = new GraphStore(driver, resilience, new Neo4jProperties(1, 1, 1));

        assertThat(store.expand(List.of("skill:java"), 5, 10,
                GraphExpansionPolicy.of(new GraphExpansionPolicy.Rule(
                        "PREREQUISITE", GraphExpansionPolicy.Direction.OUTGOING)),
                GraphScope.publicOnly())).isEmpty();
    }

    @Test
    void appliesPerSourceAndGlobalExpansionQuotas() {
        List<GraphStore.Neighbor> raw = new ArrayList<>();
        for (int source = 1; source <= 8; source++) {
            for (int neighbor = 1; neighbor <= 8; neighbor++) {
                raw.add(new GraphStore.Neighbor(
                        "source:" + source, "PREREQUISITE", "node:" + source + ":" + neighbor,
                        "Node " + neighbor, GraphExpansionPolicy.Direction.OUTGOING,
                        1D, "seed", "active", "unknown"));
            }
        }
        // A duplicate must not consume either quota.
        raw.add(raw.get(0));

        List<GraphStore.Neighbor> bounded = GraphStore.applyQuotas(raw, 6, 40);
        Map<String, Long> counts = bounded.stream()
                .collect(Collectors.groupingBy(GraphStore.Neighbor::srcId, Collectors.counting()));

        assertThat(bounded).hasSize(40);
        assertThat(counts.values()).allMatch(count -> count <= 6);
        assertThat(bounded.stream().map(GraphStore.Neighbor::dstId).distinct()).hasSize(40);

        List<GraphStore.Neighbor> confidenceOrdered = List.of(
                new GraphStore.Neighbor("source:confidence", "PREREQUISITE", "node:low", "Low",
                        GraphExpansionPolicy.Direction.OUTGOING, 0.20D, "seed", "active", "skill"),
                new GraphStore.Neighbor("source:confidence", "PREREQUISITE", "node:high", "High",
                        GraphExpansionPolicy.Direction.OUTGOING, 0.95D, "seed", "active", "skill"));
        assertThat(GraphStore.applyQuotas(confidenceOrdered, 1, 40))
                .extracting(GraphStore.Neighbor::dstId)
                .containsExactly("node:high");
    }
}
