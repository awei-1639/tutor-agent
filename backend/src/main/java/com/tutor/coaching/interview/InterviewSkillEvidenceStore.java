package com.tutor.coaching.interview;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Read-only view over {@code interview_skill_evidence} for planning consumers.
 *
 * <p>Evidence writes stay in {@link InterviewCompletionJobStore}; this class exists so other
 * modules (learning plans) can read verified weaknesses without depending on the worker's
 * job-state SQL.
 */
@Repository
public class InterviewSkillEvidenceStore {
    private final JdbcTemplate jdbc;

    public InterviewSkillEvidenceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Most recently verified weak skills (average below the threshold), deduplicated. */
    public List<String> recentWeakSkills(long userId, double maxAverageScore, int limit) {
        return jdbc.query("""
                SELECT skill_id FROM interview_skill_evidence
                WHERE user_id=? AND average_score < ? AND skill_id IS NOT NULL AND skill_id <> ''
                GROUP BY skill_id
                ORDER BY MAX(created_at) DESC
                LIMIT ?
                """, (rs, i) -> rs.getString(1), userId, maxAverageScore, limit);
    }
}
