package com.tutor.chat.internal;

import com.tutor.contract.Evidence;
import com.tutor.contract.Intent;
import com.tutor.expert.IntentRouter;
import com.tutor.retrieval.agentic.AgenticRetriever;
import com.tutor.retrieval.fusion.FusedRetriever;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 内部评估端点 (evals/run_eval.mjs 使用): 评估必须打真实管线, 禁止在脚本里重写检索逻辑。
 * 单机单用户开发环境, 不做鉴权 (Phase 4 多用户时随JWT一并处理)。
 */
@RestController
@RequestMapping("/internal")
public class InternalController {
    private final FusedRetriever retriever;
    private final AgenticRetriever agenticRetriever;
    private final IntentRouter router;

    public InternalController(FusedRetriever retriever,
                              AgenticRetriever agenticRetriever,
                              IntentRouter router,
                              com.tutor.push.PushService pushService) {
        this.retriever = retriever;
        this.agenticRetriever = agenticRetriever;
        this.router = router;
        this.pushService = pushService;
    }

    public record RetrieveRequest(@NotBlank @Size(max = 4000) String query,
                                  @Min(1) @Max(20) Integer topK, @Size(max = 32) String mode) {}

    @PostMapping("/retrieve")
    public Map<String, Object> retrieve(@Valid @RequestBody RetrieveRequest req) {
        String mode = req.mode() == null ? "agentic" : req.mode();
        long t0 = System.currentTimeMillis();
        List<Evidence> results;
        if ("agentic".equals(mode)) {
            results = agenticRetriever.retrieve(req.query(), req.topK() == null ? 5 : req.topK(), "eval");
        } else {
            boolean fused = !"vector_only".equals(mode);
            boolean rerank = "fused_rerank".equals(mode);
            results = retriever.retrieve(req.query(), req.topK() == null ? 5 : req.topK(), "eval", fused, rerank);
        }
        long ms = System.currentTimeMillis() - t0;
        return Map.of(
                "mode", mode,
                "latency_ms", ms,
                "results", results.stream().map(e -> Map.of(
                        "node_id", e.nodeId(), "type", e.nodeType(), "score", e.score())).toList());
    }

    private final com.tutor.push.PushService pushService;

    public record RouteRequest(@NotBlank @Size(max = 4000) String question) {}

    @PostMapping("/push-run")
    public Map<String, Object> pushRun() {
        return pushService.runOnce();
    }

    @PostMapping("/route")
    public Map<String, Object> route(@Valid @RequestBody RouteRequest req) {
        Intent intent = router.route(req.question(), List.of(), "eval");
        return Map.of("intent", intent.name().toLowerCase());
    }
}
