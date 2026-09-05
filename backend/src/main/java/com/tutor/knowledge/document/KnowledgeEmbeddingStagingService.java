package com.tutor.knowledge.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.llm.EmbeddingGateway;
import com.tutor.knowledge.retrieval.vector.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** 负责文档切片的批量向量化与围栏暂存，不承担上传、解析或发布职责。 */
final class KnowledgeEmbeddingStagingService {
    private static final int BATCH_SIZE = 16;

    private final JdbcTemplate jdbc;
    private final EmbeddingGateway gateway;
    private final KnowledgeIngestionJobStore jobs;
    private final KnowledgeEmbeddingCache embeddingCache;
    private final Executor embeddingExecutor;
    private final ObjectMapper mapper = new ObjectMapper();

    KnowledgeEmbeddingStagingService(JdbcTemplate jdbc, EmbeddingGateway gateway, KnowledgeIngestionJobStore jobs,
                                    KnowledgeEmbeddingCache embeddingCache, Executor embeddingExecutor) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.jobs = jobs;
        this.embeddingCache = embeddingCache;
        this.embeddingExecutor = embeddingExecutor;
    }

    void embedAndStage(KnowledgeIngestionJobStore.Job job, List<StructuredChunker.Chunk> chunks) {
        List<CompletableFuture<List<EmbeddedChunk>>> futures = new ArrayList<>();
        for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
            int from = start;
            int to = Math.min(chunks.size(), start + BATCH_SIZE);
            List<StructuredChunker.Chunk> batch = chunks.subList(from, to);
            futures.add(CompletableFuture.supplyAsync(() -> embedBatch(job, batch, from), embeddingExecutor));
        }
        for (CompletableFuture<List<EmbeddedChunk>> future : futures) {
            for (EmbeddedChunk embedded : future.join()) {
                if (!jobs.heartbeat(job)) throw new IllegalStateException("知识入库任务租约已失效");
                stage(job, embedded);
            }
        }
    }

    private List<EmbeddedChunk> embedBatch(KnowledgeIngestionJobStore.Job job, List<StructuredChunker.Chunk> batch, int from) {
        List<String> inputs = batch.stream().map(KnowledgeEmbeddingStagingService::embeddingText).toList();
        List<float[]> vectors = embeddingCache == null
                ? gateway.embedBatch(inputs, "doc-" + job.documentId() + "-batch-" + from)
                : embeddingCache.getOrComputeBatch(inputs, "doc-" + job.documentId() + "-batch-" + from);
        if (vectors.size() != batch.size()) throw new IllegalStateException("embedding batch size mismatch");
        List<EmbeddedChunk> result = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) result.add(new EmbeddedChunk(from + i, batch.get(i), vectors.get(i)));
        return result;
    }

    private void stage(KnowledgeIngestionJobStore.Job job, EmbeddedChunk embedded) {
        StructuredChunker.Chunk chunk = embedded.chunk();
        jdbc.update("""
                INSERT INTO knowledge_document_chunk_staging
                (job_id, lease_token, chunk_index, chunk_text, content_hash, embedding, section_path, block_type, metadata, page_from, page_to)
                SELECT ?, ?, ?, ?, ?, ?::vector, ?, ?, ?::jsonb, ?, ?
                WHERE EXISTS (SELECT 1 FROM knowledge_ingestion_jobs WHERE id=? AND lease_token=? AND status='processing' AND lease_until > now())
                """, job.id(), job.leaseToken(), embedded.index(), chunk.text(), sha256(chunk.text().getBytes(StandardCharsets.UTF_8)),
                VectorStore.toVectorLiteral(embedded.embedding()), chunk.sectionPath(), chunk.blockType(),
                metadataJson(chunk.metadata()), chunk.pageFrom(), chunk.pageTo(), job.id(), job.leaseToken());
    }

    private String metadataJson(Map<String, Object> metadata) {
        try { return mapper.writeValueAsString(metadata); }
        catch (Exception e) { throw new IllegalStateException("无法序列化文档切片元数据", e); }
    }

    private static String embeddingText(StructuredChunker.Chunk chunk) {
        StringBuilder prefix = new StringBuilder();
        if (chunk.sectionPath() != null && !chunk.sectionPath().isBlank()) prefix.append("章节: ").append(chunk.sectionPath()).append('\n');
        if (chunk.blockType() != null && !chunk.blockType().isBlank()) prefix.append("内容类型: ").append(chunk.blockType()).append('\n');
        if (chunk.pageFrom() != null) prefix.append("页码: ").append(chunk.pageFrom()).append(chunk.pageTo() == null || chunk.pageTo().equals(chunk.pageFrom()) ? "" : "-" + chunk.pageTo()).append('\n');
        return prefix.append(chunk.text()).toString();
    }

    private static String sha256(byte[] input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)); }
        catch (Exception e) { throw new IllegalStateException("无法计算文档摘要", e); }
    }

    private record EmbeddedChunk(int index, StructuredChunker.Chunk chunk, float[] embedding) {}
}
