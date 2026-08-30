package com.tutor.context.sections;

import com.tutor.context.ContextSection;
import com.tutor.context.TokenBudget;
import com.tutor.context.TurnContextView;
import com.tutor.memory.local.FactStore;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** 区2.5: 用户长期语义事实（目标/偏好/技能等），比情景摘要更稳定、优先遵守。 */
@Component
@Order(6)
public class FactsSection implements ContextSection {

    @Override public String name() { return "facts"; }
    @Override public int budget() { return 300; }

    @Override
    public String render(TurnContextView ctx, TokenBudget budget) {
        List<FactStore.UserFact> facts = ctx.facts();
        if (facts == null || facts.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n## 关于用户的长期事实（稳定目标与偏好，回答时优先遵守）\n");
        for (FactStore.UserFact fact : facts) {
            sb.append("- ").append(fact.factText()).append('\n');
        }
        return budget.truncate(sb.toString(), budget());
    }
}
