package com.tutor.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/** Owns document parsing, chunking, embedding staging, and publication for ingestion jobs. */
final class KnowledgeIngestionService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private static final int MAX_CHUNKS = 300;
    private static final int MAX_INDEXED_CHARS = 300 * 3_600;
    private static final String DOCUMENT_TRUNCATION_MARKER =
            "\n\n[文档中间内容因索引资源上限省略]\n\n";

    private final JdbcTemplate jdbc;
    private final OssStorage oss;
    private final KnowledgeIngestionJobStore jobs;
    private final StructuredChunker chunker;
    private final ClamAvScanner scanner;
    private final KnowledgeEmbeddingStagingService embeddingStager;
    private final KnowledgeChunkPublicationService chunkPublisher;
    private final DocumentTextExtractor textExtractor;
    private final KnowledgeDocumentAdminService adminDocuments;

    KnowledgeIngestionService(JdbcTemplate jdbc, OssStorage oss, KnowledgeIngestionJobStore jobs,
                               StructuredChunker chunker, ClamAvScanner scanner,
                               KnowledgeEmbeddingStagingService embeddingStager,
                               KnowledgeChunkPublicationService chunkPublisher,
                               DocumentTextExtractor textExtractor,
                               KnowledgeDocumentAdminService adminDocuments) {
        this.jdbc = jdbc;
        this.oss = oss;
        this.jobs = jobs;
        this.chunker = chunker;
        this.scanner = scanner;
        this.embeddingStager = embeddingStager;
        this.chunkPublisher = chunkPublisher;
        this.textExtractor = textExtractor;
        this.adminDocuments = adminDocuments;
    }

    void ingest(KnowledgeIngestionJobStore.Job job) {
            Map<String, Object> document = adminDocuments.activeDocument(job.documentId(), job.documentGeneration());
            requireLease(jobs.stage(job, "parsing"));
            byte[] content = oss.get((String) document.get("oss_object_key"));
            KnowledgeDocumentFilePolicy.requireContentMatchesExtension(
                    (String) document.get("original_filename"), content);
            scanner.scan(content);
            String hash = KnowledgeDocumentFilePolicy.sha256(content);
            jdbc.update("UPDATE knowledge_documents SET content_hash=? WHERE id=? AND generation=? AND deleted_at IS NULL",
                    hash, job.documentId(), job.documentGeneration());
            String text = textExtractor.extract(content, (String) document.get("original_filename"));
            boolean partial = false;
            String truncationReason = null;
            if (text.length() > MAX_INDEXED_CHARS) {
                text = retainHeadTail(text, MAX_INDEXED_CHARS);
                partial = true;
                truncationReason = "解析文本超过 1080000 字符上限，保留文档头尾";
            }
            StructuredChunker.ChunkingResult chunking = chunker.chunkWithStatus(
                    text, (String) document.get("original_filename"), MAX_CHUNKS);
            List<StructuredChunker.Chunk> chunks = chunking.chunks();
            if (chunking.truncated()) {
                partial = true;
                truncationReason = appendTruncationReason(truncationReason,
                        "chunk 数量达到 " + MAX_CHUNKS + " 上限");
            }
            if (chunks.isEmpty()) throw new IllegalArgumentException("文档未解析出有效文本");
            requireLease(jobs.stage(job, "embedding"));
            jdbc.update("DELETE FROM knowledge_document_chunk_staging WHERE job_id=?", job.id());
            embedAndStage(job, chunks);
            requireLease(jobs.stage(job, "publishing"));
            publish(job, chunks.size(), partial, truncationReason);
            log.info("知识文档已入库 document={} chunks={}", job.documentId(), chunks.size());
    }

    private void embedAndStage(KnowledgeIngestionJobStore.Job job, List<StructuredChunker.Chunk> chunks) {
        embeddingStager.embedAndStage(job, chunks);
    }

    private static void requireLease(boolean active) {
        if (!active) throw new IllegalStateException("摄取任务租约已失效，放弃提交结果");
    }

    static String retainHeadTail(String text, int maxChars) {
        if (text == null || text.length() <= maxChars || maxChars <= 0) return text == null ? "" : text;
        int available = maxChars - DOCUMENT_TRUNCATION_MARKER.length();
        if (available <= 0) return text.substring(0, maxChars);
        int head = (int) Math.floor(available * 0.6D);
        int tail = available - head;
        return text.substring(0, head) + DOCUMENT_TRUNCATION_MARKER
                + text.substring(text.length() - tail);
    }

    private static String appendTruncationReason(String current, String additional) {
        if (current == null || current.isBlank()) return additional;
        return current + "; " + additional;
    }

    private void publish(KnowledgeIngestionJobStore.Job job, int chunkCount, boolean partial, String truncationReason) {
        chunkPublisher.publish(job, chunkCount, partial, truncationReason);
    }
}
