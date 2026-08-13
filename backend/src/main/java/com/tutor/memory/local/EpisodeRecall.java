package com.tutor.memory.local;

import com.tutor.llm.LlmGateway;
import com.tutor.memory.local.EpisodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * L2 情景记忆召回: 用当前问题的 embedding 在同一用户的 episodes 中找相关历史。
 * 召回失败只降级为空列表，不阻断当前回答。
 */
@Component
public class EpisodeRecall {
    private static final Logger log = LoggerFactory.getLogger(EpisodeRecall.class);
    private static final int TOP_K = 3;

    private final EpisodeStore store;
    private final LlmGateway gateway;

    public EpisodeRecall(EpisodeStore store, LlmGateway gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    public List<EpisodeStore.Episode> recall(long userId, String query, String traceId) {
        try {
            float[] queryEmbedding = gateway.embed("情景记忆检索: " + query, traceId);
            return store.searchByEmbedding(userId, queryEmbedding, TOP_K);
        } catch (RuntimeException e) {
            log.warn("情景记忆召回失败 user={} trace={}: {}", userId, traceId, e.getMessage());
            return List.of();
        }
    }

    public void deleteByUser(long userId) {
        store.deleteByUser(userId);
    }
}
