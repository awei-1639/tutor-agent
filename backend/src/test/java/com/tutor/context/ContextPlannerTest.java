package com.tutor.context;

import com.tutor.contract.Evidence;
import com.tutor.context.sections.EvidenceSection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextPlannerTest {
    @Test
    void retainsRulesAndEvidenceBeforeLowerPriorityMemory() {
        TokenBudget budget = new TokenBudget();
        ContextSection rules = section("rules", 300, "RULE ".repeat(100));
        ContextSection summary = section("summary", 800, "SUMMARY ".repeat(1_000));
        ContextSection evidence = section("evidence", 2_500, "EVIDENCE ".repeat(1_000));
        ContextPlanner.Plan plan = new ContextPlanner().plan(List.of(summary, evidence, rules),
                new TurnContextView(Map.of(), List.<Evidence>of()), budget);

        assertThat(plan.prompt()).startsWith("RULE").contains("EVIDENCE");
        assertThat(plan.allocations()).extracting(ContextPlanner.Allocation::name)
                .containsExactly("rules", "evidence", "summary");
    }

    @Test
    void exposesOnlyCitationIdsWhoseCompleteEvidenceSurvivesTruncation() {
        TokenBudget budget = new TokenBudget();
        List<Evidence> evidences = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> new Evidence("skill:" + i, "skill",
                        ("证据文本" + i).repeat(120), 1.0 - i / 100.0, null, null, null, null))
                .toList();

        ContextPlanner.Plan plan = new ContextPlanner().plan(
                List.of(new EvidenceSection()), new TurnContextView(Map.of(), evidences), budget);

        assertThat(plan.citationIds()).isNotEmpty().contains("S1").doesNotContain("S10");
        assertThat(plan.citationIds()).allMatch(id -> plan.prompt().contains("[" + id + "]"));
        assertThat(plan.prompt()).doesNotContain("[S10]");
    }

    @Test
    void reservesProfileBudgetWhenEvidenceIsVeryLarge() {
        TokenBudget budget = new TokenBudget();
        ContextSection rules = section("rules", 300, "RULE ".repeat(100));
        ContextSection evidence = section("evidence", 2_500, "EVIDENCE ".repeat(2_000));
        ContextSection profile = section("profile", 500, "PROFILE ".repeat(300));

        ContextPlanner.Plan plan = new ContextPlanner().plan(List.of(evidence, profile, rules),
                new TurnContextView(Map.of(), List.<Evidence>of()), budget);

        assertThat(plan.allocations().stream()
                .filter(allocation -> allocation.name().equals("profile"))
                .findFirst().orElseThrow().allocatedTokens()).isGreaterThan(0);
        assertThat(plan.prompt()).contains("PROFILE");
    }

    @Test
    void ordersStablePrefixSectionsBeforeEvidenceForPromptCaching() {
        TokenBudget budget = new TokenBudget();
        ContextSection rules = section("rules", 300, "RULE ".repeat(20));
        ContextSection profile = section("profile", 500, "PROFILE ".repeat(20));
        ContextSection summary = section("summary", 800, "SUMMARY ".repeat(20));
        ContextSection evidence = section("evidence", 2_500, "EVIDENCE ".repeat(20));

        ContextPlanner.Plan plan = new ContextPlanner().plan(List.of(evidence, summary, profile, rules),
                new TurnContextView(Map.of(), List.<Evidence>of()), budget);

        String prompt = plan.prompt();
        // 稳定前缀 (规则/画像/摘要) 必须排在每轮变化的证据之前，证据置于末尾以命中 prefix 缓存。
        assertThat(prompt.indexOf("RULE")).isLessThan(prompt.indexOf("PROFILE"));
        assertThat(prompt.indexOf("PROFILE")).isLessThan(prompt.indexOf("SUMMARY"));
        assertThat(prompt.indexOf("SUMMARY")).isLessThan(prompt.indexOf("EVIDENCE"));
    }

    private ContextSection section(String name, int max, String text) {
        return new ContextSection() {
            @Override public String name() { return name; }
            @Override public int budget() { return max; }
            @Override public String render(TurnContextView context, TokenBudget budget) { return text; }
        };
    }
}
