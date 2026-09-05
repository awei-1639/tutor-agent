package com.tutor.conversation.memory.application;

import com.tutor.conversation.memory.BigramSimilarity;
import com.tutor.conversation.memory.local.FactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 语义事实召回：事实总量小（每用户有上限），直接全量加载后按确定性评分排序。
 * 评分 = 0.5*问题包含度 + 0.3*置信度 + 0.2*recency。失败降级为空列表，不阻断回答。
 */
@Component
public class FactRecallService {
    private static final Logger log = LoggerFactory.getLogger(FactRecallService.class);
    private static final double OVERLAP_WEIGHT = 0.5D;
    private static final double CONFIDENCE_WEIGHT = 0.3D;
    private static final double RECENCY_WEIGHT = 0.2D;

    private final FactStore factStore;
    private final boolean enabled;
    private final int topK;
    private final int maxActivePerUser;
    private final double recencyDecayDays;

    public FactRecallService(FactStore factStore,
                             @Value("${memory.facts.enabled:true}") boolean enabled,
                             @Value("${memory.facts.recall-top-k:6}") int topK,
                             @Value("${memory.facts.max-active-per-user:60}") int maxActivePerUser,
                             @Value("${memory.recall.recency-decay-days:30}") double recencyDecayDays) {
        this.factStore = factStore;
        this.enabled = enabled;
        this.topK = Math.max(1, topK);
        this.maxActivePerUser = Math.max(1, maxActivePerUser);
        this.recencyDecayDays = Math.max(1D, recencyDecayDays);
    }

    public List<FactStore.UserFact> recall(long userId, String query, String traceId) {
        if (!enabled) return List.of();
        try {
            List<FactStore.UserFact> facts = factStore.activeByUser(userId, maxActivePerUser);
            if (facts.isEmpty()) return List.of();
            return facts.stream()
                    .sorted(Comparator.comparingDouble((FactStore.UserFact fact) ->
                                    score(fact, query)).reversed()
                            .thenComparing(fact -> fact.updatedAt(), Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(FactStore.UserFact::id))
                    .limit(topK)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("事实召回失败 user={} trace={}: {}", userId, traceId, e.getMessage());
            return List.of();
        }
    }

    private double score(FactStore.UserFact fact, String query) {
        return OVERLAP_WEIGHT * BigramSimilarity.containment(fact.factText(), query)
                + CONFIDENCE_WEIGHT * Math.clamp(fact.confidence(), 0D, 1D)
                + RECENCY_WEIGHT * recencyFactor(fact.updatedAt());
    }

    private double recencyFactor(Instant updatedAt) {
        if (updatedAt == null) return 1.0D;
        long ageDays = Math.max(0L, Duration.between(updatedAt, Instant.now()).toDays());
        return Math.exp(-(double) ageDays / recencyDecayDays);
    }
}
