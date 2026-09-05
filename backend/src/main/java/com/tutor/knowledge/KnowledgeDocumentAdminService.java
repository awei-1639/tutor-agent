package com.tutor.knowledge;

import com.tutor.config.KnowledgeIngestionProperties;
import com.tutor.config.KnowledgeUploadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 管理员文档的上传、查询、重试和软删除。 */
final class KnowledgeDocumentAdminService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentAdminService.class);

    private final KnowledgeDocumentAdminStore store;
    private final OssStorage oss;
    private final KnowledgeIngestionJobStore jobs;
    private final KnowledgeUploadRateLimiter uploadLimiter;
    private final KnowledgeOssCleanupStore ossCleanup;
    private final KnowledgeUploadProperties uploadProperties;
    private final KnowledgeIngestionProperties ingestionProperties;
    private final KnowledgeUploadSessionService uploadSessions;

    KnowledgeDocumentAdminService(KnowledgeDocumentAdminStore store, OssStorage oss, KnowledgeIngestionJobStore jobs,
                                   KnowledgeUploadRateLimiter uploadLimiter,
                                   KnowledgeOssCleanupStore ossCleanup,
                                   KnowledgeUploadProperties uploadProperties,
                                   KnowledgeIngestionProperties ingestionProperties,
                                   KnowledgeUploadSessionService uploadSessions) {
        this.store = store;
        this.oss = oss;
        this.jobs = jobs;
        this.uploadLimiter = uploadLimiter;
        this.ossCleanup = ossCleanup;
        this.uploadProperties = uploadProperties;
        this.ingestionProperties = ingestionProperties;
        this.uploadSessions = uploadSessions;
    }

    KnowledgeDocumentService.UploadResult upload(long adminId, MultipartFile file, String requestedTitle) {
        return upload(adminId, file, requestedTitle, null);
    }

    KnowledgeDocumentService.UploadResult upload(long adminId, MultipartFile file, String requestedTitle,
                                                 String requestedResourceKind) {
        String resourceKind = KnowledgeDocumentFilePolicy.sanitizeResourceKind(requestedResourceKind);
        if (!uploadLimiter.allow(adminId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "上传过于频繁，请稍后再试");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要上传的文档");
        }
        String filename = KnowledgeDocumentFilePolicy.safeFilename(file.getOriginalFilename());
        KnowledgeDocumentFilePolicy.requireSupported(filename);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取上传文件失败");
        }
        if (bytes.length > uploadProperties.maxFileBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "文件超过单文件限制：" + uploadProperties.maxFileSize());
        }
        KnowledgeDocumentFilePolicy.requireContentMatchesExtension(filename, bytes);
        String hash = KnowledgeDocumentFilePolicy.sha256(bytes);
        UUID duplicate = store.findDuplicate(hash).orElse(null);
        if (duplicate != null) {
            return new KnowledgeDocumentService.UploadResult(duplicate.toString(), "deduplicated", true);
        }
        if (jobs.pendingCount() >= ingestionProperties.maxPendingJobs()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文档处理队列已满，请稍后重试");
        }
        UUID id = UUID.randomUUID();
        String title = requestedTitle == null || requestedTitle.isBlank()
                ? KnowledgeDocumentFilePolicy.titleFrom(filename) : requestedTitle.trim();
        String objectKey = oss.documentKey(id, filename);
        boolean storedInOss = false;
        try {
            oss.put(objectKey, bytes, file.getContentType());
            storedInOss = true;
            store.insertUploaded(id, title, filename, file.getContentType(), bytes.length, hash, objectKey,
                    resourceKind == null ? "document" : resourceKind, adminId);
        } catch (RuntimeException e) {
            if (storedInOss) {
                try {
                    oss.delete(objectKey);
                } catch (RuntimeException cleanup) {
                    if (ossCleanup != null) ossCleanup.enqueue(objectKey, "upload_compensation");
                    log.error("OSS orphan cleanup failed key={}", objectKey, cleanup);
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "文档上传到 OSS 失败，请检查 OSS 配置和权限");
        }
        store.audit(adminId, "KNOWLEDGE_DOCUMENT_UPLOADED", id);
        return new KnowledgeDocumentService.UploadResult(id.toString(), "uploaded", false);
    }

    List<Map<String, Object>> list(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return store.list(safeLimit);
    }

    void retry(long adminId, UUID id) {
        Map<String, Object> document = document(id);
        if (document.get("deleted_at") != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已删除文档不能重试");
        }
        store.markRetry(id);
        store.audit(adminId, "KNOWLEDGE_DOCUMENT_RETRIED", id);
        jobs.enqueue(id, store.generation(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "文档不存在")));
    }

    void softDelete(long adminId, UUID id) {
        Map<String, Object> document = document(id);
        if (document.get("deleted_at") != null) return;
        KnowledgeDocumentAdminStore.PendingUpload pendingUpload = store.pendingUpload(id).orElse(null);
        store.softDelete(id);
        String objectKey = (String) document.get("oss_object_key");
        String pendingUploadId = pendingUpload == null ? null : pendingUpload.uploadId();
        if (!uploadSessions.cleanupUploadArtifact(objectKey, pendingUploadId)) {
            if (pendingUploadId != null && !pendingUploadId.isBlank()) {
                log.error("multipart upload abort will be retried by OSS lifecycle key={}", objectKey);
            } else {
                log.error("OSS document cleanup queued key={}", objectKey);
            }
        }
        store.audit(adminId, "KNOWLEDGE_DOCUMENT_SOFT_DELETED", id);
    }

    Map<String, Object> activeDocument(UUID id, long generation) {
        try {
            return store.activeDocument(id, generation);
        } catch (Exception e) {
            throw new IllegalStateException("文档已删除或已有更新");
        }
    }

    private Map<String, Object> document(UUID id) {
        try {
            return store.document(id).orElseThrow();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
    }

}
