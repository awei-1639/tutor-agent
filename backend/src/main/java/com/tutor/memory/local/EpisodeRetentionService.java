package com.tutor.memory.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounds derived cross-session memory without deleting a user's conversation history. */
@Component
public class EpisodeRetentionService {
    private static final Logger log = LoggerFactory.getLogger(EpisodeRetentionService.class);

    private final JdbcTemplate jdbc;
    private final int maxActiveEpisodesPerUser;

    public EpisodeRetentionService(JdbcTemplate jdbc,
                                   @Value("${memory.episode.max-active-per-user:200}") int maxActiveEpisodesPerUser) {
        this.jdbc = jdbc;
        this.maxActiveEpisodesPerUser = Math.max(1, maxActiveEpisodesPerUser);
    }

    @Scheduled(cron = "${memory.episode.retention-cron:0 30 3 * * *}")
    public void prune() {
        try {
            int expired = jdbc.update("DELETE FROM episodes WHERE expires_at IS NOT NULL AND expires_at <= now()");
            int overflow = jdbc.update("""
                    WITH ranked AS (
                        SELECT id, row_number() OVER (PARTITION BY user_id ORDER BY created_at DESC, id DESC) AS rn
                        FROM episodes
                        WHERE status='active' AND (expires_at IS NULL OR expires_at > now())
                    )
                    DELETE FROM episodes e USING ranked r
                    WHERE e.id=r.id AND r.rn > ?
                    """, maxActiveEpisodesPerUser);
            if (expired + overflow > 0) {
                log.info("episode retention pruned expired={} overflow={}", expired, overflow);
            }
        } catch (RuntimeException error) {
            // Retention is best effort; it must never affect a chat turn.
            log.warn("episode retention failed: {}", error.getMessage());
        }
    }
}
