package com.tutor.profile;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** SQL boundary for skill-name alignment cache reads and writes. */
@Repository
public class SkillAlignmentStore {
    private final JdbcTemplate jdbc;

    public SkillAlignmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> findCached(List<String> normalizedNames) {
        if (normalizedNames == null || normalizedNames.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(normalizedNames.size(), "?"));
        Map<String, String> cached = new HashMap<>();
        jdbc.query("SELECT raw_name, node_id FROM skill_alignments WHERE raw_name IN (" + placeholders + ")",
                (rs, rowNum) -> new String[]{rs.getString(1), rs.getString(2)}, normalizedNames.toArray())
                .forEach(row -> cached.put(row[0], row[1]));
        return cached;
    }

    public void saveAll(List<String> names, Map<String, String> nodeIds) {
        if (names == null || names.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO skill_alignments (raw_name, node_id, method, score) VALUES (?,?,?,1.0)
                ON CONFLICT (raw_name) DO UPDATE SET node_id=EXCLUDED.node_id, method=EXCLUDED.method
                """, names, names.size(), (ps, name) -> {
            String nodeId = nodeIds.get(name);
            ps.setString(1, name);
            ps.setString(2, nodeId);
            ps.setString(3, nodeId != null ? "exact_or_alias" : "miss");
        });
    }
}
