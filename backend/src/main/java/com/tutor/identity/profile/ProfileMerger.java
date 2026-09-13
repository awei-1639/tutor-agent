package com.tutor.identity.profile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 画像合并规则 — 确定性纯函数, LLM 只负责抽取不负责决策 (实现设计 2.3 / ADR)。
 * 规则: explicit(1.0) 永远覆盖 inferred; inferred 重复印证 +0.1 封顶 0.9; 同级冲突保留高置信。
 * 关键字段 (target_position/location) 已有确认值时, 变更进 pending_confirm 待用户确认。
 */
public final class ProfileMerger {
    public static final double EXPLICIT = 1.0;
    public static final double INFERRED_INIT = 0.6;
    public static final double REINFORCE = 0.1;
    public static final double INFERRED_CAP = 0.9;
    public static final double DECAY_DAILY = 0.977;   // 30天半衰期
    public static final double DECAY_FLOOR = 0.05;
    /** 面试证据来源: 真实作答的验证信号, 强于对话推断, 弱于用户显式声明 */
    public static final String SOURCE_INTERVIEW = "interview";
    public static final double INTERVIEW_MIN_SCORE = 7.0;      // 低于此分视为待补齐, 交给学习计划而非画像
    public static final double INTERVIEW_MAX_CONF = 0.9;       // 封顶低于 explicit(1.0)
    public static final double INTERVIEW_CONF_PER_POINT = 0.06; // 每少 1 分降低的置信度
    static final List<String> KEY_FIELDS = List.of("target_position", "location");
    static final List<String> SCALAR_FIELDS = List.of(
            "target_position", "location", "experience_years", "education", "daily_hours");

    private ProfileMerger() {}

    /** 返回新的 profile map (不可变原则: 不修改入参); events 收集审计增量描述 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> merge(Map<String, Object> current, ExtractedDelta delta,
                                            List<String> events) {
        Map<String, Object> next = deepCopy(current);

        // 技能: 按 name 合并
        if (delta.skills() != null && !delta.skills().isEmpty()) {
            List<Map<String, Object>> skills = (List<Map<String, Object>>) next
                    .computeIfAbsent("skills", k -> new ArrayList<Map<String, Object>>());
            for (ExtractedDelta.SkillDelta sd : delta.skills()) {
                Map<String, Object> hit = skills.stream()
                        .filter(s -> sd.name().equalsIgnoreCase(String.valueOf(s.get("name"))))
                        .findFirst().orElse(null);
                if (hit == null) {
                    skills.add(new HashMap<>(Map.of(
                            "name", sd.name(),
                            "confidence", sd.explicit() ? EXPLICIT : INFERRED_INIT,
                            "source", sd.explicit() ? "explicit" : "inferred",
                            "last_seen", LocalDate.now().toString())));
                    events.add("技能新增: " + sd.name() + (sd.explicit() ? "(explicit)" : "(inferred)"));
                } else {
                    double conf = num(hit.get("confidence"));
                    boolean wasExplicit = "explicit".equals(hit.get("source"));
                    if (sd.explicit()) {
                        hit.put("confidence", EXPLICIT);
                        hit.put("source", "explicit");
                        if (!wasExplicit) events.add("技能升为显式: " + sd.name());
                    } else if (!wasExplicit) {
                        double nv = Math.min(INFERRED_CAP, conf + REINFORCE);
                        if (nv != conf) events.add("技能印证: " + sd.name() + " " + conf + "→" + nv);
                        hit.put("confidence", nv);
                    } // explicit 存量遇 inferred 新值: 不动 (显式优先)
                    hit.put("last_seen", LocalDate.now().toString());
                }
            }
        }

        // 标量字段
        for (String field : SCALAR_FIELDS) {
            ExtractedDelta.ScalarDelta sd = delta.scalar(field);
            if (sd == null || sd.value() == null || sd.value().isBlank()) continue;
            Map<String, Object> cur = (Map<String, Object>) next.get(field);
            double newConf = sd.explicit() ? EXPLICIT : INFERRED_INIT;
            boolean isKey = KEY_FIELDS.contains(field);
            boolean sameValue = cur != null && sd.value().equals(String.valueOf(cur.get("value")));

            if (sameValue) {
                if (!sd.explicit() && "inferred".equals(cur.get("source"))) {
                    cur.put("confidence", Math.min(INFERRED_CAP, num(cur.get("confidence")) + REINFORCE));
                } else if (sd.explicit()) {
                    cur.put("confidence", EXPLICIT);
                    cur.put("source", "explicit");
                }
                continue;
            }
            if (cur == null) { // 首次设置直接生效 (onboarding 无摩擦)
                next.put(field, newField(sd.value(), newConf, sd.explicit(), isKey && sd.explicit()));
                events.add(field + " 首次设置: " + sd.value());
                continue;
            }
            if (isKey) { // 关键字段变更 → 待确认区
                Map<String, Object> pending = (Map<String, Object>) next
                        .computeIfAbsent("pending_confirm", k -> new HashMap<String, Object>());
                pending.put(field, newField(sd.value(), newConf, sd.explicit(), false));
                events.add(field + " 变更待确认: " + cur.get("value") + "→" + sd.value());
                continue;
            }
            // 非关键标量: explicit覆盖一切; inferred仅在置信更高时覆盖
            if (sd.explicit() || newConf > num(cur.get("confidence"))) {
                next.put(field, newField(sd.value(), newConf, sd.explicit(), false));
                events.add(field + " 更新: " + cur.get("value") + "→" + sd.value());
            }
        }

        // 偏好形式: 并集去重
        if (delta.preferredFormat() != null && !delta.preferredFormat().isEmpty()) {
            List<String> pf = (List<String>) next.computeIfAbsent("preferred_format", k -> new ArrayList<String>());
            for (String f : delta.preferredFormat()) if (!pf.contains(f)) { pf.add(f); events.add("偏好新增: " + f); }
        }
        return next;
    }

    /**
     * 面试证据回写: 仅"被证明具备"的技能进入画像。
     *
     * <p>弱项 (<7 分) 不在此降权 —— 单场题目难度受 JUNIOR/MID/SENIOR 影响，低分不足以证伪，
     * 且降权会让画像随单次波动抖动。弱项由学习计划消费 (InterviewCompletionWorker → createEvidenceTasks)。
     * 用户显式声明的技能永不被面试证据覆盖。
     *
     * @param averageScores 技能 ID (可能带 skill: 前缀) → 该场面试的平均分
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mergeInterviewSkills(Map<String, Object> current,
                                                           Map<String, Double> averageScores,
                                                           List<String> events) {
        Map<String, Object> next = deepCopy(current);
        if (averageScores == null || averageScores.isEmpty()) return next;
        Map<String, Double> verified = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : averageScores.entrySet()) {
            String name = normalizeSkillName(entry.getKey());
            Double rawScore = entry.getValue();
            double score = rawScore == null ? 0 : rawScore;
            if (name.isEmpty() || score < INTERVIEW_MIN_SCORE) continue;
            verified.merge(name, score, Math::max);   // 同场同名取最高分, 避免重复键互相覆盖
        }
        if (verified.isEmpty()) return next;

        List<Map<String, Object>> skills = (List<Map<String, Object>>) next
                .computeIfAbsent("skills", k -> new ArrayList<Map<String, Object>>());
        String today = LocalDate.now().toString();
        for (Map.Entry<String, Double> entry : verified.entrySet()) {
            String name = entry.getKey();
            double target = interviewConfidence(entry.getValue());

            Map<String, Object> hit = skills.stream()
                    .filter(s -> name.equalsIgnoreCase(String.valueOf(s.get("name"))))
                    .findFirst().orElse(null);
            if (hit == null) {
                Map<String, Object> skill = new HashMap<>();
                skill.put("name", name);
                skill.put("confidence", target);
                skill.put("source", SOURCE_INTERVIEW);
                skill.put("last_seen", today);
                skills.add(skill);
                events.add("技能新增(面试): " + name);
                continue;
            }
            if ("explicit".equals(hit.get("source"))) continue;  // 显式优先, 面试证据不覆盖
            hit.put("last_seen", today);
            double conf = num(hit.get("confidence"));
            if (target > conf + 1e-9) {
                hit.put("confidence", target);
                hit.put("source", SOURCE_INTERVIEW);
                events.add("面试印证: " + name + " " + conf + "→" + target);
            }
        }
        return next;
    }

    /** 技能 ID → 画像技能名: 去掉图谱 skill: 前缀 */
    public static String normalizeSkillName(String skillId) {
        String trimmed = skillId == null ? "" : skillId.trim();
        return trimmed.toLowerCase(Locale.ROOT).startsWith("skill:")
                ? trimmed.substring("skill:".length()).trim()
                : trimmed;
    }

    /** 面试平均分 → 画像置信度: 10 分封顶 0.9, 每少 1 分 -0.06, 下限 0.6 */
    public static double interviewConfidence(double averageScore) {
        double raw = INTERVIEW_MAX_CONF - (10.0 - averageScore) * INTERVIEW_CONF_PER_POINT;
        return Math.max(INFERRED_INIT, Math.min(INTERVIEW_MAX_CONF, raw));
    }

    /** 每日衰减: 仅非 explicit 字段 (inferred / interview), 有下限; 返回新 map */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> decay(Map<String, Object> current) {
        Map<String, Object> next = deepCopy(current);
        Object skillsObj = next.get("skills");
        if (skillsObj instanceof List<?> skills) {
            for (Object o : skills) {
                Map<String, Object> s = (Map<String, Object>) o;
                if (!"explicit".equals(s.get("source"))) {
                    s.put("confidence", Math.max(DECAY_FLOOR, num(s.get("confidence")) * DECAY_DAILY));
                }
            }
        }
        for (String field : SCALAR_FIELDS) {
            Object o = next.get(field);
            if (o instanceof Map<?, ?> m && !"explicit".equals(((Map<String, Object>) m).get("source"))) {
                Map<String, Object> f = (Map<String, Object>) m;
                f.put("confidence", Math.max(DECAY_FLOOR, num(f.get("confidence")) * DECAY_DAILY));
            }
        }
        return next;
    }

    /** 用户确认 pending_confirm 中的关键字段 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> confirm(Map<String, Object> current, String field, boolean accept) {
        Map<String, Object> next = deepCopy(current);
        Map<String, Object> pending = (Map<String, Object>) next.get("pending_confirm");
        if (pending == null || !pending.containsKey(field)) return next;
        Map<String, Object> value = (Map<String, Object>) pending.remove(field);
        if (accept) {
            value.put("confirmed", true);
            value.put("confidence", EXPLICIT);
            value.put("source", "explicit");
            next.put(field, value);
        }
        if (pending.isEmpty()) next.remove("pending_confirm");
        return next;
    }

    private static Map<String, Object> newField(String value, double conf, boolean explicit, boolean confirmed) {
        Map<String, Object> m = new HashMap<>();
        m.put("value", value);
        m.put("confidence", conf);
        m.put("source", explicit ? "explicit" : "inferred");
        m.put("confirmed", confirmed);
        return m;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> src) {
        Map<String, Object> out = new HashMap<>();
        src.forEach((k, v) -> out.put(k, copyValue(v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object v) {
        if (v instanceof Map<?, ?> m) return deepCopy((Map<String, Object>) m);
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(copyValue(x)));
            return out;
        }
        return v;
    }
}
