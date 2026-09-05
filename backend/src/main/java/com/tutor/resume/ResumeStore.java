package com.tutor.resume;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Persistence boundary for encrypted resume projections and versioned uploads. */
@Repository
public class ResumeStore {
    private final JdbcTemplate jdbc;

    public ResumeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long userId, String rawText, String encryptionKey,
                       String structuredJson, String embedding) {
        Long id = jdbc.queryForObject("""
                INSERT INTO resumes (user_id, raw_encrypted, structured, embedding)
                VALUES (?, pgp_sym_encrypt(?, ?), ?::jsonb, ?::vector) RETURNING id
                """, Long.class, userId, rawText, encryptionKey, structuredJson, embedding);
        if (id == null) throw new IllegalStateException("简历入库未返回 ID");
        return id;
    }

    public void savePiiMapping(long userId, String mappingJson, String encryptionKey) {
        jdbc.update("INSERT INTO pii_mappings (user_id, mapping_encrypted) VALUES (?, pgp_sym_encrypt(?, ?))",
                userId, mappingJson, encryptionKey);
    }

    public Optional<String> latestStructuredJson(long userId) {
        return jdbc.query("""
                SELECT structured::text FROM resumes WHERE user_id=? ORDER BY id DESC LIMIT 1
                """, (rs, i) -> rs.getString(1), userId).stream().findFirst();
    }
}
