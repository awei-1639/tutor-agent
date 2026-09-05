package com.tutor.knowledge.document;

import com.tutor.llm.EmbeddingGateway;
import com.tutor.config.KnowledgeIngestionProperties;
import com.tutor.config.KnowledgeUploadProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Qualifier;

/** 管理员知识库文档：OSS 存原文件，异步解析/切分/向量化，完成后纳入 RAG 检索。 */
@Service
public class KnowledgeDocumentService {
    private final KnowledgeUploadSessionService uploadSessions;
    private final KnowledgeDocumentAdminService adminDocuments;
    private final KnowledgeIngestionService ingestionService;

    public KnowledgeDocumentService(JdbcTemplate jdbc, OssStorage oss, EmbeddingGateway gateway,
                                     KnowledgeIngestionJobStore jobs, TransactionTemplate transactions,
                                     StructuredChunker chunker, KnowledgeUploadRateLimiter uploadLimiter,
                                     ClamAvScanner scanner, AliyunOcrClient ocr, KnowledgeOssCleanupStore ossCleanup,
                                     KnowledgeEmbeddingCache embeddingCache, KnowledgeUploadProperties uploadProperties,
                                     KnowledgeIngestionProperties ingestionProperties,
                                     @Qualifier("knowledgeEmbeddingExecutor") Executor embeddingExecutor) {
        KnowledgeEmbeddingStagingService embeddingStager =
                new KnowledgeEmbeddingStagingService(jdbc, gateway, jobs, embeddingCache, embeddingExecutor);
        KnowledgeChunkPublicationService chunkPublisher =
                new KnowledgeChunkPublicationService(jdbc, transactions, jobs);
        DocumentTextExtractor textExtractor = new DocumentTextExtractor(ocr);
        KnowledgeUploadSessionStore uploadStore = new KnowledgeUploadSessionStore(jdbc, transactions);
        this.uploadSessions = new KnowledgeUploadSessionService(
                uploadStore, oss, jobs, uploadLimiter, ossCleanup, uploadProperties, ingestionProperties);
        KnowledgeDocumentAdminStore adminStore = new KnowledgeDocumentAdminStore(jdbc, transactions, jobs);
        this.adminDocuments = new KnowledgeDocumentAdminService(
                adminStore, oss, jobs, uploadLimiter, ossCleanup,
                uploadProperties, ingestionProperties, uploadSessions);
        this.ingestionService = new KnowledgeIngestionService(
                jdbc, oss, jobs, chunker, scanner, embeddingStager, chunkPublisher,
                textExtractor, adminDocuments);
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

    public UploadSession prepareUpload(long adminId, String requestedFilename, long sizeBytes,
                                       String contentType, String requestedTitle, String requestedResourceKind) {
        return uploadSessions.prepareUpload(adminId, requestedFilename, sizeBytes, contentType,
                requestedTitle, requestedResourceKind);
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

    public UploadResult upload(long adminId, MultipartFile file, String requestedTitle, String requestedResourceKind) {
        return adminDocuments.upload(adminId, file, requestedTitle, requestedResourceKind);
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
        ingestionService.ingest(job);
    }

    static String retainHeadTail(String text, int maxChars) {
        return KnowledgeIngestionService.retainHeadTail(text, maxChars);
    }

}
