package com.tutor.retrieval.graph;

import com.tutor.retrieval.graph.GraphStore;
import com.tutor.retrieval.resilience.Neo4jResilience;
import com.tutor.config.Neo4jProperties;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;

import java.time.Duration;
import java.util.List;

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

        assertThat(store.expand(List.of("skill:java"), 5, 10)).isEmpty();
    }
}
