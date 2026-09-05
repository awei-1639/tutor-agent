package com.tutor.knowledge.retrieval.vector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.tutor.knowledge.retrieval.GraphScope;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Collectors;

/** kg_chunks 的 pgvector 检索封装 */
@Component
public class VectorStore {
    private final JdbcTemplate jdbc;

    public VectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record VectorHit(String nodeId, String nodeType, String chunkText, double score, String sourceUrl,
                            String sourceStatus, String contentHash) {
        public VectorHit(String nodeId, String nodeType, String chunkText, double score) {
            this(nodeId, nodeType, chunkText, score, null, null, null);
        }
        public VectorHit(String nodeId, String nodeType, String chunkText, double score, String sourceUrl) {
            this(nodeId, nodeType, chunkText, score, sourceUrl, null, null);
        }
    }

    public List<VectorHit> search(float[] queryVec, int topK) {
        return search(queryVec, topK, GraphScope.publicOnly());
    }

    public List<VectorHit> search(float[] queryVec, int topK, GraphScope scope) {
        GraphScope effectiveScope = scope == null ? GraphScope.publicOnly() : scope;
        String vec = toVectorLiteral(queryVec);
        List<VectorHit> hits = new ArrayList<>(jdbc.query(
                "SELECT node_id, node_type, chunk_text, 1 - (embedding <=> ?::vector) AS score, source_url, source_status, content_hash " +
                        "FROM kg_chunks WHERE (visibility='public' AND ? OR owner_user_id=? OR (CAST(? AS VARCHAR) IS NOT NULL AND tenant_id=?)) " +
                        "ORDER BY embedding <=> ?::vector LIMIT ?",
                (rs, i) -> hit(rs),
                vec, effectiveScope.includePublic(), effectiveScope.userId(),
                effectiveScope.tenantId(), effectiveScope.tenantId(), vec, topK));
        hits.addAll(jdbc.query("""
                SELECT 'doc:' || c.document_id || ':' || c.chunk_index AS node_id,
                       coalesce(d.resource_kind, 'document') AS node_type, c.chunk_text,
                       1 - (c.embedding <=> ?::vector) AS score,
                       'knowledge://document/' || c.document_id || '#chunk=' || c.chunk_index AS source_url,
                       'managed' AS source_status, c.content_hash
                FROM knowledge_document_chunks c
                JOIN knowledge_documents d ON d.id=c.document_id
                WHERE d.status='indexed' AND d.deleted_at IS NULL AND d.created_by=?
                ORDER BY c.embedding <=> ?::vector LIMIT ?
                """, (rs, i) -> hit(rs),
                vec, effectiveScope.userId(), vec, topK));
        return hits.stream().sorted(Comparator.comparingDouble(VectorHit::score).reversed()).limit(topK).toList();
    }

    /**
     * 稀疏检索 (Phase 2 V4 2.1): pg_trgm 三字符 gram 模糊匹配, 解决 bge-m3 对专有名词
     * (LoRA/vLLM/RAG) 召回不稳的问题。score = similarity(query, chunk_text) ∈ [0,1]。
     * 阈值默认 0.15 过滤噪声片段(经验值, 字符串越长匹配越松)。
     */
    public List<VectorHit> sparseSearch(String query, int topK, double minSimilarity) {
        return sparseSearch(query, topK, minSimilarity, GraphScope.publicOnly());
    }

    public List<VectorHit> sparseSearch(String query, int topK, double minSimilarity, GraphScope scope) {
        GraphScope effectiveScope = scope == null ? GraphScope.publicOnly() : scope;
        List<VectorHit> hits = new ArrayList<>(jdbc.query(
                "SELECT node_id, node_type, chunk_text, similarity(chunk_text, ?) AS sim, source_url, source_status, content_hash " +
                        "FROM kg_chunks WHERE chunk_text % ? AND (visibility='public' AND ? OR owner_user_id=? OR (CAST(? AS VARCHAR) IS NOT NULL AND tenant_id=?)) ORDER BY sim DESC LIMIT ?",
                (rs, i) -> hit(rs),
                query, query, effectiveScope.includePublic(), effectiveScope.userId(),
                effectiveScope.tenantId(), effectiveScope.tenantId(), topK));
        hits.addAll(jdbc.query("""
                SELECT 'doc:' || c.document_id || ':' || c.chunk_index AS node_id,
                       coalesce(d.resource_kind, 'document') AS node_type, c.chunk_text, similarity(c.chunk_text, ?) AS sim,
                       'knowledge://document/' || c.document_id || '#chunk=' || c.chunk_index AS source_url,
                       'managed' AS source_status, c.content_hash
                FROM knowledge_document_chunks c
                JOIN knowledge_documents d ON d.id=c.document_id
                WHERE d.status='indexed' AND d.deleted_at IS NULL AND d.created_by=? AND c.chunk_text % ?
                ORDER BY sim DESC LIMIT ?
                """, (rs, i) -> hit(rs),
                query, effectiveScope.userId(), query, topK));
        return hits.stream()
                .filter(h -> h.score() >= minSimilarity)
                .sorted(Comparator.comparingDouble(VectorHit::score).reversed())
                .limit(topK)
                .toList();
    }

    /** 按 node_id 批量取 chunk (图谱扩展节点回捞文本用) */
    public Map<String, VectorHit> byNodeIds(List<String> nodeIds) {
        return byNodeIds(nodeIds, GraphScope.publicOnly());
    }

    public Map<String, VectorHit> byNodeIds(List<String> nodeIds, GraphScope scope) {
        if (nodeIds.isEmpty()) return Map.of();
        GraphScope effectiveScope = scope == null ? GraphScope.publicOnly() : scope;
        String in = nodeIds.stream().map(x -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(nodeIds);
        args.add(effectiveScope.includePublic());
        args.add(effectiveScope.userId());
        args.add(effectiveScope.tenantId());
        args.add(effectiveScope.tenantId());
        return jdbc.query(
                "SELECT node_id, node_type, chunk_text, 0 AS score, source_url, source_status, content_hash FROM kg_chunks " +
                        "WHERE node_id IN (" + in + ") AND (visibility='public' AND ? OR owner_user_id=? OR (CAST(? AS VARCHAR) IS NOT NULL AND tenant_id=?))",
                (rs, i) -> hit(rs),
                args.toArray()).stream().collect(Collectors.toMap(VectorHit::nodeId, h -> h));
    }

    private static VectorHit hit(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new VectorHit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4),
                rs.getString(5), rs.getString(6), rs.getString(7));
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
