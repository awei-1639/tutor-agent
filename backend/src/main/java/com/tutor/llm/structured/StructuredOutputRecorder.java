package com.tutor.llm.structured;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.resume.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** 尽力记录结构化输出；失败不能阻塞主链路。 */
@Component
public class StructuredOutputRecorder {
    private static final Logger log = LoggerFactory.getLogger(StructuredOutputRecorder.class);
    private static final int MAX_RAW_CHARS = 12000;
    private final JdbcTemplate jdbc;
    private final String encryptionKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public StructuredOutputRecorder(
            JdbcTemplate jdbc,
            @Value("${security.resume-enc-key:}") String encryptionKey
    ) {
        this.jdbc = jdbc;
        this.encryptionKey = encryptionKey == null ? "" : encryptionKey;
    }

    public void record(
            String traceId,
            StructuredTask task,
            String schemaId,
            int attempt,
            String rawOutput,
            String status,
            List<StructuredOutputError> errors
    ) {
        if (rawOutput == null) return;
        try {
            String bounded = rawOutput.length() <= MAX_RAW_CHARS
                    ? rawOutput : rawOutput.substring(0, MAX_RAW_CHARS) + "…";
            String masked = PiiMasker.mask(bounded).masked();
            String hash = sha256(bounded);
            String errorsJson = mapper.writeValueAsString(errors == null ? List.of() : errors);
            if (encryptionKey.isBlank()) {
                jdbc.update("""
                        INSERT INTO structured_output_events
                            (trace_id, task, schema_id, attempt, raw_output_masked,
                             raw_output_sha256, validation_status, validation_errors)
                        VALUES (?,?,?,?,?,?,?,?::jsonb)
                        """, traceId, task.name().toLowerCase(), schemaId, attempt,
                        masked, hash, status, errorsJson);
            } else {
                jdbc.update("""
                        INSERT INTO structured_output_events
                            (trace_id, task, schema_id, attempt, raw_output_encrypted,
                             raw_output_masked, raw_output_sha256, validation_status, validation_errors)
                        VALUES (?,?,?, ?, pgp_sym_encrypt(?,?), ?, ?, ?, ?::jsonb)
                        """, traceId, task.name().toLowerCase(), schemaId, attempt,
                        bounded, encryptionKey, masked, hash, status, errorsJson);
            }
        } catch (Exception error) {
            log.warn("structured output record failed task={} trace={} type={}",
                    task, traceId, error.getClass().getSimpleName());
        }
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
