package com.tutor.identity.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Owns profile persistence SQL, JSON mapping, generation fencing, and event rows. */
@Repository
public class ProfileStore {
    private static final Logger log = LoggerFactory.getLogger(ProfileStore.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProfileStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Map<String, Object> snapshot(long userId) {
        List<String> rows = jdbc.query("SELECT data::text FROM profiles WHERE user_id=?",
                (rs, i) -> rs.getString(1), userId);
        if (rows.isEmpty()) return Map.of();
        try {
            return mapper.readValue(rows.getFirst(), new TypeReference<>() {});
        } catch (Exception error) {
            log.error("画像反序列化失败 user={}", userId, error);
            return Map.of();
        }
    }

    void save(long userId, Map<String, Object> data) {
        try {
            String json = mapper.writeValueAsString(data);
            jdbc.update("""
                    INSERT INTO profiles (user_id, data, updated_at) VALUES (?, ?::jsonb, now())
                    ON CONFLICT (user_id) DO UPDATE SET data=?::jsonb, updated_at=now()
                    """, userId, json, json);
        } catch (Exception error) {
            log.error("画像保存失败 user={}", userId, error);
        }
    }

    boolean saveIfGeneration(long userId, long generation, Map<String, Object> data) {
        try {
            String json = mapper.writeValueAsString(data);
            return jdbc.update("""
                    INSERT INTO profiles (user_id, data, updated_at)
                    SELECT ?, ?::jsonb, now()
                    WHERE EXISTS (SELECT 1 FROM users WHERE id=? AND memory_generation=?)
                    ON CONFLICT (user_id) DO UPDATE SET data=EXCLUDED.data, updated_at=now()
                    """, userId, json, userId, generation) > 0;
        } catch (Exception error) {
            log.error("画像保存失败 user={}", userId, error);
            return false;
        }
    }

    void insertEvent(long userId, String eventJson, String trigger, String traceId) {
        jdbc.update("INSERT INTO profile_events (user_id, delta, trigger, trace_id) VALUES (?, ?::jsonb, ?, ?)",
                userId, eventJson, trigger, traceId);
    }

    void insertEvent(long userId, String eventJson, String trigger) {
        jdbc.update("INSERT INTO profile_events (user_id, delta, trigger) VALUES (?, ?::jsonb, ?)",
                userId, eventJson, trigger);
    }

    boolean generationCurrent(long userId, long expectedGeneration) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id=? AND memory_generation=?",
                Integer.class, userId, expectedGeneration);
        return count != null && count > 0;
    }

    void deleteByUser(long userId) {
        jdbc.update("DELETE FROM profile_events WHERE user_id=?", userId);
        jdbc.update("DELETE FROM profiles WHERE user_id=?", userId);
    }

    List<ProfileEventRow> recentEvents(long userId, int limit) {
        return jdbc.query("""
                SELECT id, delta::text, trigger, created_at, trace_id
                FROM profile_events WHERE user_id=?
                ORDER BY id DESC LIMIT ?
                """, (rs, i) -> new ProfileEventRow(
                rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toInstant(), rs.getString(5)), userId, limit);
    }

    List<Long> userIds() {
        return jdbc.query("SELECT user_id FROM profiles", (rs, i) -> rs.getLong(1));
    }

    record ProfileEventRow(long id, String deltaJson, String trigger,
                           Instant createdAt, String traceId) {
    }
}
