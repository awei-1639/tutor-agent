package com.tutor.knowledge.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns upload-session SQL, transaction boundaries, and administrative audit rows. */
final class KnowledgeUploadSessionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    KnowledgeUploadSessionStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    void create(UUID documentId, long adminId, String title, String filename, String contentType,
                long sizeBytes, String contentHash, String objectKey, String resourceKind,
                Instant expiresAt, String uploadId, long partSize) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO knowledge_documents
                    (id, title, original_filename, content_type, size_bytes, content_hash, oss_object_key,
                     resource_kind, status, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'uploading', ?)
                    """, documentId, title, filename, contentType, sizeBytes, contentHash,
                    objectKey, resourceKind, adminId);
            jdbc.update("""
                    INSERT INTO knowledge_upload_sessions
                    (document_id, admin_user_id, object_key, expected_size, expires_at, upload_id, part_size)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, documentId, adminId, objectKey, sizeBytes, expiresAt, uploadId,
                    partSize == 0 ? null : partSize);
        });
    }

    Map<String, Object> findForAdmin(UUID documentId, long adminId) {
        return jdbc.queryForMap("""
                SELECT s.object_key, s.expected_size, s.status, s.expires_at, s.upload_id, s.part_size
                FROM knowledge_upload_sessions s JOIN knowledge_documents d ON d.id=s.document_id
                WHERE s.document_id=? AND s.admin_user_id=? AND d.deleted_at IS NULL
                """, documentId, adminId);
    }

    int renew(UUID documentId, long adminId, Instant expiresAt) {
        return jdbc.update("""
                UPDATE knowledge_upload_sessions SET expires_at=?
                WHERE document_id=? AND admin_user_id=? AND status='pending' AND expires_at > now()
                """, expiresAt, documentId, adminId);
    }

    String contentType(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT content_type FROM knowledge_documents WHERE id=?", String.class, documentId);
    }

    void complete(UUID documentId, long adminId, Runnable enqueueIngestion) {
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.update("""
                    UPDATE knowledge_upload_sessions SET status='completed', completed_at=now()
                    WHERE document_id=? AND admin_user_id=? AND status='pending' AND expires_at > now()
                    """, documentId, adminId);
            if (updated != 1) throw new IllegalStateException("涓婁紶浼氳瘽鐘舵€佸凡鍙樺寲");
            jdbc.update("UPDATE knowledge_documents SET status='uploaded', error_message=NULL, updated_at=now() WHERE id=? AND status='uploading'", documentId);
            enqueueIngestion.run();
        });
    }

    List<Map<String, Object>> expiredPending() {
        return jdbc.query("""
                SELECT document_id, object_key, upload_id FROM knowledge_upload_sessions
                WHERE status='pending' AND expires_at < now()
                ORDER BY expires_at LIMIT 50
                """, (rs, rowNum) -> Map.of(
                "id", rs.getObject(1, UUID.class),
                "key", rs.getString(2),
                "uploadId", rs.getString(3) == null ? "" : rs.getString(3)));
    }

    void expire(UUID documentId) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("UPDATE knowledge_upload_sessions SET status='expired' WHERE document_id=? AND status='pending'", documentId);
            jdbc.update("UPDATE knowledge_documents SET status='deleted', deleted_at=COALESCE(deleted_at, now()), updated_at=now() WHERE id=? AND status='uploading'", documentId);
        });
    }

    void audit(long adminId, String action, UUID documentId) {
        jdbc.update("INSERT INTO admin_audit_log (admin_user_id, action, metadata) VALUES (?, ?, ?::jsonb)",
                adminId, action, "{\"documentId\":\"" + documentId + "\"}");
    }
}
