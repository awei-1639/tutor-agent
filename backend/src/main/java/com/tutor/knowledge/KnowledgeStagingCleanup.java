package com.tutor.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 清理崩溃、取消或终态任务遗留的暂存数据行。 */
@Component
public class KnowledgeStagingCleanup {
    private final JdbcTemplate jdbc;
    public KnowledgeStagingCleanup(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelayString = "${knowledge.ingestion.staging-cleanup-ms:3600000}")
    public void cleanup() {
        jdbc.update("""
                DELETE FROM knowledge_document_chunk_staging s
                WHERE NOT EXISTS (SELECT 1 FROM knowledge_ingestion_jobs j WHERE j.id=s.job_id AND j.status IN ('pending','processing','retryable_failed'))
                   OR EXISTS (SELECT 1 FROM knowledge_ingestion_jobs j WHERE j.id=s.job_id AND j.created_at < now() - interval '24 hours')
                """);
    }
}
