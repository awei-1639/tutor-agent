package com.tutor.knowledge.retrieval.fusion;

import com.tutor.platform.llm.EmbeddingGateway;
import com.tutor.knowledge.retrieval.GraphScope;
import com.tutor.knowledge.retrieval.graph.GraphExpansionPolicy;
import com.tutor.knowledge.retrieval.graph.GraphStore;
import com.tutor.knowledge.retrieval.vector.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalCandidatePipelineTest {
    private final EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final GraphStore graphStore = mock(GraphStore.class);
    private final EntityAliasStore aliasStore = mock(EntityAliasStore.class);
    private final RetrievalProfile profile = new RetrievalProfile(
            "test", 20, 12, 10, 8, 4, 6, 40, 10,
            0.85D, 0.30D, 0.40D, 1.0D, 0.15D);

    @Test
    void vectorOnlyPathDoesNotInvokeSparseOrGraphChannels() {
        VectorStore.VectorHit hit = new VectorStore.VectorHit("skill:a", "skill", "text", 0.9D);
        when(embeddings.embedQuery("q", "trace")).thenReturn(new float[]{1F});
        when(vectorStore.search(any(float[].class), anyInt(), any(GraphScope.class)))
                .thenReturn(List.of(hit));

        RetrievalCandidatePipeline pipeline = pipeline();
        RetrievalCandidatePipeline.CandidateOutcome result = pipeline.retrieve(
                "q", 1, "trace", false, GraphExpansionPolicy.none(),
                GraphScope.publicOnly(), false);

        assertThat(result.evidences()).extracting("nodeId").containsExactly("skill:a");
        assertThat(result.telemetry().denseCandidates()).isEqualTo(1);
        verify(vectorStore, never()).sparseSearch(anyString(), anyInt(), any(Double.class), any(GraphScope.class));
        verify(graphStore, never()).expand(anyList(), anyInt(), anyInt(), any(), any(GraphScope.class));
    }

    @Test
    void embeddingFailureFallsBackToSparseCandidates() {
        VectorStore.VectorHit hit = new VectorStore.VectorHit("skill:sparse", "skill", "text", 0.6D);
        when(embeddings.embedQuery("q", "trace")).thenThrow(new IllegalStateException("embedding down"));
        when(vectorStore.sparseSearch(anyString(), anyInt(), anyDouble(), any(GraphScope.class)))
                .thenReturn(List.of(hit));

        RetrievalCandidatePipeline.CandidateOutcome result = pipeline().retrieve(
                "q", 3, "trace", true, GraphExpansionPolicy.none(),
                GraphScope.publicOnly(), false);

        assertThat(result.evidences()).extracting("nodeId").containsExactly("skill:sparse");
        assertThat(result.telemetry().embeddingDegraded()).isTrue();
    }

    @Test
    void fusedPathLoadsGraphCandidatesAndReportsTelemetry() {
        VectorStore.VectorHit source = new VectorStore.VectorHit("skill:a", "skill", "source", 0.9D);
        VectorStore.VectorHit expanded = new VectorStore.VectorHit("skill:b", "skill", "expanded", 0.1D);
        GraphExpansionPolicy policy = GraphExpansionPolicy.of(
                new GraphExpansionPolicy.Rule("PREREQUISITE", GraphExpansionPolicy.Direction.OUTGOING));
        GraphStore.Neighbor neighbor = new GraphStore.Neighbor(
                "skill:a", "PREREQUISITE", "skill:b", "B",
                GraphExpansionPolicy.Direction.OUTGOING, 1D, "seed", "active", "skill");
        when(embeddings.embedQuery("q", "trace")).thenReturn(new float[]{1F});
        when(vectorStore.search(any(float[].class), anyInt(), any(GraphScope.class)))
                .thenReturn(List.of(source));
        when(vectorStore.sparseSearch(anyString(), anyInt(), any(Double.class), any(GraphScope.class)))
                .thenReturn(List.of());
        when(aliasStore.resolveNodeIds(anyList(), anyInt())).thenReturn(List.of());
        when(graphStore.findSeedIds(anyList(), anyInt(), any(GraphScope.class))).thenReturn(List.of());
        when(graphStore.expand(anyList(), anyInt(), anyInt(), any(), any(GraphScope.class)))
                .thenReturn(List.of(neighbor));
        when(vectorStore.byNodeIds(List.of("skill:b"), GraphScope.publicOnly()))
                .thenReturn(Map.of("skill:b", expanded));

        RetrievalCandidatePipeline.CandidateOutcome result = pipeline().retrieve(
                "q", 2, "trace", true, policy, GraphScope.publicOnly(), false);

        assertThat(result.evidences()).extracting("nodeId")
                .contains("skill:a", "skill:b");
        assertThat(result.telemetry().graphCandidates()).isEqualTo(1);
        assertThat(result.telemetry().graphExpansionSources()).isEqualTo(1);
    }

    private RetrievalCandidatePipeline pipeline() {
        return new RetrievalCandidatePipeline(embeddings, vectorStore, graphStore, aliasStore, profile);
    }
}
