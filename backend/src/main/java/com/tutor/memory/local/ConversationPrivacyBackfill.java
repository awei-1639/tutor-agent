package com.tutor.memory.local;

import com.tutor.resume.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 增量保护 V48 之前创建的数据行。Flyway 无法访问应用加密密钥，
 * 因此回填任务在应用启动后以小批量、幂等方式运行。
 */
@Component
public class ConversationPrivacyBackfill {
    private static final Logger log = LoggerFactory.getLogger(ConversationPrivacyBackfill.class);
    private static final int BATCH = 200;

    private final JdbcTemplate jdbc;
    private final String encKey;

    public ConversationPrivacyBackfill(JdbcTemplate jdbc,
                                       @Value("${security.resume-enc-key:}") String encKey) {
        this.jdbc = jdbc;
        this.encKey = encKey == null ? "" : encKey;
    }

    @Scheduled(initialDelayString = "${security.conversation-backfill-initial-delay-ms:30000}",
            fixedDelayString = "${security.conversation-backfill-interval-ms:3600000}")
    public void backfillBatch() {
        if (encKey.isBlank()) return;
        int messages = jdbc.query("""
                SELECT id, content FROM messages
                WHERE content_encrypted IS NULL ORDER BY id LIMIT ?
                """, (rs, i) -> new Raw(rs.getLong(1), rs.getString(2)), BATCH)
                .stream().mapToInt(raw -> jdbc.update("""
                        UPDATE messages SET content=?, content_encrypted=pgp_sym_encrypt(?, ?)
                        WHERE id=? AND content_encrypted IS NULL
                        """, PiiMasker.mask(raw.value()).masked(), raw.value(), encKey, raw.id())).sum();
        int summaries = jdbc.query("""
                SELECT id, summary FROM conversations
                WHERE summary IS NOT NULL AND summary_encrypted IS NULL
                ORDER BY id LIMIT ?
                """, (rs, i) -> new Raw(rs.getLong(1), rs.getString(2)), BATCH)
                .stream().mapToInt(raw -> jdbc.update("""
                        UPDATE conversations SET summary=?, summary_encrypted=pgp_sym_encrypt(?, ?)
                        WHERE id=? AND summary_encrypted IS NULL
                        """, PiiMasker.mask(raw.value()).masked(), raw.value(), encKey, raw.id())).sum();
        if (messages > 0 || summaries > 0) {
            log.info("conversation privacy backfill messages={} summaries={}", messages, summaries);
        }
    }

    private record Raw(long id, String value) {}
}
