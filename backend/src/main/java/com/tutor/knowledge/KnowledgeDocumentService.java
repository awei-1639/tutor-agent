package com.tutor.knowledge;

import com.tutor.llm.EmbeddingGateway;
import com.tutor.config.KnowledgeIngestionProperties;
import com.tutor.config.KnowledgeUploadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Qualifier;

/** 管理员知识库文档：OSS 存原文件，异步解析/切分/向量化，完成后纳入 RAG 检索。 */
@Service
public class KnowledgeDocumentService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);
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
    private final KnowledgeUploadSessionService uploadSessions;
    private final KnowledgeDocumentAdminService adminDocuments;

    public KnowledgeDocumentService(JdbcTemplate jdbc, OssStorage oss, EmbeddingGateway gateway,
                                     KnowledgeIngestionJobStore jobs, TransactionTemplate transactions,
                                     StructuredChunker chunker, KnowledgeUploadRateLimiter uploadLimiter,
                                     ClamAvScanner scanner, AliyunOcrClient ocr, KnowledgeOssCleanupStore ossCleanup,
                                     KnowledgeEmbeddingCache embeddingCache, KnowledgeUploadProperties uploadProperties,
                                     KnowledgeIngestionProperties ingestionProperties,
                                     @Qualifier("knowledgeEmbeddingExecutor") Executor embeddingExecutor) {
        this.jdbc = jdbc;
        this.oss = oss;
        this.jobs = jobs;
        this.chunker = chunker;
        this.scanner = scanner;
        this.embeddingStager = new KnowledgeEmbeddingStagingService(jdbc, gateway, jobs, embeddingCache, embeddingExecutor);
        this.chunkPublisher = new KnowledgeChunkPublicationService(jdbc, transactions, jobs);
        this.textExtractor = new DocumentTextExtractor(ocr);
        this.uploadSessions = new KnowledgeUploadSessionService(
                jdbc, oss, jobs, transactions, uploadLimiter, ossCleanup, uploadProperties, ingestionProperties);
        this.adminDocuments = new KnowledgeDocumentAdminService(
                jdbc, oss, jobs, transactions, uploadLimiter, ossCleanup,
                uploadProperties, ingestionProperties, uploadSessions);
    }

    public record UploadResult(String id, String status, boolean deduplicated) {}

    public record PartUpload(int partNumber, String uploadUrl) {}
    public record CompletedPart(int partNumber, String etag) {}
    public record UploadSession(String id, String uploadUrl, String expiresAt, long maxBytes,
                                boolean multipart, String uploadId, long partSize, List<PartUpload> parts,
                                List<CompletedPart> completedParts, boolean objectReady, String status) {
        public UploadSession(String id, String uploadUrl, String expiresAt, long maxBytes,
                             boolean multipart, String uploadId, long partSize, List<PartUpload> parts) {
            this(id, uploadUrl, expiresAt, maxBytes, multipart, uploadId, partSize, parts, List.of(), false, "pending");
        }
    }

    public UploadSession prepareUpload(long adminId, String requestedFilename, long sizeBytes,
                                       String contentType, String requestedTitle) {
        return uploadSessions.prepareUpload(adminId, requestedFilename, sizeBytes, contentType, requestedTitle);
    }

    /** 重新签发短期 OSS URL，并返回 OSS 已持久化的分片。 */
    public UploadSession resumeUpload(long adminId, UUID id) {
        return uploadSessions.resumeUpload(adminId, id);
    }

    public UploadResult completeUpload(long adminId, UUID id) {
        return completeUpload(adminId, id, List.of());
    }

    public UploadResult completeUpload(long adminId, UUID id, List<CompletedPart> completedParts) {
        return uploadSessions.completeUpload(adminId, id, completedParts);
    }

    @Scheduled(fixedDelayString = "${knowledge.upload.session-cleanup-ms:300000}")
    public void cleanupExpiredUploadSessions() {
        uploadSessions.cleanupExpiredUploadSessions();
    }

    public UploadResult upload(long adminId, MultipartFile file, String requestedTitle) {
        return adminDocuments.upload(adminId, file, requestedTitle);
    }

    public List<Map<String, Object>> list(int limit) {
        return adminDocuments.list(limit);
    }

    public void retry(long adminId, UUID id) {
        adminDocuments.retry(adminId, id);
    }

    public void softDelete(long adminId, UUID id) {
        adminDocuments.softDelete(adminId, id);
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
