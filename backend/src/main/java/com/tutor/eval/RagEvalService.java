package com.tutor.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Evidence;
import com.tutor.config.ExecutorLifecycle;
import com.tutor.expert.IntentRouter;
import com.tutor.expert.RoutingPolicy;
import com.tutor.retrieval.GraphScope;
import com.tutor.retrieval.agentic.AgenticRetriever;
import com.tutor.retrieval.fusion.FusedRetriever;
import com.tutor.retrieval.graph.GraphExpansionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PreDestroy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 真实 RAG 评测执行器：每条 case 都调用生产检索管线，结果和失败 case 持久化到 eval_runs.metrics。
 * 不在这里复制 FusedRetriever/AgenticRetriever 逻辑，避免评测脚本与线上实现漂移。
 */
@Service
public class RagEvalService {
    private static final Logger log = LoggerFactory.getLogger(RagEvalService.class);
    private static final List<String> DEFAULT_MODES = List.of("vector_only", "fused", "fused_rerank", "agentic");
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_CASES = 300;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final FusedRetriever fusedRetriever;
    private final AgenticRetriever agenticRetriever;
    private final Path datasetPath;
    private final RagEvalQuality.GateThresholds gateThresholds;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore runningEvaluation = new Semaphore(1);

    public RagEvalService(JdbcTemplate jdbc,
                          ObjectMapper mapper,
                          FusedRetriever fusedRetriever,
                          AgenticRetriever agenticRetriever,
                          @Value("${tutor.eval.dataset-path:evals/rag_testset.json}") String datasetPath,
                          @Value("${tutor.eval.gate.min-overall-hit:0.70}") double minOverallHit,
                          @Value("${tutor.eval.gate.min-overall-recall:0.40}") double minOverallRecall,
                          @Value("${tutor.eval.gate.min-multi-hop-hit:0.50}") double minMultiHopHit,
                          @Value("${tutor.eval.gate.max-errors:0}") long maxErrors) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.fusedRetriever = fusedRetriever;
        this.agenticRetriever = agenticRetriever;
        this.datasetPath = Path.of(datasetPath);
        this.gateThresholds = new RagEvalQuality.GateThresholds(minOverallHit, minOverallRecall, minMultiHopHit, maxErrors);
    }

    @PreDestroy
    void shutdownExecutor() {
        ExecutorLifecycle.shutdown(executor, "rag-evaluation", log);
    }

    public record StartRequest(Integer topK, Integer limit, List<String> modes) {}

    public Map<String, Object> start(StartRequest request) {
        if (!runningEvaluation.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有 RAG 评测正在运行，请等待当前运行完成");
        }
        try {
        int topK = request != null && request.topK() != null ? request.topK() : DEFAULT_TOP_K;
        int limit = request != null && request.limit() != null ? request.limit() : MAX_CASES;
        if (topK < 1 || topK > 20) throw new IllegalArgumentException("topK must be between 1 and 20");
        if (limit < 1 || limit > MAX_CASES) throw new IllegalArgumentException("limit must be between 1 and " + MAX_CASES);
        List<String> modes = normalizeModes(request == null ? null : request.modes());
        Dataset dataset = loadDataset(limit);
        String modelConfig = json(Map.of("embedding", "configured", "modes", modes, "datasetTotalCases", dataset.totalCases()));

        Long runId = jdbc.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO eval_runs (mode, model_config, metrics, status, dataset_version, top_k, total_cases, started_at) " +
                            "VALUES (?, ?::jsonb, '{}'::jsonb, 'running', ?, ?, ?, now()) RETURNING id");
            ps.setString(1, "ab_full");
            ps.setString(2, modelConfig);
            ps.setString(3, dataset.version());
            ps.setInt(4, topK);
            ps.setInt(5, dataset.cases().size());
            return ps;
        }, rs -> {
            if (!rs.next()) throw new IllegalStateException("eval run insert returned no id");
            return rs.getLong(1);
        });
        executor.submit(() -> {
            try {
                run(runId, dataset, topK, modes);
            } finally {
                runningEvaluation.release();
            }
        });
        return Map.of("id", runId, "status", "running", "datasetVersion", dataset.version(),
                "topK", topK, "totalCases", dataset.cases().size(), "datasetTotalCases", dataset.totalCases(), "modes", modes);
        } catch (RuntimeException e) {
            runningEvaluation.release();
            throw e;
        }
    }

    public List<Map<String, Object>> listRuns() {
        return jdbc.query("SELECT id, status, mode, dataset_version, top_k, total_cases, error_message, started_at, finished_at, created_at " +
                        "FROM eval_runs ORDER BY created_at DESC LIMIT 30",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("status", rs.getString("status"));
                    row.put("mode", rs.getString("mode"));
                    row.put("datasetVersion", rs.getString("dataset_version"));
                    row.put("topK", rs.getObject("top_k"));
                    row.put("totalCases", rs.getObject("total_cases"));
                    row.put("error", rs.getString("error_message"));
                    row.put("startedAt", timestamp(rs.getObject("started_at")));
                    row.put("finishedAt", timestamp(rs.getObject("finished_at")));
                    row.put("createdAt", timestamp(rs.getObject("created_at")));
                    return row;
                });
    }

    public Map<String, Object> getRun(long id) {
        return jdbc.queryForObject("SELECT id, status, mode, model_config::text, metrics::text, dataset_version, top_k, total_cases, " +
                        "error_message, started_at, finished_at, created_at FROM eval_runs WHERE id=?",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("status", rs.getString("status"));
                    row.put("mode", rs.getString("mode"));
                    row.put("modelConfig", parse(rs.getString("model_config")));
                    row.put("metrics", parse(rs.getString("metrics")));
                    row.put("datasetVersion", rs.getString("dataset_version"));
                    row.put("topK", rs.getObject("top_k"));
                    row.put("totalCases", rs.getObject("total_cases"));
                    row.put("error", rs.getString("error_message"));
                    row.put("startedAt", timestamp(rs.getObject("started_at")));
                    row.put("finishedAt", timestamp(rs.getObject("finished_at")));
                    row.put("createdAt", timestamp(rs.getObject("created_at")));
                    return row;
                }, id);
    }

    private void run(long runId, Dataset dataset, int topK, List<String> modes) {
        try {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("datasetVersion", dataset.version());
            metrics.put("topK", topK);
            metrics.put("totalCases", dataset.cases().size());
            metrics.put("datasetTotalCases", dataset.totalCases());
            Map<String, Object> modeResults = new LinkedHashMap<>();
            for (String mode : modes) {
                modeResults.put(mode, evaluateMode(runId, mode, dataset.cases(), dataset.totalCases(), topK));
            }
            metrics.put("modes", modeResults);
            jdbc.update("UPDATE eval_runs SET status='completed', metrics=?::jsonb, finished_at=now() WHERE id=?",
                    json(metrics), runId);
        } catch (Exception e) {
            log.error("RAG evaluation failed run={}", runId, e);
            jdbc.update("UPDATE eval_runs SET status='failed', error_message=?, finished_at=now() WHERE id=?",
                    safeMessage(e), runId);
        }
    }

    private Map<String, Object> evaluateMode(long runId, String mode, List<JsonNode> cases, int datasetTotalCases, int topK) {
        List<Map<String, Object>> perCase = new ArrayList<>();
        for (JsonNode testCase : cases) {
            String caseId = testCase.path("id").asText("case-" + perCase.size());
            String query = testCase.path("query").asText("");
            String type = testCase.path("type").asText("unknown");
            List<String> gold = strings(testCase.path("gold"));
            long started = System.nanoTime();
            try {
                List<Evidence> results = retrieve(mode, testCase, topK, "eval-" + runId + "-" + mode + "-" + caseId);
                List<String> retrieved = results.stream().map(Evidence::nodeId).toList();
                List<String> hits = gold.stream().filter(retrieved::contains).distinct().toList();
                int firstRank = firstRank(retrieved, new LinkedHashSet<>(gold));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", caseId);
                row.put("query", query);
                row.put("type", type);
                row.put("gold", gold);
                row.put("retrieved", retrieved);
                row.put("hits", hits);
                row.put("recall", gold.isEmpty() ? 0D : (double) hits.size() / gold.size());
                row.put("rr", firstRank < 0 ? 0D : 1D / (firstRank + 1));
                row.put("hit", firstRank >= 0);
                row.put("latencyMs", elapsedMs(started));
                row.put("error", null);
                perCase.add(row);
            } catch (RuntimeException e) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", caseId);
                row.put("query", query);
                row.put("type", type);
                row.put("gold", gold);
                row.put("retrieved", List.of());
                row.put("hits", List.of());
                row.put("recall", 0D);
                row.put("rr", 0D);
                row.put("hit", false);
                row.put("latencyMs", elapsedMs(started));
                row.put("error", safeMessage(e));
                perCase.add(row);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        Map<String, Object> overall = RagEvalQuality.aggregate(perCase);
        result.put("overall", overall);
        Map<String, Object> byType = new LinkedHashMap<>();
        cases.stream().map(c -> c.path("type").asText("unknown")).distinct().forEach(type ->
                byType.put(type, RagEvalQuality.aggregate(perCase.stream().filter(c -> type.equals(c.get("type"))).toList())));
        result.put("byType", byType);
        result.put("badcaseClusters", RagEvalQuality.diagnoseAndCluster(perCase));
        result.put("qualityGate", RagEvalQuality.qualityGate(overall, byType, cases.size(), datasetTotalCases, gateThresholds));
        result.put("cases", perCase);
        return result;
    }

    private List<Evidence> retrieve(String mode, JsonNode evalCase, int topK, String traceId) {
        String query = evalCase.path("query").asText();
        boolean multiHop = "agentic".equals(mode);
        GraphExpansionPolicy policy = GraphExpansionPolicy.forFacets(
                evalFacets(evalCase.path("gold_intent").asText()),
                multiHop ? IntentRouter.RetrievalHint.MULTI_CANDIDATE : IntentRouter.RetrievalHint.SINGLE);
        GraphScope scope = GraphScope.publicOnly();
        return switch (mode) {
            case "vector_only" -> fusedRetriever.retrieve(query, topK, traceId, false, false, policy, scope).evidences();
            case "fused" -> fusedRetriever.retrieve(query, topK, traceId, true, false, policy, scope).evidences();
            case "fused_rerank" -> fusedRetriever.retrieve(query, topK, traceId, true, true, policy, scope).evidences();
            case "agentic" -> agenticRetriever.retrieveAdaptiveResult(
                    query, topK, traceId, true, policy, scope).evidences();
            default -> throw new IllegalArgumentException("unsupported eval mode: " + mode);
        };
    }

    /** 评测选择由数据集声明，不能在脚本中用问题文本再建一套隐式路由规则。 */
    private static List<RoutingPolicy.RetrievalFacet> evalFacets(String goldIntent) {
        return switch (goldIntent) {
            case "find_resource" -> List.of(RoutingPolicy.RetrievalFacet.RESOURCE);
            case "find_job" -> List.of(RoutingPolicy.RetrievalFacet.CAREER);
            case "learn_path" -> List.of(RoutingPolicy.RetrievalFacet.LEARNING);
            case "learn" -> List.of();
            default -> throw new IllegalArgumentException("unsupported eval gold_intent: " + goldIntent);
        };
    }

    private Dataset loadDataset(int limit) {
        try {
            String content;
            Path resolvedPath = resolveDatasetPath();
            if (resolvedPath != null) content = Files.readString(resolvedPath);
            else {
                var resource = getClass().getResourceAsStream("/eval/rag_testset.json");
                if (resource == null) throw new IllegalStateException("RAG dataset not found: " + datasetPath);
                content = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            JsonNode root = mapper.readTree(content);
            List<JsonNode> allCases = new ArrayList<>();
            root.path("cases").forEach(allCases::add);
            List<JsonNode> cases = new ArrayList<>(allCases.stream().limit(limit).toList());
            if (cases.isEmpty()) throw new IllegalStateException("RAG dataset has no cases");
            return new Dataset(root.path("version").asText("unknown"), cases, allCases.size());
        } catch (Exception e) {
            throw new IllegalStateException("cannot load RAG dataset: " + datasetPath, e);
        }
    }

    private Path resolveDatasetPath() {
        if (Files.exists(datasetPath)) return datasetPath;
        Path parentRelative = Path.of("..").resolve(datasetPath).normalize();
        return Files.exists(parentRelative) ? parentRelative : null;
    }

    private static List<String> normalizeModes(List<String> modes) {
        if (modes == null || modes.isEmpty()) return DEFAULT_MODES;
        List<String> normalized = modes.stream().filter(DEFAULT_MODES::contains).distinct().toList();
        if (normalized.isEmpty()) throw new IllegalArgumentException("modes must contain a supported mode");
        return normalized;
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static int firstRank(List<String> retrieved, Set<String> gold) {
        for (int i = 0; i < retrieved.size(); i++) if (gold.contains(retrieved.get(i))) return i;
        return -1;
    }

    private static long elapsedMs(long started) { return Math.max(0L, (System.nanoTime() - started) / 1_000_000L); }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("cannot serialize evaluation result", e); }
    }

    private JsonNode parse(String value) {
        try { return value == null ? mapper.createObjectNode() : mapper.readTree(value); }
        catch (Exception e) { return mapper.createObjectNode(); }
    }

    private static String timestamp(Object value) { return value == null ? null : String.valueOf(value); }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.substring(0, Math.min(500, message.length()));
    }

    private record Dataset(String version, List<JsonNode> cases, int totalCases) {}
}
