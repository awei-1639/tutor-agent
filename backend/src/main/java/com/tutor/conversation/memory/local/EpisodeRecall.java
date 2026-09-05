package com.tutor.conversation.memory.local;

import com.tutor.llm.EmbeddingGateway;
import com.tutor.conversation.memory.local.EpisodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * L2 情景记忆召回: 用当前问题的 embedding 在同一用户的 episodes 中找相关历史。
 * 召回失败只降级为空列表，不阻断当前回答。
 */
@Component
public class EpisodeRecall {
    private static final Logger log = LoggerFactory.getLogger(EpisodeRecall.class);
    private static final int TOP_K = 3;

    private final EpisodeStore store;
    private final EmbeddingGateway gateway;

    public EpisodeRecall(EpisodeStore store, EmbeddingGateway gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    public List<EpisodeStore.Episode> recall(long userId, String query, String traceId) {
        try {
            float[] queryEmbedding = gateway.embedQuery("情景记忆检索: " + query, traceId);
            return store.searchByEmbedding(userId, queryEmbedding, TOP_K);
        } catch (RuntimeException e) {
            log.warn("情景记忆召回失败 user={} trace={}: {}", userId, traceId, e.getMessage());
            // Embedding 是增强能力，而非唯一的本地记忆路径。
            // embedding 服务不可用时，仍返回最近的有效 Episode。
            return store.recentByUser(userId, TOP_K);
        }
    }

    public void deleteByUser(long userId) {
        store.deleteByUser(userId);
    }

    public boolean isActiveById(long id, long userId) {
        return id > 0L && store.isActiveById(id, userId);
    }

    public Optional<String> remoteMemoryIdById(long id, long userId) {
        return store.remoteMemoryIdById(id, userId);
    }

    public void recordRemoteMemoryId(long id, long userId, String remoteMemoryId) {
        store.recordRemoteMemoryId(id, userId, remoteMemoryId);
    }

    public boolean deleteByIdForUser(long id, long userId) {
        return store.deleteByIdForUser(id, userId);
    }
}
