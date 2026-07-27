package com.tutor.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 稀疏通道融合单测 (Phase 2 V4 2.1): 三路 RRF 验证稀疏通道独立贡献 + 互不干扰。
 * 不连真实 DB, 仅测 fuse() 纯函数逻辑。
 */
class FusedRetrieverSparseTest {

    @Test
    @DisplayName("稀疏通道独立贡献: 仅稀疏命中 → 得分 = BETA/(K+1)")
    void sparseOnlyChannel() {
        Map<String, Double> s = FusedRetriever.fuse(
                List.of(),                        // 无向量
                List.of("skill:foo"),             // 仅稀疏
                List.of(), List.of(), false);
        assertThat(s.get("skill:foo")).isEqualTo(FusedRetriever.BETA / (FusedRetriever.RRF_K + 1));
    }

    @Test
    @DisplayName("BETA=0.3 兜底而非主导: 稀疏命中必须低于向量近邻")
    void sparseNotDominating() {
        Map<String, Double> s = FusedRetriever.fuse(
                List.of("vecRank1"),
                List.of("sparseRank1"),
                List.of(), List.of(), false);
        // 向量 rank1 = 1/(K+1) ≈ 0.091; 稀疏 rank1 = BETA/(K+1) ≈ 0.027
        assertThat(s.get("vecRank1")).isGreaterThan(s.get("sparseRank1"));
    }

    @Test
    @DisplayName("稀疏命中节点同时被向量命中 → 累加而非 max")
    void sparseAndVectorAccumulate() {
        Map<String, Double> both = FusedRetriever.fuse(
                List.of("a"),
                List.of("a"),
                List.of(), List.of(), false);
        Map<String, Double> vecOnly = FusedRetriever.fuse(
                List.of("a"),
                List.of(),
                List.of(), List.of(), false);
        // 稀疏+向量 应 > 仅向量 (相加 vs 单独)
        assertThat(both.get("a")).isGreaterThan(vecOnly.get("a"));
    }

    @Test
    @DisplayName("稀疏通道 max 抑制: 同一节点多稀疏片段不累加 (与图扩展同策略)")
    void sparseMaxNotSum() {
        // 模拟两个稀疏来源都指向 x → max 而非 sum
        Map<String, Double> s = FusedRetriever.fuse(
                List.of("a"),
                List.of("x", "x"),
                List.of(), List.of(), false);
        // 期望 = BETA * (1/(K+1)) 单次
        assertThat(s.get("x")).isEqualTo(FusedRetriever.BETA / (FusedRetriever.RRF_K + 1));
    }

    @Test
    @DisplayName("三路 RRF: 向量+稀疏+扩展 互不干扰融合")
    void threeChannelFusion() {
        Map<String, Double> s = FusedRetriever.fuse(
                List.of("vec1", "vec2"),
                List.of("sparse1"),
                List.of(new GraphStore.Neighbor("vec1", "PREREQUISITE", "expand1", "X")),
                List.of("vec1"), false);
        // vec1 = 1/(K+1) (向量rank1)
        // sparse1 = BETA/(K+1)
        // expand1 = ALPHA * 1/(K+1) (vec1 rank1 的扩展)
        assertThat(s.get("vec1")).isGreaterThan(0);
        assertThat(s.get("sparse1")).isGreaterThan(0);
        assertThat(s.get("expand1")).isGreaterThan(0);
        // 三路都得正分且独立
        assertThat(s.get("vec1")).isNotEqualTo(s.get("sparse1"));
        assertThat(s.get("expand1")).isNotEqualTo(s.get("sparse1"));
    }
}