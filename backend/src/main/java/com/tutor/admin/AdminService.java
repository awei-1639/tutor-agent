package com.tutor.admin;

import com.tutor.auth.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理端业务：所有权限判断都在服务端按当前数据库状态执行。 */
@Service
public class AdminService {
    private final JdbcTemplate jdbc;

    public AdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> overview() {
        long adminId = requireAdmin();
        Map<String, Object> users = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE deleted_at IS NULL AND disabled_at IS NULL) AS active,
                       count(*) FILTER (WHERE deleted_at IS NULL AND disabled_at IS NOT NULL) AS disabled,
                       count(*) FILTER (WHERE deleted_at IS NOT NULL) AS deleted,
                       count(*) FILTER (WHERE role = 'ADMIN' AND deleted_at IS NULL) AS admins
                FROM users
                """);
        List<Map<String, Object>> evalRuns = jdbc.query("""
                SELECT id, status, dataset_version, top_k, total_cases, started_at, finished_at, created_at
                FROM eval_runs ORDER BY created_at DESC LIMIT 6
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("status", rs.getString("status"));
            row.put("datasetVersion", rs.getString("dataset_version"));
            row.put("topK", rs.getObject("top_k"));
            row.put("totalCases", rs.getObject("total_cases"));
            row.put("startedAt", timestamp(rs.getObject("started_at")));
            row.put("finishedAt", timestamp(rs.getObject("finished_at")));
            row.put("createdAt", timestamp(rs.getObject("created_at")));
            return row;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operatorId", adminId);
        result.put("users", users);
        result.put("recentEvalRuns", evalRuns);
        result.put("interviewQuality", interviewQuality());
        result.put("checks", Map.of(
                "database", "available",
                "evaluation", evalRuns.stream().anyMatch(run -> "running".equals(run.get("status"))) ? "running" : "idle"));
        return result;
    }

    /** Aggregated calibration signals only; answer content and user identity stay out of operations views. */
    private Map<String, Object> interviewQuality() {
        Map<String, Object> metrics = jdbc.queryForMap("""
                SELECT
                  (SELECT count(*) FROM interview_sessions WHERE status IN ('COMPLETED', 'CANCELLED')) AS finalized_sessions,
                  (SELECT count(*) FROM interview_feedback) AS total_feedback,
                  (SELECT count(*) FROM interview_feedback WHERE rating='inaccurate') AS inaccurate_feedback,
                  COALESCE((SELECT avg((q.scorecard->>'confidence')::numeric)
                    FROM interview_questions q JOIN interview_sessions s ON s.id=q.session_id
                    WHERE s.status IN ('COMPLETED', 'CANCELLED') AND q.scorecard ? 'confidence'), 0) AS avg_confidence
                """);
        List<Map<String, Object>> recentCalibration = jdbc.query("""
                SELECT rating, reason, created_at FROM interview_feedback
                WHERE rating='inaccurate' AND reason<>''
                ORDER BY updated_at DESC LIMIT 5
                """, (rs, rowNum) -> Map.of(
                "rating", rs.getString("rating"),
                "reason", rs.getString("reason"),
                "createdAt", timestamp(rs.getObject("created_at"))));
        long total = ((Number) metrics.get("total_feedback")).longValue();
        long inaccurate = ((Number) metrics.get("inaccurate_feedback")).longValue();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("finalizedSessions", ((Number) metrics.get("finalized_sessions")).longValue());
        result.put("totalFeedback", total);
        result.put("inaccurateFeedback", inaccurate);
        result.put("inaccurateRate", total == 0 ? 0D : (double) inaccurate / total);
        result.put("avgConfidence", ((Number) metrics.get("avg_confidence")).doubleValue());
        result.put("recentCalibration", recentCalibration);
        return result;
    }

    public Map<String, Object> listUsers(String search, String status, int page, int size) {
        requireAdmin();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            where.append(" AND (email ILIKE ? OR name ILIKE ?)");
            String term = "%" + search.trim() + "%";
            params.add(term);
            params.add(term);
        }
        if ("active".equals(status)) {
            where.append(" AND deleted_at IS NULL AND disabled_at IS NULL");
        } else if ("disabled".equals(status)) {
            where.append(" AND deleted_at IS NULL AND disabled_at IS NOT NULL");
        } else if ("deleted".equals(status)) {
            where.append(" AND deleted_at IS NOT NULL");
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM users" + where, Long.class, params.toArray());
        String sql = "SELECT id, email, name, role, created_at, disabled_at, deleted_at FROM users" + where
                + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        params.add(safeSize);
        params.add(safePage * safeSize);
        List<Map<String, Object>> items = jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("email", rs.getString("email"));
            row.put("name", rs.getString("name"));
            row.put("role", rs.getString("role"));
            row.put("status", statusOf(rs.getObject("deleted_at"), rs.getObject("disabled_at")));
            row.put("createdAt", timestamp(rs.getObject("created_at")));
            row.put("disabledAt", timestamp(rs.getObject("disabled_at")));
            row.put("deletedAt", timestamp(rs.getObject("deleted_at")));
            return row;
        }, params.toArray());
        return Map.of("items", items, "page", safePage, "size", safeSize, "total", total == null ? 0 : total);
    }

    public void disable(long targetId) {
        long adminId = requireAdmin();
        assertNotSelf(adminId, targetId);
        int updated = jdbc.update("UPDATE users SET disabled_at = COALESCE(disabled_at, now()) "
                + "WHERE id=? AND deleted_at IS NULL", targetId);
        if (updated == 0) throw notFound();
        audit(adminId, "USER_DISABLED", targetId);
    }

    public void restore(long targetId) {
        long adminId = requireAdmin();
        int updated = jdbc.update("UPDATE users SET disabled_at=NULL, deleted_at=NULL WHERE id=?", targetId);
        if (updated == 0) throw notFound();
        audit(adminId, "USER_RESTORED", targetId);
    }

    public void softDelete(long targetId) {
        long adminId = requireAdmin();
        assertNotSelf(adminId, targetId);
        int updated = jdbc.update("UPDATE users SET deleted_at=COALESCE(deleted_at, now()), "
                + "disabled_at=COALESCE(disabled_at, now()) WHERE id=?", targetId);
        if (updated == 0) throw notFound();
        audit(adminId, "USER_SOFT_DELETED", targetId);
    }

    public List<Map<String, Object>> audit(int limit) {
        requireAdmin();
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return jdbc.query("""
                SELECT a.id, a.action, a.admin_user_id, au.name AS admin_name,
                       a.target_user_id, tu.name AS target_name, a.metadata, a.created_at
                FROM admin_audit_log a
                LEFT JOIN users au ON au.id = a.admin_user_id
                LEFT JOIN users tu ON tu.id = a.target_user_id
                ORDER BY a.created_at DESC LIMIT ?
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("action", rs.getString("action"));
            row.put("adminUserId", rs.getObject("admin_user_id"));
            row.put("adminName", rs.getString("admin_name"));
            row.put("targetUserId", rs.getObject("target_user_id"));
            row.put("targetName", rs.getString("target_name"));
            row.put("metadata", rs.getString("metadata"));
            row.put("createdAt", timestamp(rs.getObject("created_at")));
            return row;
        }, safeLimit);
    }

    public long requireAdmin() {
        Long userId = AuthContext.currentUserId();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM users
                WHERE id=? AND role='ADMIN' AND deleted_at IS NULL AND disabled_at IS NULL
                """, Integer.class, userId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
        return userId;
    }

    private void assertNotSelf(long adminId, long targetId) {
        if (adminId == targetId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能修改当前管理员账号");
        }
    }

    public void audit(long adminId, String action, long targetId) {
        jdbc.update("INSERT INTO admin_audit_log (admin_user_id, action, target_user_id, metadata) "
                + "VALUES (?, ?, ?, '{}'::jsonb)", adminId, action, targetId);
    }

    /** Records an admin operation whose target is not a user (for example, an evaluation sample). */
    public void auditEvent(long adminId, String action, String metadataJson) {
        jdbc.update("INSERT INTO admin_audit_log (admin_user_id, action, target_user_id, metadata) "
                + "VALUES (?, ?, NULL, ?::jsonb)", adminId, action,
                metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在或状态不允许此操作");
    }

    private static String statusOf(Object deletedAt, Object disabledAt) {
        if (deletedAt != null) return "deleted";
        if (disabledAt != null) return "disabled";
        return "active";
    }

    private static String timestamp(Object value) {
        return value == null ? null : value.toString();
    }
}
