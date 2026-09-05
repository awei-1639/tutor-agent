package com.tutor.config;

import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Checks required backing stores for the readiness probe. */
@Service
public class HealthReadinessService {
    private static final Logger log = LoggerFactory.getLogger(HealthReadinessService.class);
    private final JdbcTemplate jdbc;
    private final Driver neo4j;

    public HealthReadinessService(JdbcTemplate jdbc, Driver neo4j) {
        this.jdbc = jdbc;
        this.neo4j = neo4j;
    }

    public List<String> unavailableDependencies() {
        List<String> unavailable = new ArrayList<>();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (RuntimeException error) {
            log.warn("readiness: PostgreSQL unavailable: {}", error.getMessage());
            unavailable.add("postgres");
        }
        try {
            neo4j.verifyConnectivity();
        } catch (RuntimeException error) {
            log.warn("readiness: Neo4j unavailable: {}", error.getMessage());
            unavailable.add("neo4j");
        }
        return List.copyOf(unavailable);
    }
}
