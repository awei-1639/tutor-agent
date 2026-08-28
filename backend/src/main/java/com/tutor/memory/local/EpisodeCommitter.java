package com.tutor.memory.local;

import com.tutor.memory.external.MemorySyncOutbox;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** 在昂贵的 LLM 工作完成后，原子提交已准备好的 Episode。 */
@Component
public class EpisodeCommitter {
    private final JdbcTemplate jdbc;
    private final EpisodeStore episodes;
    private final TransactionTemplate transactions;
    private final MemorySyncOutbox outbox;

    public EpisodeCommitter(JdbcTemplate jdbc, EpisodeStore episodes, TransactionTemplate transactions) {
        this(jdbc, episodes, transactions, null);
    }

    @Autowired
    public EpisodeCommitter(JdbcTemplate jdbc, EpisodeStore episodes, TransactionTemplate transactions,
                            MemorySyncOutbox outbox) {
        this.jdbc = jdbc;
        this.episodes = episodes;
        this.transactions = transactions;
        this.outbox = outbox;
    }

    public boolean commit(long userId, long conversationId, long expectedWatermark,
                          long firstMessageId, long lastMessageId,
                          String summary, List<String> topics, List<String> openItems, float[] embedding) {
        return commit(userId, conversationId, expectedWatermark, firstMessageId, lastMessageId,
                summary, topics, openItems, embedding, Long.MIN_VALUE);
    }

    /**
     * 围栏式提交：仅当用户记忆代际自任务入队后未变化时，任务才能发布。
     * 这是“删除先于写入”保障的另一半；仅检查当前代际会允许旧任务在删除后
     * 使用新代际发布。
     */
    public boolean commit(long userId, long conversationId, long expectedWatermark,
                          long firstMessageId, long lastMessageId,
                          String summary, List<String> topics, List<String> openItems,
                          float[] embedding, long expectedGeneration) {
        Boolean committed = transactions.execute(status -> {
            Long watermark = jdbc.queryForObject(
                    "SELECT episode_upto_msg_id FROM conversations WHERE id=? FOR UPDATE", Long.class, conversationId);
            var user = jdbc.query("SELECT memory_generation FROM users WHERE id=? FOR UPDATE",
                    (rs, i) -> new UserState(rs.getLong(1)), userId);
            if (watermark == null || user.isEmpty() || watermark != expectedWatermark) return false;
            long generation = user.getFirst().generation();
            if (expectedGeneration != Long.MIN_VALUE && generation != expectedGeneration) return false;
            long episodeId = episodes.insertIfAbsentReturningId(userId, conversationId, summary, topics, openItems, embedding,
                    firstMessageId, lastMessageId, generation);
            if (episodeId == 0) return false;
            jdbc.update("UPDATE conversations SET episode_upto_msg_id=? WHERE id=?", lastMessageId, conversationId);
            if (outbox != null) {
                outbox.enqueueUpsertEpisode(userId, generation, episodeId, summary, topics, openItems);
            }
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }

    private record UserState(long generation) {}
}
