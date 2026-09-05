package com.tutor.conversation.context.sections;

import com.tutor.conversation.context.ContextSection;
import com.tutor.conversation.context.TokenBudget;
import com.tutor.conversation.context.TurnContextView;
import com.tutor.conversation.memory.local.EpisodeStore;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** 区3: 跨会话情景记忆, 只作为个性化上下文，不作为知识证据。 */
@Component
@Order(3)
public class EpisodeSection implements ContextSection {

    @Override public String name() { return "episodes"; }
    @Override public int budget() { return 700; }

    @Override
    public String render(TurnContextView ctx, TokenBudget budget) {
        List<EpisodeStore.Episode> episodes = ctx.episodes();
        if (episodes == null || episodes.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n## 用户过去的相关经历（仅作个性化参考）\n");
        for (EpisodeStore.Episode episode : episodes) {
            sb.append("- ").append(episode.summary());
            if (episode.topics() != null && !episode.topics().isEmpty()) {
                sb.append("；主题: ").append(String.join("、", episode.topics()));
            }
            if (episode.openItems() != null && !episode.openItems().isEmpty()) {
                sb.append("；未完成: ").append(String.join("、", episode.openItems()));
            }
            sb.append('\n');
        }
        return budget.truncate(sb.toString(), budget());
    }
}
