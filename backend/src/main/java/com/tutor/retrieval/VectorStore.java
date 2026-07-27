package com.tutor.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** kg_chunks 的 pgvector 检索封装 */
@Component
public class VectorStore {
    private final JdbcTemplate jdbc;

    public VectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record VectorHit(String nodeId, String nodeType, String chunkText, double score) {}

    public List<VectorHit> search(float[] queryVec, int topK) {
        String vec = toVectorLiteral(queryVec);
        return jdbc.query(
                "SELECT node_id, node_type, chunk_text, 1 - (embedding <=> ?::vector) AS score " +
                        "FROM kg_chunks ORDER BY embedding <=> ?::vector LIMIT ?",
                (rs, i) -> new VectorHit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4)),
                vec, vec, topK);
    }

    /**
     * 稀疏检索 (Phase 2 V4 2.1): pg_trgm 三字符 gram 模糊匹配, 解决 bge-m3 对专有名词
     * (LoRA/vLLM/RAG) 召回不稳的问题。score = similarity(query, chunk_text) ∈ [0,1]。
     * 阈值默认 0.15 过滤噪声片段(经验值, 字符串越长匹配越松)。
     */
    public List<VectorHit> sparseSearch(String query, int topK, double minSimilarity) {
        return jdbc.query(
                "SELECT node_id, node_type, chunk_text, similarity(chunk_text, ?) AS sim " +
                        "FROM kg_chunks WHERE chunk_text % ? ORDER BY sim DESC LIMIT ?",
                (rs, i) -> new VectorHit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4)),
                query, query, topK).stream()
                .filter(h -> h.score() >= minSimilarity)
                .toList();
    }

    /** 按 node_id 批量取 chunk (图谱扩展节点回捞文本用) */
    public Map<String, VectorHit> byNodeIds(List<String> nodeIds) {
        if (nodeIds.isEmpty()) return Map.of();
        String in = nodeIds.stream().map(x -> "?").collect(Collectors.joining(","));
        return jdbc.query(
                "SELECT node_id, node_type, chunk_text FROM kg_chunks WHERE node_id IN (" + in + ")",
                (rs, i) -> new VectorHit(rs.getString(1), rs.getString(2), rs.getString(3), 0),
                nodeIds.toArray()).stream().collect(Collectors.toMap(VectorHit::nodeId, h -> h));
    }

    public static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
