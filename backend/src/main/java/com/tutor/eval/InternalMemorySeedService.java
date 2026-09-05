package com.tutor.eval;

import com.tutor.llm.EmbeddingGateway;
import com.tutor.conversation.memory.local.EpisodeStore;
import com.tutor.conversation.memory.local.FactStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** Rebuilds deterministic memory fixtures for the internal evaluation endpoint. */
@Service
public class InternalMemorySeedService {
    private final JdbcTemplate jdbc;
    private final EmbeddingGateway embeddingGateway;
    private final EpisodeStore episodes;
    private final FactStore facts;

    public InternalMemorySeedService(JdbcTemplate jdbc, EmbeddingGateway embeddingGateway,
                                     EpisodeStore episodes, FactStore facts) {
        this.jdbc = jdbc;
        this.embeddingGateway = embeddingGateway;
        this.episodes = episodes;
        this.facts = facts;
    }

    public record SeedEpisode(String summary, List<String> topics, Integer ageDays) {}
    public record SeedFact(String text, String category, Double confidence, String status) {}
    public record Result(long userId, int episodes, int facts) {}

    public Result seed(long userId, List<SeedEpisode> episodeSeeds,
                       List<SeedFact> factSeeds, String traceId) {
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        // 幂等重建：清理派生记忆，但不修改 messages/conversations。
        jdbc.update("DELETE FROM user_facts WHERE user_id=?", userId);
        jdbc.update("DELETE FROM episode_memory_tombstones WHERE user_id=?", userId);
        jdbc.update("DELETE FROM episodes WHERE user_id=?", userId);
        long conversationId = jdbc.queryForObject(
                "INSERT INTO conversations (user_id, last_active_at) VALUES (?, now()) RETURNING id",
                Long.class, userId);

        int episodeCount = seedEpisodes(userId, conversationId, episodeSeeds, traceId);
        int factCount = seedFacts(userId, factSeeds);
        return new Result(userId, episodeCount, factCount);
    }

    private int seedEpisodes(long userId, long conversationId, List<SeedEpisode> seeds, String traceId) {
        if (seeds == null) return 0;
        int count = 0;
        int index = 0;
        for (SeedEpisode seed : seeds) {
            if (seed == null || seed.summary() == null || seed.summary().isBlank()) continue;
            float[] embedding = embeddingGateway.embed(seed.summary(), traceId);
            // 每条种子 Episode 使用互异的负数源窗口，避免命中部分唯一索引。
            long window = -1000L - index;
            long id = episodes.insertIfAbsentReturningId(userId, conversationId, seed.summary(),
                    seed.topics() == null ? List.of() : seed.topics(), List.of(), embedding,
                    window, window, 0L);
            index++;
            if (id == 0) continue;
            int ageDays = seed.ageDays() == null ? 0 : Math.max(0, seed.ageDays());
            jdbc.update("UPDATE episodes SET created_at = now() - (? * interval '1 day') WHERE id=?", ageDays, id);
            count++;
        }
        return count;
    }

    private int seedFacts(long userId, List<SeedFact> seeds) {
        if (seeds == null) return 0;
        int count = 0;
        for (SeedFact seed : seeds) {
            if (seed == null || seed.text() == null || seed.text().isBlank()) continue;
            long id = facts.insertIfAbsentReturningId(userId, null, 0L, seed.text(), seed.category(),
                    seed.confidence() == null ? 0.7D : seed.confidence());
            if (id == 0) continue;
            if ("superseded".equalsIgnoreCase(seed.status())) {
                jdbc.update("UPDATE user_facts SET status='superseded' WHERE id=?", id);
            }
            count++;
        }
        return count;
    }
}
