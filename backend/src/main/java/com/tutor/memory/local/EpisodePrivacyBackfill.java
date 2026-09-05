package com.tutor.memory.local;

import com.tutor.identity.resume.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 增量保护 V51 之前创建的 Episode；Flyway 无法访问应用加密密钥。 */
@Component
public class EpisodePrivacyBackfill {
    private static final Logger log = LoggerFactory.getLogger(EpisodePrivacyBackfill.class);
    private static final int BATCH = 200;

    private final JdbcTemplate jdbc;
    private final String encKey;
    private final String encKeyId;
    private final String previousEncKey;
    private final String previousEncKeyId;

    public EpisodePrivacyBackfill(JdbcTemplate jdbc,
                                  @Value("${security.resume-enc-key:}") String encKey,
                                  @Value("${security.resume-enc-key-id:v1}") String encKeyId,
                                  @Value("${security.resume-enc-previous-key:}") String previousEncKey,
                                  @Value("${security.resume-enc-previous-key-id:}") String previousEncKeyId) {
        this.jdbc = jdbc;
        this.encKey = encKey == null ? "" : encKey;
        this.encKeyId = encKeyId == null || encKeyId.isBlank() ? "v1" : encKeyId.trim();
        this.previousEncKey = previousEncKey == null ? "" : previousEncKey;
        this.previousEncKeyId = previousEncKeyId == null ? "" : previousEncKeyId.trim();
    }

    @Scheduled(initialDelayString = "${security.episode-backfill-initial-delay-ms:30000}",
            fixedDelayString = "${security.episode-backfill-interval-ms:3600000}")
    public void backfillBatch() {
        if (encKey.isBlank()) return;
        try {
            String sourceSummary = sourceSummaryColumn();
            String predicate = hasPreviousKey()
                    ? "summary_encrypted IS NULL OR summary_encryption_key_id<>?"
                    : "summary_encrypted IS NULL";
            String sql = """
                    SELECT id, %s AS clear_summary FROM episodes
                    WHERE summary IS NOT NULL AND (%s)
                    ORDER BY id LIMIT ?
            """.formatted(sourceSummary, predicate);
            List<Object> args = new ArrayList<>();
            args.add(encKeyId);
            args.add(encKey);
            if (hasPreviousKey()) {
                args.add(previousEncKeyId);
                args.add(previousEncKey);
            }
            if (hasPreviousKey()) args.add(encKeyId);
            args.add(BATCH);
            int updated = jdbc.query(sql, (rs, i) -> new Raw(rs.getLong(1), rs.getString(2)), args.toArray())
                    .stream()
                    .filter(raw -> raw.value() != null && !raw.value().isBlank())
                    .mapToInt(raw -> jdbc.update("""
                            UPDATE episodes
                            SET summary=?, summary_encrypted=pgp_sym_encrypt(?, ?), summary_encryption_key_id=?
                            WHERE id=? AND (summary_encrypted IS NULL OR summary_encryption_key_id<>?)
                            """, PiiMasker.mask(raw.value()).masked(), raw.value(), encKey, encKeyId,
                            raw.id(), encKeyId))
                    .sum();
            if (updated > 0) log.info("episode privacy backfill updated={}", updated);
        } catch (RuntimeException error) {
            // 旧密钥未配置或数据库暂时不可用时保留原记录，避免定时任务覆盖不可解密的密文。
            log.warn("episode privacy backfill deferred: {}", error.getMessage());
        }
    }

    private boolean hasPreviousKey() {
        return !previousEncKey.isBlank() && !previousEncKeyId.isBlank()
                && !previousEncKeyId.equals(encKeyId);
    }

    private String sourceSummaryColumn() {
        if (!hasPreviousKey()) {
            return """
                    CASE
                        WHEN summary_encrypted IS NOT NULL AND summary_encryption_key_id=?
                            THEN pgp_sym_decrypt(summary_encrypted, ?)
                        ELSE summary
                    END
                    """;
        }
        return """
                CASE
                    WHEN summary_encrypted IS NOT NULL AND summary_encryption_key_id=?
                        THEN pgp_sym_decrypt(summary_encrypted, ?)
                    WHEN summary_encrypted IS NOT NULL AND summary_encryption_key_id=?
                        THEN pgp_sym_decrypt(summary_encrypted, ?)
                    ELSE summary
                END
                """;
    }

    private record Raw(long id, String value) {}
}
