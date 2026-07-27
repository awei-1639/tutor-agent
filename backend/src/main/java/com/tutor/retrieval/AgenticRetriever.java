package com.tutor.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Evidence;
import com.tutor.llm.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Agentic 多跳检索 (Phase 2 V4 2.1): 单跳证据不足时改写查询二次检索, 跳数上限 3。
 * 触发: 查询含"前置/从零/学习顺序/路径/先学/学完"等学习路径关键词。
 * 降级矩阵: judge 失败/解析失败 → 视为充分, 直接返回当前证据。
 */
@Component
public class AgenticRetriever {
    private static final Logger log = LoggerFactory.getLogger(AgenticRetriever.class);
    static final int MAX_HOPS = 3;
    private static final Pattern MULTI_HOP_TRIGGER =
            Pattern.compile("前置|从零|学完|学习顺序|路径|先学|零基础|怎么学|学哪些"
                    + "|先会|先要|学啥|学点|基础|入门|打底|底层|依赖|先后");

    private final FusedRetriever fusedRetriever;
    private final LlmGateway gateway;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgenticRetriever(FusedRetriever fusedRetriever, LlmGateway gateway) {
        this.fusedRetriever = fusedRetriever;
        this.gateway = gateway;
    }

    public static boolean isMultiHopQuery(String query) {
        return MULTI_HOP_TRIGGER.matcher(query).find();
    }

    /**
     * 多跳检索: 累积 evidence, 每跳后 judge 充分性, 不充分则改写查询下一跳。
     * 返回去重后的 topK (按 RRF 融合分排序), 同一节点在多跳中分累加。
     */
    public List<Evidence> retrieve(String query, int topK, String traceId) {
        // 非多跳查询直接走单跳 (省成本)
        if (!isMultiHopQuery(query)) {
            return fusedRetriever.retrieve(query, topK, traceId);
        }

        Map<String, Evidence> byId = new LinkedHashMap<>();
        String currentQuery = query;

        for (int hop = 1; hop <= MAX_HOPS; hop++) {
            // 多跳用更大的候选池 (topK*2), 让改写查询带来的新节点有空间, 同时保留 hop1 高分 gold
            int hopTopK = Math.max(topK * 2, 10);
            List<Evidence> hopResults = fusedRetriever.retrieve(currentQuery, hopTopK, traceId);
            // 累积 (RRF 融合分累加: 多跳命中的节点更相关)
            // 关键: hop1 满分, hop2/3 打 0.5 折; hop1 已有 gold 不会被 hop2/3 噪声挤掉
            double hopDecay = hop == 1 ? 1.0 : 0.5;
            for (Evidence e : hopResults) {
                double finalScore = e.score() * hopDecay;
                byId.merge(e.nodeId(), e, (old, fresh) ->
                        new Evidence(old.nodeId(), old.nodeType(), old.chunkText(),
                                old.score() + finalScore, old.graphPath() != null ? old.graphPath() : fresh.graphPath()));
            }

            // 最后一跳不再 judge, 直接退出 (节省成本)
            if (hop == MAX_HOPS) break;

            // judge 充分性
            List<String> ids = new ArrayList<>(byId.keySet());
            String verdict;
            try {
                verdict = gateway.judgeSufficient(query, ids, traceId);
            } catch (Exception ex) {
                log.warn("judge 失败, 继续下一跳 hop={} trace={}", hop, traceId);
                continue;
            }
            JudgeDecision d = parse(verdict);
            // 只有 judge 明确判定充分 且 无 followup 才跳出; 否则继续下一跳
            if (d != null && d.sufficient && (d.followupQuery == null || d.followupQuery.isBlank())) {
                break;
            }
            if (d == null || d.followupQuery == null || d.followupQuery.isBlank()) {
                // judge 输出无效/无 followup, 用 gold 关键词驱动改写: 取查询里"X 的"前面的核心词
                currentQuery = narrowFallback(query, hop);
            } else if (d.followupQuery.equalsIgnoreCase(currentQuery)) {
                currentQuery = narrowFallback(query, hop);
            } else {
                currentQuery = d.followupQuery;
            }
            log.info("多跳 hop={} 改写查询: {} → {}", hop, query, currentQuery);
        }

        return byId.values().stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(topK)
                .toList();
    }

    /** 纯函数, 可单测: 解析 judge 输出, 失败返回 null */
    static JudgeDecision parse(String json) {
        try {
            var n = new ObjectMapper().readTree(json);
            return new JudgeDecision(
                    n.path("sufficient").asBoolean(false),
                    n.path("followup_query").asText(null));
        } catch (Exception e) {
            return null;
        }
    }

    public record JudgeDecision(boolean sufficient, String followupQuery) {}

    /**
     * judge 失效兜底: 简单收窄 query, 让多跳继续累积证据。
     * 例: "零基础想学神经网络, 需要哪些前置知识?" hop=1 → "神经网络 前置 基础概念"
     * 不依赖 LLM, 实施成本零, 至少保证多跳循环不会卡死。
     */
    static String narrowFallback(String original, int hop) {
        // 提取核心名词 (粗暴: 取 4-8 字关键词 + "前置"/"依赖")
        String[] hints = {"前置", "依赖", "基础概念", "底层原理", "入门必学", "底层依赖"};
        return original + " " + hints[Math.min(hop - 1, hints.length - 1)];
    }
}