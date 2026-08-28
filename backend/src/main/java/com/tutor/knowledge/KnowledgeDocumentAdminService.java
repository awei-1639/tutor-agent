package com.tutor.knowledge;

import com.tutor.config.KnowledgeIngestionProperties;
import com.tutor.config.KnowledgeUploadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 管理员文档的上传、查询、重试和软删除。 */
final class KnowledgeDocumentAdminService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentAdminService.class);

    private final JdbcTemplate jdbc;
    private final OssStorage oss;
    private final KnowledgeIngestionJobStore jobs;
    private final TransactionTemplate transactions;
    private final KnowledgeUploadRateLimiter uploadLimiter;
    private final KnowledgeOssCleanupStore ossCleanup;
    private final KnowledgeUploadProperties uploadProperties;
    private final KnowledgeIngestionProperties ingestionProperties;
    private final KnowledgeUploadSessionService uploadSessions;

    KnowledgeDocumentAdminService(JdbcTemplate jdbc, OssStorage oss, KnowledgeIngestionJobStore jobs,
                                   TransactionTemplate transactions, KnowledgeUploadRateLimiter uploadLimiter,
                                   KnowledgeOssCleanupStore ossCleanup,
                                   KnowledgeUploadProperties uploadProperties,
                                   KnowledgeIngestionProperties ingestionProperties,
                                   KnowledgeUploadSessionService uploadSessions) {
        this.jdbc = jdbc;
        this.oss = oss;
        this.jobs = jobs;
        this.transactions = transactions;
        this.uploadLimiter = uploadLimiter;
        this.ossCleanup = ossCleanup;
        this.uploadProperties = uploadProperties;
        this.ingestionProperties = ingestionProperties;
        this.uploadSessions = uploadSessions;
    }

    KnowledgeDocumentService.UploadResult upload(long adminId, MultipartFile file, String requestedTitle) {
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
        List<UUID> duplicated = jdbc.query("""
                SELECT id FROM knowledge_documents
                WHERE content_hash=? AND deleted_at IS NULL AND status <> 'failed'
                ORDER BY created_at DESC LIMIT 1
        """, (rs, rowNum) -> rs.getObject(1, UUID.class), hash);
        if (!duplicated.isEmpty()) {
            return new KnowledgeDocumentService.UploadResult(duplicated.getFirst().toString(), "deduplicated", true);
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
            transactions.executeWithoutResult(status -> {
                jdbc.update("""
                        INSERT INTO knowledge_documents
                        (id, title, original_filename, content_type, size_bytes, content_hash, oss_object_key, status, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'uploaded', ?)
                        """, id, title, filename, file.getContentType(), bytes.length, hash, objectKey, adminId);
                jobs.enqueue(id, 0);
            });
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
        audit(adminId, "KNOWLEDGE_DOCUMENT_UPLOADED", id);
        return new KnowledgeDocumentService.UploadResult(id.toString(), "uploaded", false);
    }

    List<Map<String, Object>> list(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return jdbc.query("""
                SELECT d.id, d.title, d.original_filename, d.content_type, d.size_bytes, d.status,
                       d.error_message, d.chunk_count, d.created_at, d.updated_at, d.deleted_at, u.name AS creator_name,
                       d.partial_indexed, d.truncation_reason,
                       j.status AS job_status, j.stage AS job_stage, j.attempts AS job_attempts, j.error_message AS job_error
                FROM knowledge_documents d JOIN users u ON u.id=d.created_by
                LEFT JOIN LATERAL (
                  SELECT status, stage, attempts, error_message FROM knowledge_ingestion_jobs
                  WHERE document_id=d.id ORDER BY created_at DESC LIMIT 1
                ) j ON TRUE
                ORDER BY d.created_at DESC LIMIT ?
                """, KnowledgeDocumentAdminService::row, safeLimit);
    }

    void retry(long adminId, UUID id) {
        Map<String, Object> document = document(id);
        if (document.get("deleted_at") != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已删除文档不能重试");
        }
        jdbc.update("UPDATE knowledge_documents SET status='uploaded', error_message=NULL, updated_at=now() WHERE id=?", id);
        audit(adminId, "KNOWLEDGE_DOCUMENT_RETRIED", id);
        jobs.enqueue(id, generation(id));
    }

    void softDelete(long adminId, UUID id) {
        Map<String, Object> document = document(id);
        if (document.get("deleted_at") != null) return;
        Map<String, Object> uploadSession = jdbc.query("""
                SELECT object_key, upload_id FROM knowledge_upload_sessions
                WHERE document_id=? AND status='pending'
                """, ps -> ps.setObject(1, id), rs -> rs.next()
                ? Map.of("key", rs.getString("object_key"),
                "uploadId", rs.getString("upload_id") == null ? "" : rs.getString("upload_id"))
                : null);
        transactions.executeWithoutResult(status -> {
            jdbc.update("UPDATE knowledge_documents SET generation=generation+1, status='deleted', deleted_at=now(), updated_at=now() WHERE id=?", id);
            jdbc.update("UPDATE knowledge_upload_sessions SET status='expired' WHERE document_id=? AND status='pending'", id);
            jobs.cancelForDocument(id);
            jdbc.update("DELETE FROM knowledge_document_chunks WHERE document_id=?", id);
            jdbc.update("DELETE FROM knowledge_document_chunk_staging WHERE job_id IN (SELECT id FROM knowledge_ingestion_jobs WHERE document_id=?)", id);
            jdbc.update("DELETE FROM knowledge_embedding_cache c WHERE NOT EXISTS (SELECT 1 FROM knowledge_document_chunks k WHERE k.content_hash=c.content_hash) AND NOT EXISTS (SELECT 1 FROM knowledge_document_chunk_staging s WHERE s.content_hash=c.content_hash)");
        });
        String objectKey = (String) document.get("oss_object_key");
        String pendingUploadId = uploadSession == null ? null : (String) uploadSession.get("uploadId");
        if (!uploadSessions.cleanupUploadArtifact(objectKey, pendingUploadId)) {
            if (pendingUploadId != null && !pendingUploadId.isBlank()) {
                log.error("multipart upload abort will be retried by OSS lifecycle key={}", objectKey);
            } else {
                log.error("OSS document cleanup queued key={}", objectKey);
            }
        }
        audit(adminId, "KNOWLEDGE_DOCUMENT_SOFT_DELETED", id);
    }

    Map<String, Object> activeDocument(UUID id, long generation) {
        try {
            return jdbc.queryForMap("SELECT id, original_filename, oss_object_key FROM knowledge_documents WHERE id=? AND generation=? AND deleted_at IS NULL", id, generation);
        } catch (Exception e) {
            throw new IllegalStateException("文档已删除或已有更新");
        }
    }

    private Map<String, Object> document(UUID id) {
        try {
            return jdbc.queryForMap("SELECT id, original_filename, oss_object_key, deleted_at FROM knowledge_documents WHERE id=?", id);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
    }

    private long generation(UUID id) {
        Long generation = jdbc.queryForObject("SELECT generation FROM knowledge_documents WHERE id=?", Long.class, id);
        if (generation == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        return generation;
    }

    private void audit(long adminId, String action, UUID documentId) {
        jdbc.update("INSERT INTO admin_audit_log (admin_user_id, action, metadata) VALUES (?, ?, ?::jsonb)",
                adminId, action, "{\"documentId\":\"" + documentId + "\"}");
    }

    private static Map<String, Object> row(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getObject("id").toString());
        row.put("title", rs.getString("title"));
        row.put("filename", rs.getString("original_filename"));
        row.put("contentType", rs.getString("content_type"));
        row.put("sizeBytes", rs.getLong("size_bytes"));
        row.put("status", rs.getString("status"));
        row.put("error", rs.getString("error_message"));
        row.put("chunkCount", rs.getInt("chunk_count"));
        row.put("creatorName", rs.getString("creator_name"));
        row.put("createdAt", String.valueOf(rs.getObject("created_at")));
        row.put("updatedAt", String.valueOf(rs.getObject("updated_at")));
        row.put("deletedAt", rs.getObject("deleted_at") == null ? null : String.valueOf(rs.getObject("deleted_at")));
        row.put("jobStatus", rs.getString("job_status"));
        row.put("jobStage", rs.getString("job_stage"));
        row.put("jobAttempts", rs.getObject("job_attempts") == null ? 0 : rs.getInt("job_attempts"));
        row.put("jobError", rs.getString("job_error"));
        row.put("partialIndexed", rs.getBoolean("partial_indexed"));
        row.put("truncationReason", rs.getString("truncation_reason"));
        return row;
    }
}
