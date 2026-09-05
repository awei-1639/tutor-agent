package com.tutor.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** SQL boundary for administrator-owned knowledge documents. */
final class KnowledgeDocumentAdminStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final KnowledgeIngestionJobStore jobs;

    record PendingUpload(String objectKey, String uploadId) {}

    KnowledgeDocumentAdminStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                                KnowledgeIngestionJobStore jobs) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.jobs = jobs;
    }

    Optional<UUID> findDuplicate(String contentHash) {
        return jdbc.query("""
                SELECT id FROM knowledge_documents
                WHERE content_hash=? AND deleted_at IS NULL AND status <> 'failed'
                ORDER BY created_at DESC LIMIT 1
                """, (rs, rowNum) -> rs.getObject(1, UUID.class), contentHash)
                .stream().findFirst();
    }

    void insertUploaded(UUID id, String title, String filename, String contentType, long sizeBytes,
                        String contentHash, String objectKey, String resourceKind, long adminId) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO knowledge_documents
                    (id, title, original_filename, content_type, size_bytes, content_hash, oss_object_key,
                     resource_kind, status, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'uploaded', ?)
                    """, id, title, filename, contentType, sizeBytes, contentHash, objectKey,
                    resourceKind, adminId);
            jobs.enqueue(id, 0);
        });
    }

    List<Map<String, Object>> list(int limit) {
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
                """, KnowledgeDocumentAdminStore::row, limit);
    }

    Optional<Map<String, Object>> document(UUID id) {
        return jdbc.query("""
                SELECT id, original_filename, oss_object_key, deleted_at
                FROM knowledge_documents WHERE id=?
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getObject("id"));
            row.put("original_filename", rs.getString("original_filename"));
            row.put("oss_object_key", rs.getString("oss_object_key"));
            row.put("deleted_at", rs.getTimestamp("deleted_at"));
            return row;
        }, id).stream().findFirst();
    }

    Optional<Long> generation(UUID id) {
        return jdbc.query("SELECT generation FROM knowledge_documents WHERE id=?",
                (rs, rowNum) -> rs.getLong(1), id).stream().findFirst();
    }

    Optional<PendingUpload> pendingUpload(UUID documentId) {
        return jdbc.query("""
                SELECT object_key, upload_id FROM knowledge_upload_sessions
                WHERE document_id=? AND status='pending'
                """, (rs, rowNum) -> new PendingUpload(rs.getString(1), rs.getString(2)), documentId)
                .stream().findFirst();
    }

    void markRetry(UUID id) {
        jdbc.update("UPDATE knowledge_documents SET status='uploaded', error_message=NULL, updated_at=now() WHERE id=?", id);
    }

    void softDelete(UUID id) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE knowledge_documents
                    SET generation=generation+1, status='deleted', deleted_at=now(), updated_at=now()
                    WHERE id=?
                    """, id);
            jdbc.update("UPDATE knowledge_upload_sessions SET status='expired' WHERE document_id=? AND status='pending'", id);
            jobs.cancelForDocument(id);
            jdbc.update("DELETE FROM knowledge_document_chunks WHERE document_id=?", id);
            jdbc.update("""
                    DELETE FROM knowledge_document_chunk_staging
                    WHERE job_id IN (SELECT id FROM knowledge_ingestion_jobs WHERE document_id=?)
                    """, id);
            jdbc.update("""
                    DELETE FROM knowledge_embedding_cache c
                    WHERE NOT EXISTS (SELECT 1 FROM knowledge_document_chunks k WHERE k.content_hash=c.content_hash)
                      AND NOT EXISTS (SELECT 1 FROM knowledge_document_chunk_staging s WHERE s.content_hash=c.content_hash)
                    """);
        });
    }

    Map<String, Object> activeDocument(UUID id, long generation) {
        return jdbc.queryForMap("""
                SELECT id, original_filename, oss_object_key
                FROM knowledge_documents
                WHERE id=? AND generation=? AND deleted_at IS NULL
                """, id, generation);
    }

    void audit(long adminId, String action, UUID documentId) {
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
