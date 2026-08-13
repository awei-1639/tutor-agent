package com.tutor.memory.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.tutor.memory.external.Mem0CircuitBreaker;
import com.tutor.memory.external.Mem0Client;
import com.tutor.memory.local.EpisodeRecall;
import com.tutor.memory.local.EpisodeStore;
import com.tutor.memory.policy.MemoryConsentService;
import com.tutor.memory.external.MemorySyncOutbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 统一编排本地情节记忆与可选的 Mem0 长期记忆。
 * 远程记忆始终是增强项：故障、超时或配置缺失都不能阻断主对话。
 */
@Service
public class LongTermMemoryService {
    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);
    private static final int MAX_RECALL = 5;

    private final EpisodeRecall localRecall;
    private final Mem0Client mem0;
    private final MemoryConsentService consent;
    private final Mem0CircuitBreaker breaker;
    private final TransactionTemplate transactions;
    private final MemorySyncOutbox outbox;

    public LongTermMemoryService(EpisodeRecall localRecall, Mem0Client mem0,
                                 MemoryConsentService consent, Mem0CircuitBreaker breaker,
                                 TransactionTemplate transactions, MemorySyncOutbox outbox) {
        this.localRecall = localRecall;
        this.mem0 = mem0;
        this.consent = consent;
        this.breaker = breaker;
        this.transactions = transactions;
        this.outbox = outbox;
    }

    public record RecallResult(List<EpisodeStore.Episode> episodes, boolean degraded) {}
    public record ForgetResult(boolean remoteDeletionPending) {}

    public RecallResult recall(long userId, String query, String traceId) {
        List<EpisodeStore.Episode> local = localRecall.recall(userId, query, traceId);
        if (!mem0.enabled() || !consent.enabledFor(userId) || !breaker.allowRequest()) {
            return new RecallResult(local, false);
        }

        try {
            List<EpisodeStore.Episode> remote = mem0.search(userId, query, traceId);
            breaker.success();
            return new RecallResult(merge(local, remote), false);
        } catch (RuntimeException e) {
            breaker.failure();
            log.warn("Mem0 召回失败，降级本地情节记忆 user={} trace={}: {}", userId, traceId, e.getMessage());
            return new RecallResult(local, true);
        }
    }

    /**
     * Deliberately disabled until the outbox worker receives admitted, structured
     * memory records.  Sending an unreviewed question/answer pair lets assistant
     * hallucinations and prompt injection become durable remote memories.
     */
    public void remember(long userId, String question, String answer, String traceId) {
        log.debug("Mem0 对话双写已禁用，等待受控记忆同步 user={} trace={}", userId, traceId);
    }

    public ForgetResult forget(long userId) {
        boolean remoteDeletionRequired = mem0.enabled() && consent.enabledFor(userId);
        transactions.executeWithoutResult(status -> {
            consent.invalidateMemoryGeneration(userId);
            localRecall.deleteByUser(userId);
            consent.setEnabled(userId, false);
            if (remoteDeletionRequired) outbox.enqueueDeleteUser(userId);
        });
        return new ForgetResult(remoteDeletionRequired);
    }

    private static List<EpisodeStore.Episode> merge(List<EpisodeStore.Episode> local,
                                                     List<EpisodeStore.Episode> remote) {
        LinkedHashMap<String, EpisodeStore.Episode> unique = new LinkedHashMap<>();
        add(unique, local);
        add(unique, remote);
        return new ArrayList<>(unique.values()).subList(0, Math.min(MAX_RECALL, unique.size()));
    }

    private static void add(LinkedHashMap<String, EpisodeStore.Episode> target,
                            List<EpisodeStore.Episode> episodes) {
        if (episodes == null) return;
        for (EpisodeStore.Episode episode : episodes) {
            if (episode == null || episode.summary() == null || episode.summary().isBlank()) continue;
            String key = episode.summary().trim().toLowerCase(Locale.ROOT);
            target.putIfAbsent(key, episode);
        }
    }
}
