package com.tutor.knowledge;

import com.aliyun.oss.model.PartETag;
import com.tutor.config.KnowledgeIngestionProperties;
import com.tutor.config.KnowledgeUploadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionTemplate;

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

    private final JdbcTemplate jdbc;
    private final OssStorage oss;
    private final KnowledgeIngestionJobStore jobs;
    private final TransactionTemplate transactions;
    private final KnowledgeUploadRateLimiter uploadLimiter;
    private final KnowledgeOssCleanupStore ossCleanup;
    private final KnowledgeUploadProperties uploadProperties;
    private final KnowledgeIngestionProperties ingestionProperties;

    KnowledgeUploadSessionService(JdbcTemplate jdbc, OssStorage oss, KnowledgeIngestionJobStore jobs,
                                   TransactionTemplate transactions, KnowledgeUploadRateLimiter uploadLimiter,
                                   KnowledgeOssCleanupStore ossCleanup,
                                   KnowledgeUploadProperties uploadProperties,
                                   KnowledgeIngestionProperties ingestionProperties) {
        this.jdbc = jdbc;
        this.oss = oss;
        this.jobs = jobs;
        this.transactions = transactions;
        this.uploadLimiter = uploadLimiter;
        this.ossCleanup = ossCleanup;
        this.uploadProperties = uploadProperties;
        this.ingestionProperties = ingestionProperties;
    }

    KnowledgeDocumentService.UploadSession prepareUpload(long adminId, String requestedFilename, long sizeBytes,
                                                         String contentType, String requestedTitle) {
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
            transactions.executeWithoutResult(status -> {
                jdbc.update("""
                        INSERT INTO knowledge_documents
                        (id, title, original_filename, content_type, size_bytes, content_hash, oss_object_key, status, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'uploading', ?)
                        """, id, title, filename, effectiveType, sizeBytes,
                        KnowledgeDocumentFilePolicy.sha256(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        objectKey, adminId);
                jdbc.update("""
                        INSERT INTO knowledge_upload_sessions
                        (document_id, admin_user_id, object_key, expected_size, expires_at, upload_id, part_size)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, id, adminId, objectKey, sizeBytes, expiresAt, finalUploadId,
                        finalPartSize == 0 ? null : finalPartSize);
            });
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
            session = jdbc.queryForMap("""
                    SELECT s.object_key, s.expected_size, s.status, s.expires_at, s.upload_id, s.part_size
                    FROM knowledge_upload_sessions s JOIN knowledge_documents d ON d.id=s.document_id
                    WHERE s.document_id=? AND s.admin_user_id=? AND d.deleted_at IS NULL
                    """, id, adminId);
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
        int renewed = jdbc.update("""
                UPDATE knowledge_upload_sessions SET expires_at=?
                WHERE document_id=? AND admin_user_id=? AND status='pending' AND expires_at > now()
                """, renewedExpiry, id, adminId);
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
            String contentType = jdbc.queryForObject(
                    "SELECT content_type FROM knowledge_documents WHERE id=?", String.class, id);
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
            session = jdbc.queryForMap("""
                    SELECT s.object_key, s.expected_size, s.status, s.expires_at, s.upload_id, s.part_size
                    FROM knowledge_upload_sessions s JOIN knowledge_documents d ON d.id=s.document_id
                    WHERE s.document_id=? AND s.admin_user_id=? AND d.deleted_at IS NULL
                    """, id, adminId);
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
            transactions.executeWithoutResult(status -> {
                int updated = jdbc.update("""
                        UPDATE knowledge_upload_sessions SET status='completed', completed_at=now()
                        WHERE document_id=? AND admin_user_id=? AND status='pending' AND expires_at > now()
                        """, id, adminId);
                if (updated != 1) throw new IllegalStateException("上传会话状态已变化");
                jdbc.update("UPDATE knowledge_documents SET status='uploaded', error_message=NULL, updated_at=now() WHERE id=? AND status='uploading'", id);
                jobs.enqueue(id, 0);
            });
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "上传会话已完成或已失效");
        }
        audit(adminId, "KNOWLEDGE_DOCUMENT_UPLOADED", id);
        return new KnowledgeDocumentService.UploadResult(id.toString(), "uploaded", false);
    }

    void cleanupExpiredUploadSessions() {
        List<Map<String, Object>> expired = jdbc.query("""
                SELECT document_id, object_key, upload_id FROM knowledge_upload_sessions
                WHERE status='pending' AND expires_at < now()
                ORDER BY expires_at LIMIT 50
                """, (rs, rowNum) -> Map.of("id", rs.getObject(1, UUID.class), "key", rs.getString(2),
                "uploadId", rs.getString(3) == null ? "" : rs.getString(3)));
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
        transactions.executeWithoutResult(status -> {
            jdbc.update("UPDATE knowledge_upload_sessions SET status='expired' WHERE document_id=? AND status='pending'", id);
            jdbc.update("UPDATE knowledge_documents SET status='deleted', deleted_at=COALESCE(deleted_at, now()), updated_at=now() WHERE id=? AND status='uploading'", id);
        });
    }

    private void audit(long adminId, String action, UUID documentId) {
        jdbc.update("INSERT INTO admin_audit_log (admin_user_id, action, metadata) VALUES (?, ?, ?::jsonb)",
                adminId, action, "{\"documentId\":\"" + documentId + "\"}");
    }

    private static Instant instantOf(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.time.ZonedDateTime zoned) return zoned.toInstant();
        if (value instanceof java.util.Date date) return date.toInstant();
        return Instant.parse(String.valueOf(value));
    }
}
