package com.tutor.memory.local;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** Commits a prepared episode atomically after the expensive LLM work is done. */
@Component
public class EpisodeCommitter {
    private final JdbcTemplate jdbc;
    private final EpisodeStore episodes;
    private final TransactionTemplate transactions;

    public EpisodeCommitter(JdbcTemplate jdbc, EpisodeStore episodes, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.episodes = episodes;
        this.transactions = transactions;
    }

    public boolean commit(long userId, long conversationId, long expectedWatermark,
                          long firstMessageId, long lastMessageId,
                          String summary, List<String> topics, List<String> openItems, float[] embedding) {
        Boolean committed = transactions.execute(status -> {
            Long watermark = jdbc.queryForObject(
                    "SELECT episode_upto_msg_id FROM conversations WHERE id=? FOR UPDATE", Long.class, conversationId);
            Long generation = jdbc.queryForObject("SELECT memory_generation FROM users WHERE id=?", Long.class, userId);
            if (watermark == null || generation == null || watermark != expectedWatermark) return false;
            int inserted = episodes.insertIfAbsent(userId, conversationId, summary, topics, openItems, embedding,
                    firstMessageId, lastMessageId, generation);
            if (inserted == 0) return false;
            jdbc.update("UPDATE conversations SET episode_upto_msg_id=? WHERE id=?", lastMessageId, conversationId);
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }
}
