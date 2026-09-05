package com.tutor.coaching.push;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Owns released-job SQL and row mapping for the career-gap use case. */
@Repository
class CareerJobStore {
    private final JdbcTemplate jdbc;

    CareerJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Job findReleasedById(long jobId) {
        return jdbc.query(
                        "SELECT id, title, company, city, requires_raw FROM jobs WHERE id=? AND released",
                        this::mapJob, jobId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("岗位不存在或不可用"));
    }

    List<Job> findReleasedForTarget(String target) {
        String sql = "SELECT id, title, company, city, requires_raw FROM jobs WHERE released"
                + (target.isBlank() ? "" : " AND title ILIKE ?") + " ORDER BY id LIMIT 3";
        List<Job> matching = target.isBlank() ? jdbc.query(sql, this::mapJob)
                : jdbc.query(sql, this::mapJob, "%" + target + "%");
        return matching.isEmpty() && !target.isBlank()
                ? jdbc.query("SELECT id, title, company, city, requires_raw FROM jobs WHERE released ORDER BY id LIMIT 3",
                this::mapJob)
                : matching;
    }

    private Job mapJob(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Array array = rs.getArray(5);
        String[] requires = array == null ? new String[0] : (String[]) array.getArray();
        return new Job(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), List.of(requires));
    }

    record Job(long id, String title, String company, String city, List<String> requires) {
    }
}
