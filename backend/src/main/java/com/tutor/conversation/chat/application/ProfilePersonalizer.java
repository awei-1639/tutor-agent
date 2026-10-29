package com.tutor.conversation.chat.application;

import com.tutor.contract.Evidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 画像感知检索重排 (纯函数, 可单测)。
 *
 * <p>定位是「辅料」: 检索内核 (FusedRetriever/评测链路) 零改动, 本类只在 chat 检索阶段
 * 对溢出候选池做一次稳定重排——用 L3 画像里的 target_position (强信号) 和 skills
 * (弱信号, 按 confidence 加权) 对证据文本命中者小幅加分, 让与用户画像同向的证据
 * 在同分竞争中存活, 而不是改写检索打分本身。
 *
 * <p>安全边界: 总增幅封顶 {@link #MAX_FACTOR}, 防止画像把语义不相关的候选顶进 topK;
 * ASCII 词元按词边界匹配, 避免 "go" 命中 "google" 这类假阳性。
 */
final class ProfilePersonalizer {
    /** 命中 target_position 任一词元的固定加成 (强信号)。 */
    static final double TARGET_POSITION_BONUS = 0.20;
    /** 每命中一个技能 (confidence 加权) 的加成。 */
    static final double SKILL_BONUS_PER_MATCH = 0.06;
    /** 技能加成总和上限。 */
    static final double SKILL_BONUS_CAP = 0.15;
    /** 单条证据总增幅上限 (1 + 0.25)。 */
    static final double MAX_FACTOR = 1.25;
    /** 技能置信度低于此值不参与匹配 (每日衰减后接近噪声的 inferred 技能)。 */
    static final double MIN_SKILL_CONFIDENCE = 0.5;
    /** 重排需要额外候选池的大小: topK 之外多取这些候选参与画像竞争。 */
    static final int POOL_EXTRA = 7;

    private ProfilePersonalizer() {}

    /** 画像重排信号; 空 signal = 无画像或字段全空, 调用方应直接跳过。 */
    record Signal(String targetPosition, List<WeightedSkill> skills) {
        boolean present() {
            return (targetPosition != null && !targetPosition.isBlank())
                    || (skills != null && !skills.isEmpty());
        }
    }

    record WeightedSkill(String name, double confidence) {}

    /** 重排结果: 裁剪后的证据列表 + 因画像加成而改变排名的条数 (观测用)。 */
    record Result(List<Evidence> evidences, int boostedCount) {}

    /** 从画像快照提取信号; 容错: 字段缺失/类型不符一律忽略, 绝不让坏数据打断检索。 */
    static Signal fromProfile(Map<String, Object> profile) {
        if (profile == null || profile.isEmpty()) return new Signal(null, List.of());
        String target = profile.get("target_position") == null
                ? null : String.valueOf(profile.get("target_position")).trim();
        List<WeightedSkill> skills = new ArrayList<>();
        if (profile.get("skills") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> skill)) continue;
                Object name = skill.get("name");
                if (name == null) continue;
                String skillName = String.valueOf(name).trim().toLowerCase(Locale.ROOT);
                if (skillName.isEmpty()) continue;
                double confidence = 0;
                if (skill.get("confidence") instanceof Number n) confidence = n.doubleValue();
                if (confidence >= MIN_SKILL_CONFIDENCE) skills.add(new WeightedSkill(skillName, confidence));
            }
        }
        return new Signal(target == null || target.isBlank() ? null : target, List.copyOf(skills));
    }

    /** 候选池重排并裁剪到 topK。candidates 无需预先排序; 相同加成保持原相对顺序 (稳定)。 */
    static Result personalize(Signal signal, List<Evidence> candidates, int topK) {
        List<Evidence> safeCandidates = candidates == null ? List.of() : candidates;
        if (topK <= 0 || safeCandidates.isEmpty() || signal == null || !signal.present()) {
            return new Result(safeCandidates.stream().limit(Math.max(0, topK)).toList(), 0);
        }
        List<String> targetTokens = targetTokens(signal.targetPosition());
        record Scored(Evidence evidence, double factor) {}
        List<Scored> scored = new ArrayList<>(safeCandidates.size());
        int boosted = 0;
        for (Evidence evidence : safeCandidates) {
            double bonus = 0;
            if (!targetTokens.isEmpty() && matchesAny(textOf(evidence), targetTokens)) {
                bonus += TARGET_POSITION_BONUS;
            }
            double skillBonus = 0;
            for (WeightedSkill skill : signal.skills()) {
                if (matches(textOf(evidence), skill.name())) {
                    skillBonus = Math.min(SKILL_BONUS_CAP, skillBonus + SKILL_BONUS_PER_MATCH * skill.confidence());
                }
            }
            double factor = Math.min(MAX_FACTOR, 1 + bonus + skillBonus);
            if (factor > 1) boosted++;
            scored.add(new Scored(evidence, factor));
        }
        // List.sort 稳定: 加成相同的候选保持检索原序, 画像只做同分竞争的裁决者
        scored.sort(Comparator.comparingDouble(Scored::factor).reversed());
        return new Result(scored.stream().map(Scored::evidence).limit(topK).toList(), boosted);
    }

    // ---- 匹配 ----

    private static String textOf(Evidence evidence) {
        String text = evidence == null ? null : evidence.chunkText();
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private static boolean matchesAny(String text, List<String> tokens) {
        return tokens.stream().anyMatch(token -> matches(text, token));
    }

    /** ASCII 词元按词边界, 其余 (CJK 等) 按子串。 */
    static boolean matches(String lowerText, String lowerToken) {
        if (lowerText.isEmpty() || lowerToken == null || lowerToken.length() < 2) return false;
        if (lowerToken.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
            return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(lowerToken) + "(?![a-z0-9])")
                    .matcher(lowerText).find();
        }
        return lowerText.contains(lowerToken);
    }

    /** target_position 拆词: 中英边界与标点处切开, 词元长度 ≥ 2。 */
    private static List<String> targetTokens(String targetPosition) {
        if (targetPosition == null || targetPosition.isBlank()) return List.of();
        String normalized = targetPosition.toLowerCase(Locale.ROOT)
                .replaceAll("(?<=[\\p{IsHan}])(?=[a-z0-9])", " ")
                .replaceAll("(?<=[a-z0-9])(?=[\\p{IsHan}])", " ");
        return java.util.Arrays.stream(normalized.split("[^\\p{L}\\p{N}]+"))
                .map(String::trim).filter(t -> t.length() >= 2).distinct().toList();
    }
}
