package com.tutor.knowledge.document;

import com.tutor.platform.llm.EmbeddingGateway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.HexFormat;

/** 由 PostgreSQL 事务咨询锁围栏保护的跨 Worker Embedding 缓存。 */
@Component
public class KnowledgeEmbeddingCache {
    private static final String MODEL = "embed";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EmbeddingGateway gateway;

    public KnowledgeEmbeddingCache(JdbcTemplate jdbc, TransactionTemplate transactions, EmbeddingGateway gateway) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.gateway = gateway;
    }

    public float[] getOrCompute(String text, String traceId) {
        String hash = sha256(text);
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Long.class, hash + ":" + MODEL);
            var cached = jdbc.query("SELECT embedding::text FROM knowledge_embedding_cache WHERE content_hash=? AND model_name=?",
                    (rs, row) -> parse(rs.getString(1)), hash, MODEL);
            if (!cached.isEmpty()) return cached.getFirst();
            float[] vector = gateway.embed(text, traceId);
            jdbc.update("INSERT INTO knowledge_embedding_cache(content_hash, model_name, embedding) VALUES (?, ?, ?::vector) ON CONFLICT DO NOTHING",
                    hash, MODEL, VectorLiteral.of(vector));
            return vector;
        });
    }

    /** 先命中缓存，再通过一次有界请求计算所有未命中项。 */
    public List<float[]> getOrComputeBatch(List<String> texts, String traceId) {
        if (texts == null || texts.isEmpty()) return List.of();
        List<String> safeTexts = List.copyOf(texts);
        List<String> hashes = safeTexts.stream().map(KnowledgeEmbeddingCache::sha256).toList();
        return transactions.execute(status -> {
            hashes.stream().distinct().sorted()
                    .forEach(hash -> jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))",
                            Long.class, hash + ":" + MODEL));
            Map<String, float[]> resolved = new HashMap<>();
            for (String hash : hashes) {
                if (resolved.containsKey(hash)) continue;
                var cached = jdbc.query("SELECT embedding::text FROM knowledge_embedding_cache WHERE content_hash=? AND model_name=?",
                        (rs, row) -> parse(rs.getString(1)), hash, MODEL);
                if (!cached.isEmpty()) resolved.put(hash, cached.getFirst());
            }
            List<String> misses = new ArrayList<>();
            List<String> missHashes = new ArrayList<>();
            for (int i = 0; i < safeTexts.size(); i++) {
                if (!resolved.containsKey(hashes.get(i)) && !missHashes.contains(hashes.get(i))) {
                    missHashes.add(hashes.get(i));
                    misses.add(safeTexts.get(i));
                }
            }
            if (!misses.isEmpty()) {
                List<float[]> vectors = gateway.embedBatch(misses, traceId);
                if (vectors.size() != misses.size()) throw new IllegalStateException("embedding cache batch mismatch");
                for (int i = 0; i < misses.size(); i++) {
                    resolved.put(missHashes.get(i), vectors.get(i));
                    jdbc.update("INSERT INTO knowledge_embedding_cache(content_hash, model_name, embedding) VALUES (?, ?, ?::vector) ON CONFLICT DO NOTHING",
                            missHashes.get(i), MODEL, VectorLiteral.of(vectors.get(i)));
                }
            }
            return hashes.stream().map(resolved::get).collect(Collectors.toList());
        });
    }

    private static float[] parse(String value) {
        String body = value.trim().replace("[", "").replace("]", "");
        String[] parts = body.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) result[i] = Float.parseFloat(parts[i].trim());
        return result;
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static final class VectorLiteral {
        static String of(float[] vector) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < vector.length; i++) { if (i > 0) out.append(','); out.append(vector[i]); }
            return out.append(']').toString();
        }
    }
}
