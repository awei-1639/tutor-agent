package com.tutor.chat.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Evidence;
import com.tutor.guard.CitationSourcePolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把回答里的 [S#] 标记映射回证据，产出落库与 SSE 所需的引用负载。
 *
 * 纯函数集合：只依赖回答文本与本轮证据，不触碰会话、LLM 或存储，因此可独立单测。
 */
final class TurnCitations {
    /** 单条回答最多映射前 10 条证据，与 EvidenceSection 的渲染上限保持一致。 */
    private static final int MAX_CITED_EVIDENCE = 10;
    private static final int MAX_REPORTED_ISSUES = 20;
    private static final Pattern CITE = Pattern.compile("\\[S(\\d+)]");

    private final ObjectMapper mapper = new ObjectMapper();

    /** citations JSON、引用状态与非法引用清单。 */
    record Bundle(String json, String status, String issuesJson) {}

    /**
     * 与提示词中实际保留的引用位置对齐：未进入提示词的位置置空，
     * 避免异步引用校验拿到模型看不到的证据。
     */
    List<Evidence> forVerification(List<Evidence> evidences, Set<String> availableCitationIds) {
        if (evidences == null || evidences.isEmpty()) return List.of();
        List<Evidence> bounded = new ArrayList<>(evidences.subList(0, Math.min(evidences.size(), MAX_CITED_EVIDENCE)));
        for (int i = 0; i < bounded.size(); i++) {
            if (availableCitationIds == null || !availableCitationIds.contains("S" + (i + 1))) {
                bounded.set(i, null);
            }
        }
        return Collections.unmodifiableList(bounded);
    }

    Bundle bundleFor(String text, List<Evidence> evidences, Set<String> availableCitationIds) {
        Set<Integer> used = new LinkedHashSet<>();
        Set<String> invalid = new LinkedHashSet<>();
        Matcher matcher = CITE.matcher(text);
        while (matcher.find()) {
            int index = parseIndex(matcher.group(1));
            if (isCitable(index, evidences, availableCitationIds)) {
                used.add(index);
            } else {
                invalid.add("S" + matcher.group(1));
            }
        }
        try {
            // 保留卡片所需的完整引用信息，历史会话恢复后也能查看溯源。
            String json = mapper.writeValueAsString(used.stream().map(index -> describe(evidences.get(index), index)).toList());
            String status = !invalid.isEmpty() ? "invalid_reference" : used.isEmpty() ? "not_applicable" : "pending";
            return new Bundle(json, status, mapper.writeValueAsString(invalid));
        } catch (Exception e) {
            return new Bundle("[]", "unavailable", "[]");
        }
    }

    List<String> parseIssues(String issuesJson) {
        if (issuesJson == null || issuesJson.isBlank()) return List.of();
        try {
            var node = mapper.readTree(issuesJson);
            if (!node.isArray()) return List.of();
            List<String> issues = new ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual() && issues.size() < MAX_REPORTED_ISSUES) issues.add(item.asText());
            });
            return List.copyOf(issues);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private boolean isCitable(int index, List<Evidence> evidences, Set<String> availableCitationIds) {
        return index >= 0
                && index < Math.min(evidences.size(), MAX_CITED_EVIDENCE)
                && availableCitationIds != null
                && availableCitationIds.contains("S" + (index + 1))
                && evidences.get(index) != null;
    }

    private Map<String, String> describe(Evidence evidence, int index) {
        CitationSourcePolicy.Provenance provenance = CitationSourcePolicy.inspect(evidence);
        String[] parts = evidence.chunkText().split("\\|", 3);
        return Map.of(
                "sid", "S" + (index + 1),
                "node_id", evidence.nodeId(),
                "type", evidence.nodeType(),
                "title", parts.length > 1 ? parts[1] : evidence.nodeId(),
                "text", evidence.chunkText(),
                "graph_path", evidence.graphPath() == null ? "" : evidence.graphPath(),
                "source_url", provenance.sourceUrl(),
                "source_status", provenance.sourceStatus(),
                "evidence_hash", provenance.evidenceHash());
    }

    private static int parseIndex(String digits) {
        try {
            return Math.subtractExact(Integer.parseInt(digits), 1);
        } catch (NumberFormatException | ArithmeticException ignored) {
            return -1;
        }
    }
}
