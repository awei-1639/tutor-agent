package com.tutor.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 将已围栏暂存的文档切片原子发布为正式检索数据。 */
final class KnowledgeChunkPublicationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final KnowledgeIngestionJobStore jobs;

    KnowledgeChunkPublicationService(JdbcTemplate jdbc, TransactionTemplate transactions, KnowledgeIngestionJobStore jobs) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.jobs = jobs;
    }

    void publish(KnowledgeIngestionJobStore.Job job, int chunkCount, boolean partial, String truncationReason) {
        transactions.executeWithoutResult(status -> {
            if (!jobs.fence(job)) throw new IllegalStateException("摄取任务租约已失效，放弃提交结果");
            int active = jdbc.update("UPDATE knowledge_documents SET updated_at=now() WHERE id=? AND generation=? AND deleted_at IS NULL",
                    job.documentId(), job.documentGeneration());
            if (active == 0) throw new IllegalStateException("文档已删除或已有更新，取消发布");
            jdbc.update("DELETE FROM knowledge_document_chunks WHERE document_id=?", job.documentId());
            jdbc.update("""
                    INSERT INTO knowledge_document_chunks
                    (document_id, chunk_index, chunk_text, content_hash, embedding, section_path, block_type, metadata, page_from, page_to)
                    SELECT ?, chunk_index, chunk_text, content_hash, embedding, section_path, block_type, metadata, page_from, page_to
                    FROM knowledge_document_chunk_staging WHERE job_id=? ORDER BY chunk_index
                    """, job.documentId(), job.id());
            jdbc.update("""
                    UPDATE knowledge_documents
                    SET status='indexed', indexed_generation=?, chunk_count=?, error_message=NULL,
                        partial_indexed=?, truncation_reason=?, updated_at=now()
                    WHERE id=? AND generation=? AND deleted_at IS NULL
                    """, job.documentGeneration(), chunkCount, partial, truncationReason, job.documentId(), job.documentGeneration());
            jdbc.update("DELETE FROM knowledge_document_chunk_staging WHERE job_id=?", job.id());
        });
    }
}
