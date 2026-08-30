package com.tutor.memory.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 限制语义事实规模：过期清理 + 每用户有效上限；不触碰用户的会话历史。 */
@Component
public class FactRetentionService {
    private static final Logger log = LoggerFactory.getLogger(FactRetentionService.class);

    private final JdbcTemplate jdbc;
    private final int maxActivePerUser;

    public FactRetentionService(JdbcTemplate jdbc,
                                @Value("${memory.facts.max-active-per-user:60}") int maxActivePerUser) {
        this.jdbc = jdbc;
        this.maxActivePerUser = Math.max(1, maxActivePerUser);
    }

    @Scheduled(cron = "${memory.facts.retention-cron:0 40 3 * * *}")
    public void prune() {
        try {
            int expired = jdbc.update("DELETE FROM user_facts WHERE expires_at IS NOT NULL AND expires_at <= now()");
            int overflow = jdbc.update("""
                    WITH ranked AS (
                        SELECT id, row_number() OVER (PARTITION BY user_id ORDER BY created_at DESC, id DESC) AS rn
                        FROM user_facts
                        WHERE status='active' AND (expires_at IS NULL OR expires_at > now())
                    )
                    DELETE FROM user_facts f USING ranked r
                    WHERE f.id=r.id AND r.rn > ?
                    """, maxActivePerUser);
            if (expired + overflow > 0) {
                log.info("fact retention pruned expired={} overflow={}", expired, overflow);
            }
        } catch (RuntimeException error) {
            // 保留清理属于尽力而为，绝不能影响聊天轮次。
            log.warn("fact retention failed: {}", error.getMessage());
        }
    }
}
