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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
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
        List<EpisodeStore.Episode> local;
        boolean localDegraded = false;
        try {
            local = safeEpisodes(localRecall.recall(userId, query, traceId));
        } catch (RuntimeException e) {
            // 记忆是增强能力；数据库、embedding 或连接池异常都不能阻断主对话。
            log.warn("本地情景记忆召回失败，继续无记忆对话 user={} trace={}: {}",
                    userId, traceId, e.getMessage());
            local = List.of();
            localDegraded = true;
        }

        try {
            if (!mem0.enabled() || !consent.enabledFor(userId)
                    || !outbox.remoteReadAllowed(userId) || !breaker.allowRequest()) {
                return new RecallResult(local, localDegraded);
            }
        } catch (RuntimeException e) {
            // 配置、授权存储或熔断器异常时安全关闭远程记忆。
            log.warn("远程记忆状态检查失败，继续本地记忆 user={} trace={}: {}",
                    userId, traceId, e.getMessage());
            return new RecallResult(local, true);
        }

        try {
            List<EpisodeStore.Episode> remote = safeEpisodes(mem0.search(userId, query, traceId)).stream()
                    .filter(episode -> {
                        if (episode.id() == 0L) return true;
                        if (!localRecall.isActiveById(episode.id(), userId)) return false;
                        if (episode.remoteMemoryId() != null) {
                            localRecall.recordRemoteMemoryId(episode.id(), userId, episode.remoteMemoryId());
                        }
                        return true;
                    })
                    .toList();
            breaker.success();
            return new RecallResult(merge(local, remote), localDegraded);
        } catch (RuntimeException e) {
            breaker.failure();
            log.warn("Mem0 召回失败，降级本地情节记忆 user={} trace={}: {}", userId, traceId, e.getMessage());
            return new RecallResult(local, true);
        }
    }

    private List<EpisodeStore.Episode> safeEpisodes(List<EpisodeStore.Episode> episodes) {
        if (episodes == null || episodes.isEmpty()) return List.of();
        return episodes.stream()
                .filter(episode -> episode != null
                        && episode.summary() != null
                        && !episode.summary().isBlank())
                .toList();
    }

    /**
     * 在 outbox worker 接收到已准入的结构化记忆记录前，刻意保持禁用。
     * 写入未经审查的问题/回答会让助手幻觉和提示注入变成持久化远端记忆。
     */
    public void remember(long userId, String question, String answer, String traceId) {
        log.debug("Mem0 对话双写已禁用，等待受控记忆同步 user={} trace={}", userId, traceId);
    }

    public ForgetResult forget(long userId) {
        // 删除操作应幂等。只要已配置 Mem0 就始终入队，使后一次清除可以取代
        // 较早的延迟删除，而不会让 worker 丢弃唯一的远端清理任务。
        boolean remoteDeletionRequired = mem0.enabled();
        transactions.executeWithoutResult(status -> {
            consent.invalidateMemoryGeneration(userId);
            localRecall.deleteByUser(userId);
            consent.setEnabled(userId, false);
            if (remoteDeletionRequired) outbox.enqueueDeleteUser(userId);
        });
        return new ForgetResult(remoteDeletionRequired);
    }

    /** 删除单条本地记忆，并为已知的 Mem0 副本登记精确删除事件。 */
    public boolean forgetOne(long userId, long memoryId) {
        Boolean deleted = transactions.execute(status -> {
            var remoteId = localRecall.remoteMemoryIdById(memoryId, userId);
            if (!localRecall.deleteByIdForUser(memoryId, userId)) return false;
            if (mem0.enabled()) {
                // UUID 尚未通过远程召回落库时也要登记事件，由 worker 做受限发现，避免删除窗口丢失。
                outbox.enqueueDeleteMemory(userId, memoryId, remoteId.orElse(null));
            }
            return true;
        });
        return Boolean.TRUE.equals(deleted);
    }

    /** 重新授权时先清理旧远程副本；清理完成前 recall 会保持本地回退。 */
    public void enableExternalMemory(long userId) {
        transactions.executeWithoutResult(status -> {
            consent.setEnabled(userId, true);
            if (mem0.enabled()) outbox.enqueueDeleteUser(userId);
        });
    }

    private static List<EpisodeStore.Episode> merge(List<EpisodeStore.Episode> local,
                                                     List<EpisodeStore.Episode> remote) {
        LinkedHashMap<String, EpisodeStore.Episode> unique = new LinkedHashMap<>();
        add(unique, local);
        add(unique, remote);
        return unique.values().stream()
                .sorted(Comparator.comparingDouble(LongTermMemoryService::rankScore).reversed())
                .limit(MAX_RECALL)
                .toList();
    }

    private static double rankScore(EpisodeStore.Episode episode) {
        double relevance = Double.isFinite(episode.relevance())
                ? Math.clamp(episode.relevance(), 0D, 1D) : 0D;
        double unresolvedBonus = episode.openItems() == null || episode.openItems().isEmpty() ? 0D : 0.05D;
        return relevance + unresolvedBonus;
    }

    private static void add(LinkedHashMap<String, EpisodeStore.Episode> target,
                            List<EpisodeStore.Episode> episodes) {
        if (episodes == null) return;
        for (EpisodeStore.Episode episode : episodes) {
            if (episode == null || episode.summary() == null || episode.summary().isBlank()) continue;
            String key = canonical(episode.summary());
            if (target.keySet().stream().noneMatch(existing -> nearDuplicate(existing, key))) {
                target.putIfAbsent(key, episode);
            }
        }
    }

    private static String canonical(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
    }

    /** 成本低且确定性的近重复防护；绝不调用 embedding 服务。 */
    private static boolean nearDuplicate(String left, String right) {
        if (left.equals(right)) return true;
        if (left.length() < 20 || right.length() < 20) return false;
        Set<String> a = bigrams(left);
        Set<String> b = bigrams(right);
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return !union.isEmpty() && (double) intersection.size() / union.size() >= 0.88;
    }

    private static Set<String> bigrams(String text) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 1 < text.length(); i++) result.add(text.substring(i, i + 2));
        return result;
    }
}
