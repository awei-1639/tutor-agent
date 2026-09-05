package com.tutor.conversation.memory.local;

import com.tutor.contract.Purpose;
import com.tutor.llm.structured.FactExtractOutput;
import com.tutor.llm.structured.StructuredOutputResult;
import com.tutor.llm.structured.StructuredOutputService;
import com.tutor.llm.structured.StructuredTask;
import com.tutor.conversation.memory.policy.MemoryAdmissionPolicy;
import com.tutor.llm.LlmMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义事实写路径：Episode 提交成功后，从同一批已脱敏的用户消息窗口抽取原子事实，
 * 经准入边界后做确定性消解入库。失败仅记日志（铁律：记忆写入永不阻塞回答）。
 * 输入是 episode 源窗口的脱敏文本，而非原始对话——先过 EpisodeSummarizer 的同一条脱敏管线。
 */
@Component
public class FactExtractionService {
    private static final Logger log = LoggerFactory.getLogger(FactExtractionService.class);
    private static final double DEFAULT_CONFIDENCE = 0.6D;

    private static final String SYS = """
            你是用户画像事实抽取器。从对话中提取关于用户的、值得长期记住的原子事实，\
            输出 JSON {"facts":[{"text":"...","category":"...","confidence":0.0}]}：
            - 只提取用户明确、稳定的信息；category 取 goal(求职/学习目标)/preference(偏好)/\
            skill(技能水平)/constraint(时间等约束)/background(教育工作经验背景)
            - 每条事实是一句独立、第三人称、可长期复用的陈述（如"用户正在准备后端校招"），不超过80字
            - 不提取一次性提问、寒暄、临时情绪或对助手的要求；宁缺毋滥，最多8条
            - confidence 为 0~1 的确定性评分
            只输出 JSON。
            """;

    private final StructuredOutputService structuredOutputService;
    private final FactStore factStore;
    private final FactReconciler reconciler;
    private final MemoryAdmissionPolicy admission;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final int maxPerExtraction;

    @Autowired
    public FactExtractionService(StructuredOutputService structuredOutputService,
                                 FactStore factStore,
                                 FactReconciler reconciler,
                                 MemoryAdmissionPolicy admission,
                                 TransactionTemplate transactions,
                                 @Value("${memory.facts.enabled:true}") boolean enabled,
                                 @Value("${memory.facts.max-per-extraction:8}") int maxPerExtraction) {
        this.structuredOutputService = structuredOutputService;
        this.factStore = factStore;
        this.reconciler = reconciler;
        this.admission = admission;
        this.transactions = transactions;
        this.enabled = enabled;
        this.maxPerExtraction = Math.max(1, maxPerExtraction);
    }

    /**
     * @param maskedUserConversation 已由 PiiMasker 脱敏的源窗口用户消息拼接文本
     * @param sourceEpisodeId        事实来源 Episode；写入失败不影响 Episode 本身
     */
    public void extractFromWindow(long userId, long sourceEpisodeId, long expectedGeneration,
                                  String maskedUserConversation, String traceId) {
        if (!enabled) return;
        try {
            if (maskedUserConversation == null || maskedUserConversation.isBlank()) return;
            StructuredOutputResult<FactExtractOutput> structured = structuredOutputService.generate(
                    StructuredTask.FACT_EXTRACT,
                    Purpose.EXTRACT,
                    List.of(LlmMessage.system(SYS), LlmMessage.user(maskedUserConversation)),
                    FactExtractOutput.class,
                    output -> {
                        if (output.facts() == null) {
                            throw new IllegalArgumentException("facts array is required");
                        }
                    },
                    traceId
            );
            if (!structured.success()) return;

            List<FactExtractOutput.ExtractedFact> candidates = new ArrayList<>();
            for (FactExtractOutput.ExtractedFact fact : structured.value().facts()) {
                if (candidates.size() >= maxPerExtraction) break;
                if (fact == null || !admission.acceptsFact(fact.text() == null ? "" : fact.text().strip())) {
                    continue;
                }
                candidates.add(fact);
            }
            if (candidates.isEmpty()) return;

            transactions.executeWithoutResult(status -> reconciler.reconcile(
                    userId, expectedGeneration, sourceEpisodeId, candidates));
        } catch (Exception e) {
            log.error("事实抽取失败(不影响对话) user={} episode={} trace={}: {}",
                    userId, sourceEpisodeId, traceId, e.getMessage());
        }
    }
}
