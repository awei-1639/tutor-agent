package com.tutor.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.llm.JsonGenerationGateway;
import com.tutor.llm.structured.StructuredOutputService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Instant;

/**
 * L3 画像服务 (实现设计 2.3): 门控抽取 → 代码合并 → 审计 → 每日衰减。
 * 抽取异步执行, 永不阻塞回答 (可靠性铁律)。
 */
@Service
public class ProfileService {
    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);
    private final ProfileStore store;
    private final TransactionTemplate transactions;
    private final ProfileMessageExtractor messageExtractor;
    private final com.tutor.scheduling.ScheduledTaskLock taskLock;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProfileService(JsonGenerationGateway gateway, ProfileStore store) {
        this(gateway, store, null, new StructuredOutputService(gateway, null));
    }

    public ProfileService(JsonGenerationGateway gateway, ProfileStore store, TransactionTemplate transactions) {
        this(gateway, store, transactions, new StructuredOutputService(gateway, null));
    }

    public ProfileService(JsonGenerationGateway gateway, ProfileStore store, TransactionTemplate transactions,
                          StructuredOutputService structuredOutputService) {
        this(gateway, store, transactions, structuredOutputService, null);
    }

    @Autowired
    public ProfileService(JsonGenerationGateway gateway, ProfileStore store, TransactionTemplate transactions,
                          StructuredOutputService structuredOutputService,
                          com.tutor.scheduling.ScheduledTaskLock taskLock) {
        this.store = store;
        this.transactions = transactions;
        this.messageExtractor = new ProfileMessageExtractor(structuredOutputService);
        this.taskLock = taskLock;
    }

    /** 画像快照 (context 注入用) */
    public Map<String, Object> snapshot(long userId) {
        return store.snapshot(userId);
    }

    /** 回答完成后异步调用: 门控 → 抽取 → 合并 → 落库+审计。任何失败只记日志。 */
    public void updateFromMessage(long userId, String userMessage, String traceId) {
        updateFromMessage(userId, userMessage, traceId, Long.MIN_VALUE);
    }

    /** Fenced variant: queued extraction cannot publish after a memory clear. */
    public void updateFromMessage(long userId, String userMessage, String traceId, long expectedGeneration) {
        try {
            if (!ProfileMessageExtractor.eligible(userMessage)) return; // 门控未命中, 省一次LLM调用
            if (expectedGeneration != Long.MIN_VALUE && !store.generationCurrent(userId, expectedGeneration)) return;
            ExtractedDelta delta = messageExtractor.extract(userMessage, traceId);
            if (delta.isEmpty()) return;
            if (expectedGeneration != Long.MIN_VALUE && !store.generationCurrent(userId, expectedGeneration)) return;

            Map<String, Object> current = snapshot(userId);
            List<String> events = new ArrayList<>();
            Map<String, Object> next = ProfileMerger.merge(current, delta, events);
            if (events.isEmpty()) return;

            String eventJson = mapper.writeValueAsString(events);
            Boolean saved = transactions == null ? saveWithoutTransaction(userId, next, eventJson, traceId)
                    : expectedGeneration == Long.MIN_VALUE
                    ? transactions.execute(status -> {
                        store.save(userId, next);
                        store.insertEvent(userId, eventJson, "conversation", traceId);
                        return true;
                    })
                    : transactions.execute(status -> {
                        if (!store.saveIfGeneration(userId, expectedGeneration, next)) return false;
                        store.insertEvent(userId, eventJson, "conversation", traceId);
                        return true;
                    });
            if (!Boolean.TRUE.equals(saved)) return;
            log.info("画像更新 user={} events={} trace={}", userId, events, traceId);
        } catch (Exception e) {
            log.error("画像更新失败(不影响回答) user={} trace={}: {}", userId, traceId, e.getMessage());
        }
    }

    public void mergeResumeSkills(long userId, List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) return;
        try {
            ExtractedDelta delta = new ExtractedDelta(
                    skillNames.stream().map(n -> new ExtractedDelta.SkillDelta(n, true)).toList(),
                    Map.of(), List.of());
            List<String> events = new ArrayList<>();
            Map<String, Object> next = ProfileMerger.merge(snapshot(userId), delta, events);
            if (events.isEmpty()) return;
            store.save(userId, next);
            store.insertEvent(userId, mapper.writeValueAsString(events), "resume");
            log.info("简历技能回填画像 user={} events={}", userId, events.size());
        } catch (Exception e) {
            log.error("简历技能回填失败(不影响上传) user={}: {}", userId, e.getMessage());
        }
    }

    public Map<String, Object> confirmField(long userId, String field, boolean accept) {
        Map<String, Object> next = ProfileMerger.confirm(snapshot(userId), field, accept);
        store.save(userId, next);
        try {
            store.insertEvent(userId,
                    mapper.writeValueAsString(List.of(field + (accept ? " 确认生效" : " 拒绝变更"))), "confirm");
        } catch (Exception ignored) {}
        return next;
    }

    /** Explicit profile scope deletion; cross-session memory deletion does not imply this operation. */
    public void deleteByUser(long userId) {
        store.deleteByUser(userId);
    }

    /** 用户可见的画像变更账本。只返回本人事件，不暴露原始对话内容。 */
    public List<ProfileEvent> recentEvents(long userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return store.recentEvents(userId, safeLimit).stream()
                .map(row -> new ProfileEvent(row.id(), parseEventChanges(row.deltaJson()), row.trigger(),
                        row.createdAt(), row.traceId()))
                .toList();
    }

    public record ProfileEvent(long id, List<String> changes, String trigger,
                               Instant createdAt, String traceId) {}

    /** 每日4点: inferred 字段置信度衰减 (30天半衰期) */
    @Scheduled(cron = "0 0 4 * * *")
    public void dailyDecay() {
        // 多实例部署时，全量衰减每个触发窗口只应执行一次。无锁存储 (测试/单实例) 时直接执行。
        if (taskLock != null && !taskLock.tryAcquire("profile-daily-decay", 3600)) return;
        List<Long> userIds = store.userIds();
        for (long uid : userIds) {
            store.save(uid, ProfileMerger.decay(snapshot(uid)));
        }
        log.info("画像衰减任务完成: {} 个用户", userIds.size());
    }

    private boolean saveWithoutTransaction(long userId, Map<String, Object> next,
                                           String eventJson, String traceId) {
        store.save(userId, next);
        store.insertEvent(userId, eventJson, "conversation", traceId);
        return true;
    }

    private List<String> parseEventChanges(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("画像事件反序列化失败: {}", e.getMessage());
            return List.of("画像已更新");
        }
    }

}
