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
