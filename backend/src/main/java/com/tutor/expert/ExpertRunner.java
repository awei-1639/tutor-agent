package com.tutor.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.context.TokenBudget;
import com.tutor.contract.Evidence;
import com.tutor.contract.ExpertOutput;
import com.tutor.contract.Intent;
import com.tutor.contract.Purpose;
import com.tutor.llm.LlmGateway;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 专家执行器 (V3 3.2): 三专家共用同一结构 (任务简报+structured output), 差异仅在 system prompt。
 * 专家context = 画像+证据+当前问题, 不带闲聊历史 (实现设计 3.2 任务简报原则)。
 * 降级矩阵: 单专家失败/超时按缺席处理, 部分成功照样仲裁; 扇出整体8s上限。
 */
@Component
public class ExpertRunner {
    private static final Logger log = LoggerFactory.getLogger(ExpertRunner.class);
    static final int EXPERT_TIMEOUT_SECONDS = 25;   // 单专家上限 (deepseek非流式实测5~15s)

    /** 专家注册表: name → system prompt。输出契约统一含 confidence 与 citations。 */
    private static final Map<String, String> EXPERTS = Map.of(
            "resume", """
                    你是简历优化专家。基于用户画像与知识证据, 输出JSON:
                    {"advice":[{"point":"建议","reason":"理由","priority":1}],
                     "match_score":0.0到1.0或null,
                     "confidence":0.0到1.0, "citations":["S1"]}
                    advice 3-6条按优先级排序; match_score仅在证据含具体岗位时给出;
                    citations只能引用证据编号; 证据不足时降低confidence并在advice中说明。只输出JSON。
                    """,
            "interview", """
                    你是面试模拟专家。基于用户画像与知识证据(岗位要求), 输出JSON:
                    {"questions":[{"q":"题目","type":"笔试|面试","answer_points":"答题要点"}],
                     "confidence":0.0到1.0, "citations":["S1"]}
                    出5道笔试+3道面试题, 与目标岗位技能强相关; 只输出JSON。
                    """,
            "planner", """
                    你是学习规划专家。基于用户画像(每日可投入时间/现有技能)与知识证据, 输出JSON:
                    {"weeks":[{"week":1,"goal":"目标","tasks":["任务"],"resources":["资源名"]}],
                     "confidence":0.0到1.0, "citations":["S1"]}
                    规划4周; 前置技能顺序必须符合证据中的图谱关系; resources优先用证据中的真实资源; 只输出JSON。
                    """);

    private final LlmGateway gateway;
    private final TokenBudget tokenBudget;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ExpertRunner(LlmGateway gateway, TokenBudget tokenBudget) {
        this.gateway = gateway;
        this.tokenBudget = tokenBudget;
    }

    public static List<String> expertsFor(Intent intent) {
        return switch (intent) {
            case RESUME -> List.of("resume");
            case INTERVIEW -> List.of("interview");
            case PLANNING -> List.of("planner");
            case MIXED -> List.of("resume", "interview", "planner");
            default -> List.of();
        };
    }

    /** 并行执行, 完成一个通知一个 (onExpertDone 用于SSE stage事件); 失败者为null已过滤 */
    public List<ExpertOutput> run(List<String> experts, String briefing, String traceId,
                                  Consumer<String> onExpertDone) {
        List<CompletableFuture<ExpertOutput>> futures = experts.stream()
                .map(name -> CompletableFuture
                        .supplyAsync(() -> runOne(name, briefing, traceId), executor)
                        .orTimeout(EXPERT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .whenComplete((r, e) -> onExpertDone.accept(name))
                        .exceptionally(e -> {
                            log.warn("专家{}缺席 trace={}: {}", name, traceId, e.getMessage());
                            return null;
                        }))
                .toList();
        return futures.stream().map(CompletableFuture::join).filter(x -> x != null).toList();
    }

    private ExpertOutput runOne(String name, String briefing, String traceId) {
        String json = gateway.chatJson(Purpose.EXPERT, List.of(
                SystemMessage.from(EXPERTS.get(name)),
                UserMessage.from(briefing)), traceId);
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("专家输出JSON解析失败: " + e.getMessage());
        }
        List<String> citations = new ArrayList<>();
        root.path("citations").forEach(c -> citations.add(c.asText()));
        double confidence = root.path("confidence").asDouble(0.5);
        return new ExpertOutput(name, json, confidence, citations);
    }

    /** 专家任务简报: 画像 + 证据 + 问题 (裁剪至预算内) */
    public String briefing(String profileText, List<Evidence> evidences, String question) {
        StringBuilder sb = new StringBuilder();
        if (!profileText.isBlank()) sb.append(profileText).append('\n');
        sb.append("## 知识证据\n");
        for (int i = 0; i < evidences.size(); i++) {
            sb.append("[S").append(i + 1).append("] ").append(evidences.get(i).chunkText()).append('\n');
        }
        sb.append("\n## 用户请求\n").append(question);
        return tokenBudget.truncate(sb.toString(), 3500);
    }
}
