package com.tutor.context;

import com.tutor.contract.Evidence;
import com.tutor.memory.local.EpisodeStore;
import com.tutor.memory.local.FactStore;

import java.util.List;
import java.util.Map;

/** 分区渲染所需的只读上下文快照 */
public record TurnContextView(
        Map<String, Object> profile,      // L3 快照 (可为空Map)
        List<Evidence> evidences,         // RAG 融合结果
        String conversationSummary,       // 区5: 本会话早期轮次折叠摘要 (可为null)
        List<EpisodeStore.Episode> episodes, // 区3: 跨会话情景记忆 (可为空)
        List<FactStore.UserFact> facts    // 区2.5: 跨会话语义事实 (可为空)
) {
    public TurnContextView(Map<String, Object> profile, List<Evidence> evidences) {
        this(profile, evidences, null, List.of(), List.of());
    }

    public TurnContextView(Map<String, Object> profile, List<Evidence> evidences,
                          String conversationSummary) {
        this(profile, evidences, conversationSummary, List.of(), List.of());
    }

    public TurnContextView(Map<String, Object> profile, List<Evidence> evidences,
                          String conversationSummary, List<EpisodeStore.Episode> episodes) {
        this(profile, evidences, conversationSummary, episodes, List.of());
    }
}
