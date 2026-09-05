package com.tutor.memory.local;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Public semantic-fact facade. Persistence, encryption, and SQL live in
 * {@link FactPersistenceStore}; this class keeps the stable application API
 * and pure fact rules used by callers and tests.
 */
@Component
public class FactStore {
    public static final Set<String> CATEGORIES = FactPolicy.CATEGORIES;

    private final FactPersistenceStore persistence;

    public FactStore(JdbcTemplate jdbc) {
        this(jdbc, "", "v1", "", "");
    }

    @Autowired
    public FactStore(JdbcTemplate jdbc,
                     @Value("${security.resume-enc-key:}") String encKey,
                     @Value("${security.resume-enc-key-id:v1}") String encKeyId,
                     @Value("${security.resume-enc-previous-key:}") String previousEncKey,
                     @Value("${security.resume-enc-previous-key-id:}") String previousEncKeyId) {
        this.persistence = new FactPersistenceStore(jdbc, encKey, encKeyId, previousEncKey, previousEncKeyId);
    }

    public record UserFact(long id, String factText, String category,
                           double confidence, Instant createdAt, Instant updatedAt) {}

    public long insertIfAbsentReturningId(long userId, Long sourceEpisodeId, long memoryGeneration,
                                          String factText, String category, double confidence) {
        return persistence.insertIfAbsentReturningId(userId, sourceEpisodeId, memoryGeneration,
                factText, category, confidence);
    }

    public List<UserFact> activeByUser(long userId, int limit) {
        return persistence.activeByUser(userId, limit);
    }

    public List<UserFact> activeByUserAndCategory(long userId, String category, int limit) {
        return persistence.activeByUserAndCategory(userId, category, limit);
    }

    public boolean markSuperseded(long userId, long factId, long supersededById, long expectedGeneration) {
        return persistence.markSuperseded(userId, factId, supersededById, expectedGeneration);
    }

    public void deleteByUser(long userId) {
        persistence.deleteByUser(userId);
    }

    public boolean deleteByIdForUser(long id, long userId) {
        return persistence.deleteByIdForUser(id, userId);
    }

    public int deleteBySourceEpisodeId(long userId, long sourceEpisodeId) {
        return persistence.deleteBySourceEpisodeId(userId, sourceEpisodeId);
    }

    public static String normalizeCategory(String category) {
        return FactPolicy.normalizeCategory(category);
    }

    public static String hashOf(String factText) {
        return FactPolicy.hashOf(factText);
    }
}
