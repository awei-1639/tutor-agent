package com.tutor.conversation.memory.local;

import com.tutor.platform.llm.structured.FactExtractOutput;
import com.tutor.conversation.memory.BigramSimilarity;
import com.tutor.conversation.memory.policy.MemoryAdmissionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 确定性事实消解：mem0 用 LLM 对候选事实做 ADD/UPDATE/DELETE/NOOP 判定，
 * 这里刻意改为纯规则（同类目 + bigram 阈值），保证可单测、可复现、零额外 LLM 成本；
 * 是否需要 LLM 兜底由记忆评测（evals/run_memory_eval.mjs）的失效泄漏率指标决定。
 * 新事实胜旧事实：事实按时间序写入，最新陈述代表用户当前状态。
 */
@Component
public class FactReconciler {
    private static final Logger log = LoggerFactory.getLogger(FactReconciler.class);
    static final double DUPLICATE_THRESHOLD = 0.88;
    static final double SUPERSEDE_THRESHOLD = 0.60;
    private static final int CANDIDATE_LIMIT = 30;

    private final FactStore factStore;
    private final MemoryAdmissionPolicy admission;

    public FactReconciler(FactStore factStore, MemoryAdmissionPolicy admission) {
        this.factStore = factStore;
        this.admission = admission;
    }

    public record ReconcileResult(int added, int duplicates, int superseded) {
        public static final ReconcileResult EMPTY = new ReconcileResult(0, 0, 0);
    }

    /**
     * 在调用方事务内执行。候选事实必须已通过准入；此处再做一次校验属纵深防御。
     * 全程以 expectedGeneration 做 fencing：用户清除记忆后，插入与失效都不会生效。
     */
    public ReconcileResult reconcile(long userId, long expectedGeneration, Long sourceEpisodeId,
                                     List<FactExtractOutput.ExtractedFact> candidates) {
        if (candidates == null || candidates.isEmpty()) return ReconcileResult.EMPTY;
        int added = 0;
        int duplicates = 0;
        int superseded = 0;
        for (FactExtractOutput.ExtractedFact candidate : candidates) {
            String text = candidate.text() == null ? "" : candidate.text().strip();
            if (text.isEmpty() || !admission.acceptsFact(text)) continue;
            String category = FactStore.normalizeCategory(candidate.category());

            FactStore.UserFact bestMatch = bestMatch(userId, category, text);
            double similarity = bestMatch == null ? 0D
                    : BigramSimilarity.similarity(text, bestMatch.factText());
            if (similarity >= DUPLICATE_THRESHOLD) {
                duplicates++;
                continue;
            }
            long newId = factStore.insertIfAbsentReturningId(userId, sourceEpisodeId,
                    expectedGeneration, text, category,
                    candidate.confidence() == null ? 0.6D : candidate.confidence());
            if (newId == 0L) continue;
            added++;
            if (bestMatch != null && similarity >= SUPERSEDE_THRESHOLD
                    && factStore.markSuperseded(userId, bestMatch.id(), newId, expectedGeneration)) {
                superseded++;
            }
        }
        if (added > 0 || superseded > 0) {
            log.info("事实消解完成 user={} episode={} added={} superseded={} duplicates={}",
                    userId, sourceEpisodeId, added, superseded, duplicates);
        }
        return new ReconcileResult(added, duplicates, superseded);
    }

    private FactStore.UserFact bestMatch(long userId, String category, String text) {
        List<FactStore.UserFact> existing = factStore.activeByUserAndCategory(userId, category, CANDIDATE_LIMIT);
        FactStore.UserFact best = null;
        double bestScore = 0D;
        for (FactStore.UserFact fact : existing) {
            double score = BigramSimilarity.similarity(text, fact.factText());
            if (score > bestScore) {
                bestScore = score;
                best = fact;
            }
        }
        return best;
    }
}
