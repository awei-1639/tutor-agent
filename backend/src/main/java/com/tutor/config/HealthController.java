package com.tutor.config;

import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 容器探针：healthz 仅检查进程，readyz 检查必要存储依赖。 */
@RestController
public class HealthController {
    private static final Logger log = LoggerFactory.getLogger(HealthController.class);
    private final JdbcTemplate jdbc;
    private final Driver neo4j;

    public HealthController(JdbcTemplate jdbc, Driver neo4j) {
        this.jdbc = jdbc;
        this.neo4j = neo4j;
    }

    @GetMapping("/healthz")
    public Map<String, String> live() {
        return Map.of("status", "up");
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> ready() {
        List<String> unavailable = new ArrayList<>();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (RuntimeException e) {
            log.warn("readiness: PostgreSQL unavailable: {}", e.getMessage());
            unavailable.add("postgres");
        }
        try {
            neo4j.verifyConnectivity();
        } catch (RuntimeException e) {
            log.warn("readiness: Neo4j unavailable: {}", e.getMessage());
            unavailable.add("neo4j");
        }
        if (unavailable.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "ready"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "not_ready", "unavailable", unavailable));
    }
}
