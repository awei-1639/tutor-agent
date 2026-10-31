package com.tutor.knowledge.retrieval.graph;

import com.tutor.knowledge.retrieval.graph.GraphStore;
import com.tutor.knowledge.retrieval.GraphScope;
import com.tutor.knowledge.retrieval.resilience.Neo4jResilience;
import com.tutor.platform.config.Neo4jProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    /**
     * 两跳规则必须生成变长路径查询, 且把第二跳按 SECOND_HOP_DAMPEN 打折 —— 否则第二跳前置的
     * RRF 分和直接前置同分, 会把直接前置挤下去 (multi_hop 二轮诊断 2026-10-31)。
     */
    @Test
    void twoHopRuleEmitsVariableLengthPathWithSecondHopDampening() {
        String cypher = captureCypher(new GraphExpansionPolicy.Rule(
                "PREREQUISITE", GraphExpansionPolicy.Direction.INCOMING, 0.80D, 2.0D, 2));

        assertThat(cypher)
                .contains("[:PREREQUISITE*1..2]")
                .contains("relationships(p)")
                .contains("bestPath")
                .contains(String.valueOf(GraphStore.SECOND_HOP_DAMPEN));
    }

    @Test
    void singleHopRuleKeepsPlainPatternWithoutPathVariable() {
        String cypher = captureCypher(new GraphExpansionPolicy.Rule(
                "TEACHES", GraphExpansionPolicy.Direction.INCOMING, 0.85D, 0.90D));

        assertThat(cypher)
                .contains("[r:TEACHES]")
                .doesNotContain("*1..2")
                .doesNotContain("relationships(p)");
    }

    private static String captureCypher(GraphExpansionPolicy.Rule rule) {
        Driver driver = mock(Driver.class);
        Session session = mock(Session.class);
        Result result = mock(Result.class);
        when(driver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap(), any(TransactionConfig.class))).thenReturn(result);
        doReturn(List.of()).when(result).list(any());

        GraphStore store = new GraphStore(driver, new Neo4jResilience(1, Duration.ofSeconds(1)),
                new Neo4jProperties(1, 1, 1));
        store.expand(List.of("skill:pytorch-basics"), 6, 40,
                GraphExpansionPolicy.of(rule), GraphScope.publicOnly());

        ArgumentCaptor<String> cypher = ArgumentCaptor.forClass(String.class);
        verify(session).run(cypher.capture(), anyMap(), any(TransactionConfig.class));
        return cypher.getValue();
    }
}
