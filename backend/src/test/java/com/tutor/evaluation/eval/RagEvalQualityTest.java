package com.tutor.evaluation.eval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvalQualityTest {
    @Test
    void producesWilsonIntervalAndReleaseGateForFullGoldenSet() {
        List<Map<String, Object>> rows = List.of(row("q1", "single_hop_skill", true, 1D, null), row("q2", "multi_hop_prereq", true, 1D, null));
        Map<String, Object> overall = RagEvalQuality.aggregate(rows);
        Map<String, Object> byType = Map.of("multi_hop_prereq", RagEvalQuality.aggregate(List.of(rows.get(1))));

        Map<String, Object> gate = RagEvalQuality.qualityGate(overall, byType, 2, 2,
                new RagEvalQuality.GateThresholds(0.7, 0.4, 0.5, 0));

        assertEquals("passed", gate.get("status"));
        assertTrue((Boolean) gate.get("releaseEligible"));
        @SuppressWarnings("unchecked")
        Map<String, Object> ci = (Map<String, Object>) overall.get("hitAtKCi95");
        assertTrue((Double) ci.get("lower") <= 1D);
        assertEquals(1D, ci.get("upper"));
    }

    @Test
    void clustersResourceTypeMismatchAndMarksSampleRunIneligible() {
        Map<String, Object> failed = row("q-resource", "resource_rec", false, 0D, null);
        failed.put("gold", List.of("res:target-course"));
        failed.put("retrieved", List.of("skill:rag", "skill:python-basics"));

        List<Map<String, Object>> clusters = RagEvalQuality.diagnoseAndCluster(new ArrayList<>(List.of(failed)));
        assertEquals("resource_type_mismatch", clusters.getFirst().get("code"));

        Map<String, Object> overall = RagEvalQuality.aggregate(List.of(failed));
        Map<String, Object> gate = RagEvalQuality.qualityGate(overall, Map.of(), 1, 10,
                new RagEvalQuality.GateThresholds(0.7, 0.4, 0.5, 0));
        assertEquals("sample_only", gate.get("status"));
        assertFalse((Boolean) gate.get("releaseEligible"));
    }

    private static Map<String, Object> row(String id, String type, boolean hit, double recall, String error) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("type", type);
        row.put("hit", hit);
        row.put("recall", recall);
        row.put("rr", hit ? 1D : 0D);
        row.put("latencyMs", 10L);
        row.put("error", error);
        row.put("gold", List.of("skill:target"));
        row.put("retrieved", List.of("skill:target"));
        return row;
    }
}
