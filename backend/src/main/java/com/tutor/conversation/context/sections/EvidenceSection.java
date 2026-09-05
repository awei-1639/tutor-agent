package com.tutor.conversation.context.sections;

import com.tutor.conversation.context.ContextSection;
import com.tutor.conversation.context.TokenBudget;
import com.tutor.conversation.context.TurnContextView;
import com.tutor.contract.Evidence;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;

/** 区4: 编号知识证据块 [S#]。超限按融合分从低到高裁剪 (证据已按分排序, 截尾即可)。 */
@Component
@Order(4)
public class EvidenceSection implements ContextSection {

    @Override public String name() { return "evidence"; }
    @Override public int budget() { return 2500; }

    @Override
    public String render(TurnContextView ctx, TokenBudget budget) {
        return renderWithMetadata(ctx, budget).text();
    }

    @Override
    public ContextSection.Rendered renderWithMetadata(TurnContextView ctx, TokenBudget budget) {
        List<Evidence> evidences = ctx.evidences();
        if (evidences == null || evidences.isEmpty()) {
            return new ContextSection.Rendered("\n## 知识证据\n(本轮未检索到相关证据)\n", List.of());
        }
        StringBuilder sb = new StringBuilder("\n## 知识证据\n");
        List<ContextSection.CitationMarker> markers = new ArrayList<>();
        for (int i = 0; i < evidences.size(); i++) {
            Evidence e = evidences.get(i);
            if (e == null || e.chunkText() == null || e.chunkText().isBlank()) continue;
            String line = "[S" + (i + 1) + "] " + e.chunkText()
                    + (e.graphPath() != null ? " (图谱关联: " + e.graphPath() + ")" : "") + "\n";
            if (budget.count(sb.toString() + line) > budget()) break; // 低分证据被截尾
            sb.append(line);
            markers.add(new ContextSection.CitationMarker("S" + (i + 1), sb.length()));
        }
        return new ContextSection.Rendered(sb.toString(), markers);
    }

    /**
     * 计划器收紧预算时按整条证据驱逐 (保留尽量多的完整低分块)，而不是句中截断：
     * 证据块是引用的最小语义单元，句中截断会让 [S#] 指向被腰斩的内容。
     */
    @Override
    public String fit(String text, int maxTokens, TokenBudget budget) {
        if (text == null || text.isEmpty() || maxTokens <= 0) return "";
        if (budget.count(text) <= maxTokens) return text;
        int cut = lastFittingLineBreak(text, maxTokens, budget);
        String fitted = cut > 0 ? text.substring(0, cut) : "";
        return fitted.isBlank() ? "" : fitted;
    }

    /** 在 token 上限内找到最靠后的换行边界；找不到任何完整行时返回 0。 */
    private int lastFittingLineBreak(String text, int maxTokens, TokenBudget budget) {
        int best = 0;
        int index = text.indexOf('\n');
        while (index >= 0) {
            if (budget.count(text.substring(0, index + 1)) <= maxTokens) {
                best = index + 1;
            } else {
                break;
            }
            index = text.indexOf('\n', index + 1);
        }
        return best;
    }
}
