package com.tutor.push;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** SQL boundary for job release, candidate selection, and push-task idempotency. */
@Repository
final class PushJobStore {
    private final JdbcTemplate jdbc;

    record Candidate(long id, String nodeId, String title, String company, String city,
                     String salary, List<String> requires) {}

    PushJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Long> userIds() {
        return jdbc.queryForList("SELECT id FROM users ORDER BY id", Long.class);
    }

    int releaseAvailableJobs(int batchSize) {
        return jdbc.update("""
                UPDATE jobs SET released = TRUE, fetched_at = now()
                WHERE id IN (SELECT id FROM jobs WHERE NOT released ORDER BY id LIMIT ?)
                """, batchSize);
    }

    List<Candidate> availableCandidates(long userId) {
        return jdbc.query("""
                SELECT id, node_id, title, company, city, salary, requires_raw FROM jobs
                WHERE released AND id NOT IN (SELECT job_id FROM push_tasks WHERE user_id = ? AND job_id IS NOT NULL)
                """, (rs, i) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6),
                List.of((String[]) rs.getArray(7).getArray())), userId);
    }

    Optional<String> latestResumeEmbedding(long userId) {
        return jdbc.query("""
                SELECT embedding::text FROM resumes WHERE user_id=? ORDER BY id DESC LIMIT 1
                """, (rs, i) -> rs.getString(1), userId).stream().findFirst();
    }

    Double similarity(String resumeEmbedding, long jobId) {
        return jdbc.queryForObject("SELECT 1 - (embedding <=> ?::vector) FROM jobs WHERE id=?",
                Double.class, resumeEmbedding, jobId);
    }

    boolean claimPush(long userId, long jobId) {
        return jdbc.update("""
                INSERT INTO push_tasks (user_id, job_id, status) VALUES (?,?,'sent')
                ON CONFLICT (user_id, job_id) WHERE job_id IS NOT NULL DO NOTHING
                """, userId, jobId) == 1;
    }

    void recordFailure(long userId, long jobId, String error) {
        jdbc.update("INSERT INTO push_tasks (user_id, job_id, status, retry_count, error) VALUES (?,?,'failed',0,?)",
                userId, jobId, error);
    }
}
