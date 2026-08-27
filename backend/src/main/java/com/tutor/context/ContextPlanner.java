package com.tutor.context;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 系统上下文分配的唯一所有者；网关不得悄然调整这些优先级。 */
@Component
public class ContextPlanner {
    static final int SYSTEM_CONTEXT_BUDGET = 2_600;

    public record Allocation(String name, int originalTokens, int allocatedTokens, boolean dropped) {}
    public record Plan(String prompt, List<Allocation> allocations, Set<String> citationIds) {
        public Plan {
            citationIds = citationIds == null ? Set.of() : Set.copyOf(citationIds);
        }
    }

    public Plan plan(List<ContextSection> sections, TurnContextView context, TokenBudget budget) {
        record Candidate(ContextSection section, ContextSection.Rendered rendered, int tokens) {}
        List<Candidate> candidates = sections.stream()
                .map(section -> {
                    ContextSection.Rendered rendered = section.renderWithMetadata(context, budget);
                    return new Candidate(section, rendered, budget.count(rendered.text()));
                })
                .filter(candidate -> !candidate.rendered().text().isBlank())
                .sorted(Comparator.comparingInt(candidate -> priority(candidate.section().name())))
                .toList();
        int remaining = SYSTEM_CONTEXT_BUDGET;
        Map<Candidate, Integer> plannedAllocations = new LinkedHashMap<>();

        // 先给关键分区保留最低预算，避免超长证据把用户画像、摘要全部挤掉。
        for (Candidate candidate : candidates) {
            int capped = Math.min(candidate.tokens(), candidate.section().budget());
            int floor = Math.min(capped, minimumAllocation(candidate.section().name()));
            int allocated = Math.min(floor, remaining);
            plannedAllocations.put(candidate, allocated);
            remaining -= allocated;
        }
        // 再按原有优先级分配剩余预算，保持规则和证据优先，同时利用空闲空间。
        for (Candidate candidate : candidates) {
            int capped = Math.min(candidate.tokens(), candidate.section().budget());
            int current = plannedAllocations.getOrDefault(candidate, 0);
            int extra = Math.min(Math.max(0, capped - current), remaining);
            plannedAllocations.put(candidate, current + extra);
            remaining -= extra;
        }

        StringBuilder prompt = new StringBuilder();
        java.util.ArrayList<Allocation> allocations = new java.util.ArrayList<>();
        Set<String> citationIds = new LinkedHashSet<>();
        // 分配顺序按优先级 (保证证据等关键分区优先拿预算)，但物理输出顺序按前缀稳定性排列：
        // 静态/低频变化分区在前、每轮变化的证据在最后，最大化 DeepSeek 自动 prefix 缓存命中。
        // 两者解耦，避免把证据挪到末尾时连带削减它的预算而伤检索质量。
        List<Candidate> outputOrder = candidates.stream()
                .sorted(Comparator.comparingInt(candidate -> outputRank(candidate.section().name())))
                .toList();
        for (Candidate candidate : outputOrder) {
            int allocated = plannedAllocations.getOrDefault(candidate, 0);
            String text = allocated == 0 ? "" : budget.truncate(candidate.rendered().text(), allocated);
            prompt.append(text);
            int retainedPrefixLength = text.endsWith("…") ? text.length() - 1 : text.length();
            for (ContextSection.CitationMarker marker : candidate.rendered().citationMarkers()) {
                if (marker.endOffset() <= retainedPrefixLength) citationIds.add(marker.id());
            }
        }
        // 分配明细仍按预算优先级列出，便于观测哪些分区被裁剪。
        for (Candidate candidate : candidates) {
            int allocated = plannedAllocations.getOrDefault(candidate, 0);
            allocations.add(new Allocation(candidate.section().name(), candidate.tokens(), allocated, allocated == 0));
        }
        return new Plan(prompt.toString(), List.copyOf(allocations), citationIds);
    }

    private int minimumAllocation(String section) {
        return switch (section) {
            case "rules" -> 200;
            case "evidence" -> 900;
            case "profile" -> 300;
            case "episodes", "summary" -> 200;
            default -> 0;
        };
    }

    private int priority(String section) {
        return switch (section) {
            case "rules" -> 0;
            case "evidence" -> 1;
            case "profile" -> 2;
            case "episodes" -> 3;
            case "summary" -> 4;
            default -> 5;
        };
    }

    /**
     * 物理输出顺序：按前缀稳定性排列，越稳定越靠前。规则完全静态；画像/情景/摘要按会话缓慢变化；
     * 证据每轮随 query 变化，排到最后，使其之前的前缀在同一会话内可被 DeepSeek 自动缓存命中。
     */
    private int outputRank(String section) {
        return switch (section) {
            case "rules" -> 0;
            case "profile" -> 1;
            case "episodes" -> 2;
            case "summary" -> 3;
            case "evidence" -> 4;
            default -> 5;
        };
    }
}
