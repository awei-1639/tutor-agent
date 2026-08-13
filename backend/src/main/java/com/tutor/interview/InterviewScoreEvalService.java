package com.tutor.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the versioned, human-labelled scoring-contract regression dataset. */
@Service
public class InterviewScoreEvalService {
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    public InterviewScoreEvalService(ObjectMapper mapper) {
        this.mapper = mapper;
        this.jdbc = null;
    }

    @Autowired
    public InterviewScoreEvalService(ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    public record ReplayCase(String id, int humanScore, int modelScore, double modelConfidence,
                             int reviewerCount, int humanScoreSpread) {
        public ReplayCase(String id, int humanScore, int modelScore, double modelConfidence) {
            this(id, humanScore, modelScore, modelConfidence, 1, 0);
        }
    }
    public record ReplayRequest(String datasetVersion, List<ReplayCase> cases) {}

    /** Evaluates recorded production/model outputs without invoking the model again. */
    public Map<String, Object> replay(ReplayRequest request) {
        if (request == null || request.cases() == null || request.cases().isEmpty()) {
            throw new IllegalArgumentException("至少需要一条模型评分记录");
        }
        if (request.cases().size() > 1000) throw new IllegalArgumentException("单次最多评测 1000 条记录");
        request.cases().forEach(this::validateReplayCase);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "recorded_model_vs_human");
        result.put("datasetVersion", request.datasetVersion() == null ? "unknown" : request.datasetVersion());
        Map<String, Object> metrics = InterviewModelEvalQuality.aggregate(request.cases());
        result.put("metrics", metrics);
        result.put("note", "结果基于已记录的模型输出与人工金标，不会在评测过程中再次调用模型。");
        if (jdbc != null) result.put("runId", persistRun(request.datasetVersion(), request.cases().size(), metrics));
        return result;
    }

    public List<Map<String, Object>> listReplayRuns() {
        if (jdbc == null) return List.of();
        return jdbc.query("""
                SELECT id, dataset_version, case_count, created_at
                FROM interview_score_eval_runs ORDER BY created_at DESC LIMIT 50
                """, (rs, i) -> Map.of("id", rs.getLong(1), "datasetVersion", rs.getString(2),
                "caseCount", rs.getInt(3), "createdAt", rs.getTimestamp(4).toInstant()));
    }

    public Map<String, Object> getReplayRun(long id) {
        if (jdbc == null) throw new IllegalStateException("评测持久化未配置");
        return jdbc.queryForObject("""
                SELECT id, dataset_version, case_count, metrics::text, created_at
                FROM interview_score_eval_runs WHERE id=?
                """, (rs, i) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("id", rs.getLong(1));
                    result.put("datasetVersion", rs.getString(2));
                    result.put("caseCount", rs.getInt(3));
                    result.put("metrics", parse(rs.getString(4)));
                    result.put("createdAt", rs.getTimestamp(5).toInstant());
                    return result;
                }, id);
    }

    private long persistRun(String datasetVersion, int caseCount, Map<String, Object> metrics) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO interview_score_eval_runs (dataset_version, case_count, metrics)
                    VALUES (?, ?, ?::jsonb) RETURNING id
                    """, Long.class, datasetVersion == null ? "unknown" : datasetVersion, caseCount,
                    mapper.writeValueAsString(metrics));
        } catch (Exception e) {
            throw new IllegalStateException("无法保存面试评分评测结果", e);
        }
    }

    private Object parse(String value) {
        try { return mapper.readValue(value, Object.class); }
        catch (Exception e) { return Map.of(); }
    }

    public Map<String, Object> run() {
        JsonNode dataset = loadDataset();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : dataset.path("cases")) {
            rows.add(InterviewScoreEvalQuality.evaluateCase(
                    item.path("id").asText(), item.path("answer").asText(), strings(item.path("requiredPoints")),
                    strings(item.path("bonusPoints")), strings(item.path("criticalErrors")), item.path("expectedScore").asInt()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "deterministic_contract_baseline");
        result.put("datasetVersion", dataset.path("version").asText("unknown"));
        result.put("metrics", InterviewScoreEvalQuality.aggregate(rows));
        result.put("cases", rows);
        result.put("note", "该评测验证评分契约与确定性基线，不代表 LLM 与人工评分的一致性；后者需接入双人标注金标集后独立运行。");
        return result;
    }

    private JsonNode loadDataset() {
        try (InputStream input = getClass().getResourceAsStream("/eval/interview_score_testset.json")) {
            if (input == null) throw new IllegalStateException("interview scoring dataset is missing");
            return mapper.readTree(input);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load interview scoring dataset", e);
        }
    }

    private void validateReplayCase(ReplayCase item) {
        if (item == null || item.id() == null || item.id().isBlank()) throw new IllegalArgumentException("评分记录缺少 id");
        if (item.humanScore() < 0 || item.humanScore() > 10 || item.modelScore() < 0 || item.modelScore() > 10) {
            throw new IllegalArgumentException("评分必须在 0 到 10 之间: " + item.id());
        }
        if (item.reviewerCount() < 1 || item.reviewerCount() > 100
                || item.humanScoreSpread() < 0 || item.humanScoreSpread() > 10
                || (item.reviewerCount() == 1 && item.humanScoreSpread() != 0)) {
            throw new IllegalArgumentException("评审人数或分数跨度不合法: " + item.id());
        }
        if (Double.isNaN(item.modelConfidence()) || item.modelConfidence() < 0 || item.modelConfidence() > 1) {
            throw new IllegalArgumentException("模型置信度必须在 0 到 1 之间: " + item.id());
        }
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) node.forEach(item -> values.add(item.asText()));
        return values;
    }
}
