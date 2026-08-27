package com.tutor.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Purpose;
import com.tutor.llm.JsonGenerationGateway;
import com.tutor.llm.structured.ProfileExtractOutput;
import com.tutor.llm.structured.StructuredOutputResult;
import com.tutor.llm.structured.StructuredOutputService;
import com.tutor.llm.structured.StructuredTask;
import com.tutor.resume.PiiMasker;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * L3 画像服务 (实现设计 2.3): 门控抽取 → 代码合并 → 审计 → 每日衰减。
 * 抽取异步执行, 永不阻塞回答 (可靠性铁律)。
 */
@Service
public class ProfileService {
    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);
    /** 抽取门控: 第一人称 + 个人信息信号词, 命中才调LLM (约省70%轮次) */
    private static final Pattern GATE = Pattern.compile(
            "(我|本人|自己).*(会|学过|熟悉|掌握|懂|经验|年|应届|毕业|本科|硕士|大专|专科|转行|目标|想做|想当|想转|求职|城市|基地|每天|小时|偏好|喜欢|擅长)|零基础|应届生");

    private static final String EXTRACT_SYS = """
            你是画像信息抽取器。从用户消息中抽取求职学习相关的个人信息, 输出严格JSON:
            {"skills":[{"name":"技能名","explicit":true|false}],
             "scalars":{"target_position":{"value":"..","explicit":..}|null,"location":{..}|null,
                        "experience_years":{..}|null,"education":{..}|null,"daily_hours":{..}|null},
             "preferred_format":["视频","实战项目"...]}
            规则: explicit=用户明确陈述自己的事实("我会Java"); inferred=从语气推断("这个Python代码怎么改"暗示会Python)。
            只抽取消息中真实存在的信息, 没有就给空数组/null。禁止编造。技能名用通用中文名。
            """;

    private final JsonGenerationGateway gateway;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final StructuredOutputService structuredOutputService;
    private final com.tutor.scheduling.ScheduledTaskLock taskLock;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProfileService(JsonGenerationGateway gateway, JdbcTemplate jdbc) {
        this(gateway, jdbc, null, new StructuredOutputService(gateway, null));
    }

    public ProfileService(JsonGenerationGateway gateway, JdbcTemplate jdbc, TransactionTemplate transactions) {
        this(gateway, jdbc, transactions, new StructuredOutputService(gateway, null));
    }

    public ProfileService(JsonGenerationGateway gateway, JdbcTemplate jdbc, TransactionTemplate transactions,
                          StructuredOutputService structuredOutputService) {
        this(gateway, jdbc, transactions, structuredOutputService, null);
    }

    @Autowired
    public ProfileService(JsonGenerationGateway gateway, JdbcTemplate jdbc, TransactionTemplate transactions,
                          StructuredOutputService structuredOutputService,
                          com.tutor.scheduling.ScheduledTaskLock taskLock) {
        this.gateway = gateway;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.structuredOutputService = structuredOutputService;
        this.taskLock = taskLock;
    }

    /** 画像快照 (context 注入用) */
    public Map<String, Object> snapshot(long userId) {
        List<String> rows = jdbc.query("SELECT data::text FROM profiles WHERE user_id=?",
                (rs, i) -> rs.getString(1), userId);
        if (rows.isEmpty()) {
            return Map.of();
        }
        try {
            return mapper.readValue(rows.get(0), new TypeReference<>() {});
        } catch (Exception e) {
            log.error("画像反序列化失败 user={}", userId, e);
            return Map.of();
        }
    }

    /** 回答完成后异步调用: 门控 → 抽取 → 合并 → 落库+审计。任何失败只记日志。 */
    public void updateFromMessage(long userId, String userMessage, String traceId) {
        updateFromMessage(userId, userMessage, traceId, Long.MIN_VALUE);
    }

    /** Fenced variant: queued extraction cannot publish after a memory clear. */
    public void updateFromMessage(long userId, String userMessage, String traceId, long expectedGeneration) {
        try {
            if (!GATE.matcher(userMessage).find()) return; // 门控未命中, 省一次LLM调用
            if (expectedGeneration != Long.MIN_VALUE && !generationCurrent(userId, expectedGeneration)) return;
            String safeMessage = PiiMasker.mask(userMessage).masked();
            StructuredOutputResult<ProfileExtractOutput> structured = structuredOutputService.generate(
                    StructuredTask.PROFILE_EXTRACT,
                    Purpose.EXTRACT,
                    List.of(
                            SystemMessage.from(EXTRACT_SYS),
                            UserMessage.from("用户消息: " + safeMessage)),
                    ProfileExtractOutput.class,
                    this::validateProfileOutput,
                    traceId
            );
            if (!structured.success()) return;
            ExtractedDelta delta = sanitizeDelta(toDelta(structured.value()));
            if (delta.isEmpty()) return;
            if (expectedGeneration != Long.MIN_VALUE && !generationCurrent(userId, expectedGeneration)) return;

            Map<String, Object> current = snapshot(userId);
            List<String> events = new ArrayList<>();
            Map<String, Object> next = ProfileMerger.merge(current, delta, events);
            if (events.isEmpty()) return;

            String eventJson = mapper.writeValueAsString(events);
            Boolean saved = transactions == null ? saveWithoutTransaction(userId, next, eventJson, traceId)
                    : expectedGeneration == Long.MIN_VALUE
                    ? transactions.execute(status -> {
                        saveProfile(userId, next);
                        jdbc.update("INSERT INTO profile_events (user_id, delta, trigger, trace_id) VALUES (?, ?::jsonb, ?, ?)",
                                userId, eventJson, "conversation", traceId);
                        return true;
                    })
                    : transactions.execute(status -> {
                        if (!saveProfileIfGeneration(userId, expectedGeneration, next)) return false;
                        jdbc.update("INSERT INTO profile_events (user_id, delta, trigger, trace_id) VALUES (?, ?::jsonb, ?, ?)",
                                userId, eventJson, "conversation", traceId);
                        return true;
                    });
            if (!Boolean.TRUE.equals(saved)) return;
            log.info("画像更新 user={} events={} trace={}", userId, events, traceId);
        } catch (Exception e) {
            log.error("画像更新失败(不影响回答) user={} trace={}: {}", userId, traceId, e.getMessage());
        }
    }

    private void validateProfileOutput(ProfileExtractOutput output) {
        if (output.skills() == null || output.scalars() == null || output.preferredFormat() == null) {
            throw new IllegalArgumentException("profile extraction contains null collections");
        }
    }

    private ExtractedDelta toDelta(ProfileExtractOutput output) {
        List<ExtractedDelta.SkillDelta> skills = output.skills().stream()
                .map(skill -> new ExtractedDelta.SkillDelta(skill.name(), skill.explicit()))
                .toList();
        Map<String, ExtractedDelta.ScalarDelta> scalars = new HashMap<>();
        output.scalars().forEach((field, scalar) -> {
            if (scalar != null) {
                scalars.put(field, new ExtractedDelta.ScalarDelta(scalar.value(), scalar.explicit()));
            }
        });
        return new ExtractedDelta(skills, scalars, output.preferredFormat());
    }

    /** 简历技能回填画像: 用户亲手写的文档 → explicit来源 (触发器=resume) */
    public void mergeResumeSkills(long userId, List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) return;
        try {
            ExtractedDelta delta = new ExtractedDelta(
                    skillNames.stream().map(n -> new ExtractedDelta.SkillDelta(n, true)).toList(),
                    Map.of(), List.of());
            List<String> events = new ArrayList<>();
            Map<String, Object> next = ProfileMerger.merge(snapshot(userId), delta, events);
            if (events.isEmpty()) return;
            saveProfile(userId, next);
            jdbc.update("INSERT INTO profile_events (user_id, delta, trigger) VALUES (?, ?::jsonb, ?)",
                    userId, mapper.writeValueAsString(events), "resume");
            log.info("简历技能回填画像 user={} events={}", userId, events.size());
        } catch (Exception e) {
            log.error("简历技能回填失败(不影响上传) user={}: {}", userId, e.getMessage());
        }
    }

    public Map<String, Object> confirmField(long userId, String field, boolean accept) {
        Map<String, Object> next = ProfileMerger.confirm(snapshot(userId), field, accept);
        saveProfile(userId, next);
        try {
            jdbc.update("INSERT INTO profile_events (user_id, delta, trigger) VALUES (?, ?::jsonb, ?)",
                    userId, mapper.writeValueAsString(List.of(field + (accept ? " 确认生效" : " 拒绝变更"))), "confirm");
        } catch (Exception ignored) {}
        return next;
    }

    /** Explicit profile scope deletion; cross-session memory deletion does not imply this operation. */
    public void deleteByUser(long userId) {
        jdbc.update("DELETE FROM profile_events WHERE user_id=?", userId);
        jdbc.update("DELETE FROM profiles WHERE user_id=?", userId);
    }

    /** 用户可见的画像变更账本。只返回本人事件，不暴露原始对话内容。 */
    public List<ProfileEvent> recentEvents(long userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return jdbc.query("""
                SELECT id, delta::text, trigger, created_at, trace_id
                FROM profile_events WHERE user_id=?
                ORDER BY id DESC LIMIT ?
                """, (rs, i) -> new ProfileEvent(
                rs.getLong(1), parseEventChanges(rs.getString(2)), rs.getString(3),
                rs.getTimestamp(4).toInstant(), rs.getString(5)), userId, safeLimit);
    }

    public record ProfileEvent(long id, List<String> changes, String trigger,
                               Instant createdAt, String traceId) {}

    /** 每日4点: inferred 字段置信度衰减 (30天半衰期) */
    @Scheduled(cron = "0 0 4 * * *")
    public void dailyDecay() {
        // 多实例部署时，全量衰减每个触发窗口只应执行一次。无锁存储 (测试/单实例) 时直接执行。
        if (taskLock != null && !taskLock.tryAcquire("profile-daily-decay", 3600)) return;
        List<Long> userIds = jdbc.query("SELECT user_id FROM profiles", (rs, i) -> rs.getLong(1));
        for (long uid : userIds) {
            saveProfile(uid, ProfileMerger.decay(snapshot(uid)));
        }
        log.info("画像衰减任务完成: {} 个用户", userIds.size());
    }

    private void saveProfile(long userId, Map<String, Object> data) {
        try {
            String json = mapper.writeValueAsString(data);
            jdbc.update("""
                    INSERT INTO profiles (user_id, data, updated_at) VALUES (?, ?::jsonb, now())
                    ON CONFLICT (user_id) DO UPDATE SET data=?::jsonb, updated_at=now()
                    """, userId, json, json);
        } catch (Exception e) {
            log.error("画像保存失败 user={}", userId, e);
        }
    }

    private boolean saveProfileIfGeneration(long userId, long generation, Map<String, Object> data) {
        try {
            String json = mapper.writeValueAsString(data);
            return jdbc.update("""
                    INSERT INTO profiles (user_id, data, updated_at)
                    SELECT ?, ?::jsonb, now()
                    WHERE EXISTS (SELECT 1 FROM users WHERE id=? AND memory_generation=?)
                    ON CONFLICT (user_id) DO UPDATE SET data=EXCLUDED.data, updated_at=now()
                    """, userId, json, userId, generation) > 0;
        } catch (Exception e) {
            log.error("画像保存失败 user={}", userId, e);
            return false;
        }
    }

    private boolean saveWithoutTransaction(long userId, Map<String, Object> next,
                                           String eventJson, String traceId) {
        saveProfile(userId, next);
        jdbc.update("INSERT INTO profile_events (user_id, delta, trigger, trace_id) VALUES (?, ?::jsonb, ?, ?)",
                userId, eventJson, "conversation", traceId);
        return true;
    }

    private boolean generationCurrent(long userId, long expectedGeneration) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id=? AND memory_generation=?",
                Integer.class, userId, expectedGeneration);
        return count != null && count > 0;
    }

    private ExtractedDelta sanitizeDelta(ExtractedDelta delta) {
        List<ExtractedDelta.SkillDelta> skills = delta.skills() == null ? List.of()
                : delta.skills().stream().map(s -> new ExtractedDelta.SkillDelta(
                        PiiMasker.mask(s.name()).masked(), s.explicit())).toList();
        Map<String, ExtractedDelta.ScalarDelta> scalars = new HashMap<>();
        if (delta.scalars() != null) {
            delta.scalars().forEach((field, value) -> scalars.put(field,
                    new ExtractedDelta.ScalarDelta(PiiMasker.mask(value.value()).masked(), value.explicit())));
        }
        List<String> formats = delta.preferredFormat() == null ? List.of()
                : delta.preferredFormat().stream().map(v -> PiiMasker.mask(v).masked()).toList();
        return new ExtractedDelta(skills, scalars, formats);
    }

    private ExtractedDelta parseDelta(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            List<ExtractedDelta.SkillDelta> skills = new ArrayList<>();
            for (JsonNode s : root.path("skills")) {
                if (skills.size() >= 50) break;
                if (s.hasNonNull("name")) {
                    String name = s.get("name").asText("");
                    if (!name.isBlank()) {
                        skills.add(new ExtractedDelta.SkillDelta(clip(name, 120), s.path("explicit").asBoolean(false)));
                    }
                }
            }
            Map<String, ExtractedDelta.ScalarDelta> scalars = new HashMap<>();
            JsonNode sc = root.path("scalars");
            for (String f : ProfileMerger.SCALAR_FIELDS) {
                JsonNode n = sc.path(f);
                if (n.isObject() && n.hasNonNull("value")) {
                    scalars.put(f, new ExtractedDelta.ScalarDelta(n.get("value").asText(), n.path("explicit").asBoolean(false)));
                }
            }
            List<String> formats = new ArrayList<>();
            for (JsonNode f : root.path("preferred_format")) {
                if (formats.size() >= 10) break;
                formats.add(clip(f.asText(""), 80));
            }
            return new ExtractedDelta(skills, scalars, formats);
        } catch (Exception e) {
            log.warn("画像抽取JSON解析失败: {}", e.getMessage());
            return new ExtractedDelta(List.of(), Map.of(), List.of());
        }
    }

    private List<String> parseEventChanges(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("画像事件反序列化失败: {}", e.getMessage());
            return List.of("画像已更新");
        }
    }

    private String clip(String value, int maxChars) {
        if (value == null) return "";
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…";
    }
}
