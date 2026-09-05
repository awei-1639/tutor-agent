package com.tutor.identity.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SQL boundary for administrator overview, user management, and audit views. */
@Repository
public class AdminStore {
    private final JdbcTemplate jdbc;
    public AdminStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Map<String,Object> overviewUsers() { return jdbc.queryForMap("""
            SELECT count(*) AS total, count(*) FILTER (WHERE deleted_at IS NULL AND disabled_at IS NULL) AS active,
                   count(*) FILTER (WHERE deleted_at IS NULL AND disabled_at IS NOT NULL) AS disabled,
                   count(*) FILTER (WHERE deleted_at IS NOT NULL) AS deleted,
                   count(*) FILTER (WHERE role='ADMIN' AND deleted_at IS NULL) AS admins FROM users"""); }

    List<Map<String,Object>> recentEvalRuns() { return jdbc.query("""
            SELECT id,status,dataset_version,top_k,total_cases,started_at,finished_at,created_at
            FROM eval_runs ORDER BY created_at DESC LIMIT 6""", (rs,i) -> {
        Map<String,Object> row=new LinkedHashMap<>(); row.put("id",rs.getLong("id")); row.put("status",rs.getString("status"));
        row.put("datasetVersion",rs.getString("dataset_version")); row.put("topK",rs.getObject("top_k")); row.put("totalCases",rs.getObject("total_cases"));
        row.put("startedAt",timestamp(rs.getObject("started_at"))); row.put("finishedAt",timestamp(rs.getObject("finished_at"))); row.put("createdAt",timestamp(rs.getObject("created_at"))); return row; }); }

    Map<String,Object> interviewMetrics() { return jdbc.queryForMap("""
            SELECT (SELECT count(*) FROM interview_sessions WHERE status IN ('COMPLETED','CANCELLED')) AS finalized_sessions,
                   (SELECT count(*) FROM interview_feedback) AS total_feedback,
                   (SELECT count(*) FROM interview_feedback WHERE rating='inaccurate') AS inaccurate_feedback,
                   COALESCE((SELECT avg((q.scorecard->>'confidence')::numeric) FROM interview_questions q JOIN interview_sessions s ON s.id=q.session_id WHERE s.status IN ('COMPLETED','CANCELLED') AND q.scorecard ? 'confidence'),0) AS avg_confidence"""); }

    List<Map<String,Object>> recentCalibration() { return jdbc.query("""
            SELECT rating,reason,created_at FROM interview_feedback WHERE rating='inaccurate' AND reason<>'' ORDER BY updated_at DESC LIMIT 5""",
            (rs,i)->Map.of("rating",rs.getString("rating"),"reason",rs.getString("reason"),"createdAt",timestamp(rs.getObject("created_at")))); }

    List<Map<String,Object>> users(String search,String status,int page,int size) {
        Filters filters=filters(search,status); List<Object> args=new ArrayList<>(filters.args()); args.add(size); args.add(page*size);
        return jdbc.query("SELECT id,email,name,role,created_at,disabled_at,deleted_at FROM users"+filters.sql()+" ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs,i)->userRow(rs),args.toArray());
    }
    long userCount(String search,String status) { Filters f=filters(search,status); Long n=jdbc.queryForObject("SELECT count(*) FROM users"+f.sql(),Long.class,f.args().toArray()); return n==null?0:n; }
    boolean isAdmin(long id) { Integer n=jdbc.queryForObject("SELECT count(*) FROM users WHERE id=? AND role='ADMIN' AND deleted_at IS NULL AND disabled_at IS NULL",Integer.class,id); return n!=null&&n>0; }
    int disable(long id) { return jdbc.update("UPDATE users SET disabled_at=COALESCE(disabled_at,now()) WHERE id=? AND deleted_at IS NULL",id); }
    int restore(long id) { return jdbc.update("UPDATE users SET disabled_at=NULL, deleted_at=NULL WHERE id=?",id); }
    int softDelete(long id) { return jdbc.update("UPDATE users SET deleted_at=COALESCE(deleted_at,now()), disabled_at=COALESCE(disabled_at,now()) WHERE id=?",id); }
    List<Map<String,Object>> audit(int limit) { return jdbc.query("""
            SELECT a.id,a.action,a.admin_user_id,au.name AS admin_name,a.target_user_id,tu.name AS target_name,a.metadata,a.created_at
            FROM admin_audit_log a LEFT JOIN users au ON au.id=a.admin_user_id LEFT JOIN users tu ON tu.id=a.target_user_id
            ORDER BY a.created_at DESC LIMIT ?""",AdminStore::auditRow,limit); }
    void audit(long adminId,String action,Long targetId,String metadata) { jdbc.update("INSERT INTO admin_audit_log (admin_user_id,action,target_user_id,metadata) VALUES (?,?,?,?::jsonb)",adminId,action,targetId,metadata==null||metadata.isBlank()?"{}":metadata); }

    private Filters filters(String search,String status) { StringBuilder sql=new StringBuilder(" WHERE 1=1"); List<Object> args=new ArrayList<>(); if(search!=null&&!search.isBlank()){sql.append(" AND (email ILIKE ? OR name ILIKE ?)");String t="%"+search.trim()+"%";args.add(t);args.add(t);} if("active".equals(status))sql.append(" AND deleted_at IS NULL AND disabled_at IS NULL"); else if("disabled".equals(status))sql.append(" AND deleted_at IS NULL AND disabled_at IS NOT NULL"); else if("deleted".equals(status))sql.append(" AND deleted_at IS NOT NULL"); return new Filters(sql.toString(),args); }
    private record Filters(String sql,List<Object> args) {}
    private static Map<String,Object> userRow(java.sql.ResultSet rs) throws java.sql.SQLException { Map<String,Object> r=new LinkedHashMap<>(); r.put("id",rs.getLong("id"));r.put("email",rs.getString("email"));r.put("name",rs.getString("name"));r.put("role",rs.getString("role"));r.put("status",statusOf(rs.getObject("deleted_at"),rs.getObject("disabled_at")));r.put("createdAt",timestamp(rs.getObject("created_at")));r.put("disabledAt",timestamp(rs.getObject("disabled_at")));r.put("deletedAt",timestamp(rs.getObject("deleted_at")));return r; }
    private static String statusOf(Object d,Object x){return d!=null?"deleted":x!=null?"disabled":"active";}
    private static String timestamp(Object v){return v==null?null:v.toString();}
    private static Map<String,Object> auditRow(java.sql.ResultSet rs,int i) throws java.sql.SQLException {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("id",rs.getLong("id")); row.put("action",rs.getString("action"));
        row.put("adminUserId",rs.getObject("admin_user_id")); row.put("adminName",rs.getString("admin_name"));
        row.put("targetUserId",rs.getObject("target_user_id")); row.put("targetName",rs.getString("target_name"));
        row.put("metadata",rs.getString("metadata")); row.put("createdAt",timestamp(rs.getObject("created_at")));
        return row;
    }
}
