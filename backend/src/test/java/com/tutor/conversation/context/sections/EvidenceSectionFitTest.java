package com.tutor.conversation.context.sections;

import com.tutor.conversation.context.ContextPlanner;
import com.tutor.conversation.context.ContextSection;
import com.tutor.conversation.context.TokenBudget;
import com.tutor.conversation.context.TurnContextView;
import com.tutor.contract.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceSectionFitTest {
    private final TokenBudget budget = new TokenBudget();
    private final EvidenceSection section = new EvidenceSection();

    @Test
    void fitKeepsWholeEvidenceLinesInsteadOfCuttingMidSentence() {
        String line1 = "[S1] " + "a".repeat(80) + "\n";
        String line2 = "[S2] " + "b".repeat(80) + "\n";
        String text = "\n## 知识证据\n" + line1 + line2;

        // 上限恰好容纳标题 + 第一行：第二行必须整行让出，而不是把 [S2] 砍半。
        int maxTokens = budget.count("\n## 知识证据\n" + line1) + 1;

        String fitted = section.fit(text, maxTokens, budget);

        assertThat(fitted).hasSize(("\n## 知识证据\n" + line1).length());
        assertThat(fitted).contains("[S1]").doesNotContain("[S2]");
    }

    @Test
    void fitDropsEverythingWhenEvenOneLineDoesNotFit() {
        String text = "\n## 知识证据\n[S1] " + "a".repeat(200) + "\n";
        assertThat(section.fit(text, 1, budget)).isEmpty();
    }

    @Test
    void plannerUsesChunkAwareFitSoCitationMarkersStayComplete() {
        // 重复字符会被 BPE 高度压缩，因此用自然句子循环拼接，按 jtokkit 实测密度构造体积。
        String chunk1 = "a".repeat(120);
        StringBuilder big = new StringBuilder();
        String evidenceSentence = "学习计划需要结合岗位要求与当前技能水平逐步推进。";
        while (budget.count(big.toString()) < 1_200) big.append(evidenceSentence);
        Evidence tight = new Evidence("n1", "skill", chunk1, 0.9, null, null, null, null);
        Evidence overflow = new Evidence("n2", "skill", big.toString(), 0.5, null, null, null, null);
        EvidenceSection evidenceSection = new EvidenceSection();
        // 高优先级静态区占走大头预算，迫使证据区被压到保底 900 token 附近。
        StringBuilder ruleText = new StringBuilder("\n## 规则\n");
        String ruleSentence = "回答必须基于证据并保持结构清晰，遇到冲突信息时说明取舍理由。";
        while (budget.count(ruleText.toString()) < 2_400) ruleText.append(ruleSentence);
        ContextSection rules = new ContextSection() {
            @Override public String name() { return "rules"; }
            @Override public int budget() { return 2_500; }
            @Override public String render(TurnContextView ctx, TokenBudget budget) {
                return ruleText.append('\n').toString();
            }
        };
        ContextPlanner planner = new ContextPlanner();

        ContextPlanner.Plan plan = planner.plan(List.of(rules, evidenceSection),
                new TurnContextView(Map.of(), List.of(tight, overflow)), budget);

        // 计划器把证据区压到分配额内时，保留的引用必须是完整块，句中不出现省略号。
        // (rules 区被压到 1700 token 走默认截断带省略号是预期行为，断言只看证据区。)
        String evidencePart = plan.prompt().substring(plan.prompt().indexOf("## 知识证据"));
        assertThat(evidencePart).contains("[S1]").doesNotContain("[S2]").doesNotContain("…");
        assertThat(plan.citationIds()).containsExactly("S1");
    }
}
