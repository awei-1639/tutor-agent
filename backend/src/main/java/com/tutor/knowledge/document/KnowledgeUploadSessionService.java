package com.tutor.knowledge.document;

import com.aliyun.oss.model.PartETag;
import com.tutor.config.KnowledgeIngestionProperties;
import com.tutor.config.KnowledgeUploadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 管理 OSS 直传会话、分片合并和过期上传清理。 */
final class KnowledgeUploadSessionService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeUploadSessionService.class);
    private static final Duration DIRECT_UPLOAD_VALIDITY = Duration.ofMinutes(15);

    private final KnowledgeUploadSessionStore store;
    private final OssStorage oss;
    private final KnowledgeIngestionJobStore jobs;
    private final KnowledgeUploadRateLimiter uploadLimiter;
    private final KnowledgeOssCleanupStore ossCleanup;
    private final KnowledgeUploadProperties uploadProperties;
    private final KnowledgeIngestionProperties ingestionProperties;

    KnowledgeUploadSessionService(KnowledgeUploadSessionStore store, OssStorage oss, KnowledgeIngestionJobStore jobs,
                                   KnowledgeUploadRateLimiter uploadLimiter,
                                   KnowledgeOssCleanupStore ossCleanup,
                                   KnowledgeUploadProperties uploadProperties,
                                   KnowledgeIngestionProperties ingestionProperties) {
        this.store = store;
        this.oss = oss;
        this.jobs = jobs;
        this.uploadLimiter = uploadLimiter;
        this.ossCleanup = ossCleanup;
        this.uploadProperties = uploadProperties;
        this.ingestionProperties = ingestionProperties;
    }

    KnowledgeDocumentService.UploadSession prepareUpload(long adminId, String requestedFilename, long sizeBytes,
                                                         String contentType, String requestedTitle) {
        return prepareUpload(adminId, requestedFilename, sizeBytes, contentType, requestedTitle, null);
    }

    KnowledgeDocumentService.UploadSession prepareUpload(long adminId, String requestedFilename, long sizeBytes,
                                                         String contentType, String requestedTitle,
                                                         String requestedResourceKind) {
        String resourceKind = KnowledgeDocumentFilePolicy.sanitizeResourceKind(requestedResourceKind);
        if (!uploadLimiter.allow(adminId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "上传过于频繁，请稍后再试");
        }
        if (sizeBytes <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件不能为空");
        if (sizeBytes > uploadProperties.maxFileBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "文件超过单文件限制：" + uploadProperties.maxFileSize());
        }
        String filename = KnowledgeDocumentFilePolicy.safeFilename(requestedFilename);
        KnowledgeDocumentFilePolicy.requireSupported(filename);
        if (jobs.pendingCount() >= ingestionProperties.maxPendingJobs()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文档处理队列已满，请稍后重试");
        }
        UUID id = UUID.randomUUID();
        String title = requestedTitle == null || requestedTitle.isBlank()
                ? KnowledgeDocumentFilePolicy.titleFrom(filename) : requestedTitle.trim();
        String objectKey = oss.documentKey(id, filename);
        String effectiveType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        Instant expiresAt = Instant.now().plus(uploadProperties.sessionTtl());
        URL uploadUrl = null;
        String uploadId = null;
        long partSize = 0;
        List<KnowledgeDocumentService.PartUpload> parts = List.of();
        try {
            boolean multipart = sizeBytes >= uploadProperties.multipartThreshold().toBytes();
            if (multipart) {
                uploadId = oss.initiateMultipartUpload(objectKey, effectiveType);
                partSize = uploadProperties.multipartPartSize().toBytes();
                int partCount = Math.toIntExact((sizeBytes + partSize - 1) / partSize);
                if (partCount > 10_000) throw new IllegalArgumentException("文件分片数量超过 OSS 上限");
                List<KnowledgeDocumentService.PartUpload> generated = new ArrayList<>(partCount);
                for (int part = 1; part <= partCount; part++) {
                    generated.add(new KnowledgeDocumentService.PartUpload(part,
                            oss.presignedUploadPartUrl(objectKey, uploadId, part, DIRECT_UPLOAD_VALIDITY).toString()));
                }
                parts = List.copyOf(generated);
            } else {
                uploadUrl = oss.presignedPutUrl(objectKey, effectiveType, DIRECT_UPLOAD_VALIDITY);
            }
            final String finalUploadId = uploadId;
            final long finalPartSize = partSize;
            store.create(id, adminId, title, filename, effectiveType, sizeBytes,
                    KnowledgeDocumentFilePolicy.sha256(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    objectKey, resourceKind == null ? "document" : resourceKind,
                    expiresAt, finalUploadId, finalPartSize);
        } catch (RuntimeException error) {
            if (uploadId != null) {
                try {
                    oss.abortMultipartUpload(objectKey, uploadId);
                } catch (RuntimeException abort) {
                    log.warn("failed to abort multipart upload key={}", objectKey, abort);
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "无法创建 OSS 直传会话，请检查 OSS 配置和权限");
        }
        audit(adminId, "KNOWLEDGE_DOCUMENT_UPLOAD_SESSION_CREATED", id);
        return new KnowledgeDocumentService.UploadSession(id.toString(),
                uploadUrl == null ? "" : uploadUrl.toString(), expiresAt.toString(),
                uploadProperties.maxFileBytes(), uploadId != null, uploadId, partSize, parts,
                List.of(), false, "pending");
    }

    KnowledgeDocumentService.UploadSession resumeUpload(long adminId, UUID id) {
        Map<String, Object> session;
        try {
            session = store.findForAdmin(id, adminId);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上传会话不存在");
        }
        String status = String.valueOf(session.get("status"));
        String objectKey = (String) session.get("object_key");
        long expectedSize = ((Number) session.get("expected_size")).longValue();
        String uploadId = session.get("upload_id") == null ? null : String.valueOf(session.get("upload_id"));
        long partSize = session.get("part_size") == null ? 0 : ((Number) session.get("part_size")).longValue();
        if ("expired".equals(status)) {
            throw new ResponseStatusException(HttpStatus.GONE, "上传会话已过期，请重新上传");
        }
        if ("completed".equals(status)) {
            return new KnowledgeDocumentService.UploadSession(id.toString(), "",
                    Instant.now().plus(DIRECT_UPLOAD_VALIDITY).toString(), uploadProperties.maxFileBytes(),
                    uploadId != null, uploadId, partSize, List.of(), List.of(), true, status);
        }
        Instant currentExpiry = instantOf(session.get("expires_at"));
        if (currentExpiry.isBefore(Instant.now())) {
            if (cleanupUploadArtifact(objectKey, uploadId)) expireUploadSession(id);
            throw new ResponseStatusException(HttpStatus.GONE, "上传会话已过期，请重新上传");
        }

        Instant renewedExpiry = Instant.now().plus(uploadProperties.sessionTtl());
        int renewed = store.renew(id, adminId, renewedExpiry);
        if (renewed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "上传会话状态已变化");

        try {
            if (uploadId != null) {
                if (partSize <= 0) throw new IllegalStateException("multipart session has no part size");
                int partCount = Math.toIntExact((expectedSize + partSize - 1) / partSize);
                List<KnowledgeDocumentService.PartUpload> parts = new ArrayList<>(partCount);
                for (int part = 1; part <= partCount; part++) {
                    parts.add(new KnowledgeDocumentService.PartUpload(part,
                            oss.presignedUploadPartUrl(objectKey, uploadId, part, DIRECT_UPLOAD_VALIDITY).toString()));
                }
                List<KnowledgeDocumentService.CompletedPart> completed = oss.listMultipartParts(objectKey, uploadId).stream()
                        .filter(part -> part.getPartNumber() >= 1 && part.getPartNumber() <= partCount)
                        .map(part -> new KnowledgeDocumentService.CompletedPart(part.getPartNumber(), part.getETag()))
                        .toList();
                return new KnowledgeDocumentService.UploadSession(id.toString(), "", renewedExpiry.toString(),
                        uploadProperties.maxFileBytes(), true, uploadId, partSize, List.copyOf(parts), completed,
                        false, "pending");
            }
            boolean objectReady = false;
            try {
                objectReady = oss.metadata(objectKey).getContentLength() == expectedSize;
            } catch (RuntimeException ignored) {
                // 单次 PUT 尚未到达 OSS，返回新的上传 URL。
            }
            String contentType = store.contentType(id);
            String freshUrl = objectReady ? ""
                    : oss.presignedPutUrl(objectKey, contentType, DIRECT_UPLOAD_VALIDITY).toString();
            return new KnowledgeDocumentService.UploadSession(id.toString(), freshUrl, renewedExpiry.toString(),
                    uploadProperties.maxFileBytes(), false, null, 0, List.of(), List.of(), objectReady, "pending");
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法恢复 OSS 上传会话，请稍后重试");
        }
    }

    KnowledgeDocumentService.UploadResult completeUpload(long adminId, UUID id,
                                                          List<KnowledgeDocumentService.CompletedPart> completedParts) {
        Map<String, Object> session;
        try {
            session = store.findForAdmin(id, adminId);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上传会话不存在");
        }
        String sessionStatus = (String) session.get("status");
        if ("completed".equals(sessionStatus)) {
            return new KnowledgeDocumentService.UploadResult(id.toString(), "uploaded", false);
        }
        String objectKey = (String) session.get("object_key");
        String uploadId = (String) session.get("upload_id");
        Instant expiresAt = instantOf(session.get("expires_at"));
        if (expiresAt.isBefore(Instant.now())) {
            if (cleanupUploadArtifact(objectKey, uploadId)) expireUploadSession(id);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传会话已过期，请重新上传");
        }
        long expectedSize = ((Number) session.get("expected_size")).longValue();
        long partSize = session.get("part_size") == null ? 0 : ((Number) session.get("part_size")).longValue();
        if (uploadId != null) completeMultipart(objectKey, uploadId, expectedSize, partSize, completedParts);
        try {
            long actualSize = oss.metadata(objectKey).getContentLength();
            if (actualSize != expectedSize) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OSS 文件大小校验失败，请重新上传");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OSS 文件尚未上传完成");
        }
        try {
            store.complete(id, adminId, () -> jobs.enqueue(id, 0));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "上传会话已完成或已失效");
        }
        audit(adminId, "KNOWLEDGE_DOCUMENT_UPLOADED", id);
        return new KnowledgeDocumentService.UploadResult(id.toString(), "uploaded", false);
    }

    void cleanupExpiredUploadSessions() {
        List<Map<String, Object>> expired = store.expiredPending();
        for (Map<String, Object> row : expired) {
            UUID id = (UUID) row.get("id");
            String objectKey = (String) row.get("key");
            String uploadId = (String) row.get("uploadId");
            if (cleanupUploadArtifact(objectKey, uploadId)) expireUploadSession(id);
        }
    }

    boolean cleanupUploadArtifact(String objectKey, String uploadId) {
        try {
            if (uploadId == null || uploadId.isBlank()) oss.delete(objectKey);
            else oss.abortMultipartUpload(objectKey, uploadId);
            return true;
        } catch (RuntimeException cleanup) {
            if ((uploadId == null || uploadId.isBlank()) && ossCleanup != null) {
                ossCleanup.enqueue(objectKey, "upload_session_expired");
            }
            log.warn("expired OSS upload cleanup will retry key={}", objectKey, cleanup);
            return false;
        }
    }

    private void completeMultipart(String objectKey, String uploadId, long expectedSize, long partSize,
                                   List<KnowledgeDocumentService.CompletedPart> completedParts) {
        if (partSize <= 0 || completedParts == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分片上传参数无效");
        }
        int expectedParts = Math.toIntExact((expectedSize + partSize - 1) / partSize);
        if (completedParts.size() != expectedParts
                || completedParts.stream().map(KnowledgeDocumentService.CompletedPart::partNumber).distinct().count() != expectedParts
                || completedParts.stream().anyMatch(part -> part.partNumber() < 1 || part.partNumber() > expectedParts
                || part.etag() == null || part.etag().isBlank() || part.etag().length() > 512)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分片列表不完整或无效");
        }
        try {
            oss.metadata(objectKey);
            return;
        } catch (RuntimeException ignored) {
            // 分片对象在完成合并前不可见，继续执行合并流程。
        }
        List<PartETag> etags = completedParts.stream()
                .sorted(Comparator.comparingInt(KnowledgeDocumentService.CompletedPart::partNumber))
                .map(part -> new PartETag(part.partNumber(), part.etag()))
                .toList();
        try {
            oss.completeMultipartUpload(objectKey, uploadId, etags);
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OSS 分片合并失败，请重试");
        }
    }

    private void expireUploadSession(UUID id) {
        store.expire(id);
    }

    private void audit(long adminId, String action, UUID documentId) {
        store.audit(adminId, action, documentId);
    }

    private static Instant instantOf(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.time.ZonedDateTime zoned) return zoned.toInstant();
        if (value instanceof java.util.Date date) return date.toInstant();
        return Instant.parse(String.valueOf(value));
    }
}
