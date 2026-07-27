package com.tutor.context.sections;

import com.tutor.context.ContextSection;
import com.tutor.context.TokenBudget;
import com.tutor.context.TurnContextView;
import com.tutor.contract.Evidence;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** 区4: 编号知识证据块 [S#]。超限按融合分从低到高裁剪 (证据已按分排序, 截尾即可)。 */
@Component
@Order(4)
public class EvidenceSection implements ContextSection {

    @Override public String name() { return "evidence"; }
    @Override public int budget() { return 2500; }

    @Override
    public String render(TurnContextView ctx, TokenBudget budget) {
        List<Evidence> evidences = ctx.evidences();
        if (evidences == null || evidences.isEmpty()) return "\n## 知识证据\n(本轮未检索到相关证据)\n";
        StringBuilder sb = new StringBuilder("\n## 知识证据\n");
        for (int i = 0; i < evidences.size(); i++) {
            Evidence e = evidences.get(i);
            String line = "[S" + (i + 1) + "] " + e.chunkText()
                    + (e.graphPath() != null ? " (图谱关联: " + e.graphPath() + ")" : "") + "\n";
            if (budget.count(sb.toString() + line) > budget()) break; // 低分证据被截尾
            sb.append(line);
        }
        return sb.toString();
    }
}
