package com.tutor.retrieval.fusion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** PostgreSQL-backed, reviewable entity alias index used before graph expansion. */
@Component
public class EntityAliasStore {
    private final JdbcTemplate jdbc;

    public EntityAliasStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> resolveNodeIds(List<String> aliases, int limit) {
        if (aliases == null || aliases.isEmpty() || limit <= 0) return List.of();
        List<String> normalized = aliases.stream()
                .filter(a -> a != null && !a.isBlank())
                .map(EntityAliasStore::normalize)
                .filter(a -> a.length() >= 2)
                .distinct().limit(16).toList();
        if (normalized.isEmpty()) return List.of();

        String exact = normalized.stream().map(a -> "?").collect(java.util.stream.Collectors.joining(","));
        String fuzzy = normalized.stream().map(a -> "normalized_alias % ?")
                .collect(java.util.stream.Collectors.joining(" OR "));
        String sql = "SELECT node_id FROM kg_entity_aliases WHERE normalized_alias IN (" + exact
                + ") OR (" + fuzzy + ") ORDER BY confidence DESC, node_id LIMIT ?";
        List<Object> args = new ArrayList<>(normalized);
        args.addAll(normalized);
        args.add(limit);
        return new ArrayList<>(new LinkedHashSet<>(jdbc.query(sql,
                (rs, rowNum) -> rs.getString("node_id"), args.toArray())));
    }

    static String normalize(String value) {
        return value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\u0000-\\u001f\\u007f]", " ")
                .replaceAll("[-_]+", " ")
                .replaceAll("\\s+", " ").trim();
    }
}
