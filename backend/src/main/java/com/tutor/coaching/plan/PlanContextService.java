package com.tutor.coaching.plan;

import com.tutor.conversation.memory.local.FactStore;
import com.tutor.coaching.interview.InterviewSkillEvidenceStore;
import com.tutor.identity.profile.ProfileMerger;
import com.tutor.identity.profile.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Assembles the learner context a weekly plan must respect.
 *
 * <p>Without this the planner only sees the three strings typed into the request form, so the
 * profile, long-term facts and interview evidence the system already paid for never reach the
 * highest-value decision it makes. Every read here is best-effort: a failure degrades the plan
 * to the legacy inputs instead of failing generation.
 */
@Service
public class PlanContextService {
    private static final Logger log = LoggerFactory.getLogger(PlanContextService.class);
    private static final int MAX_FACTS = 8;
    private static final int MAX_WEAK_SKILLS = 5;
    private static final int MAX_SKILLS = 12;

    private final ProfileService profiles;
    private final FactStore facts;
    private final InterviewSkillEvidenceStore evidence;

    public PlanContextService(ProfileService profiles, FactStore facts,
                              InterviewSkillEvidenceStore evidence) {
        this.profiles = profiles;
        this.facts = facts;
        this.evidence = evidence;
    }

    public record LearnerContext(String profileSummary, List<String> facts, List<String> weakSkills) {
        public boolean isEmpty() {
            return profileSummary.isBlank() && facts.isEmpty() && weakSkills.isEmpty();
        }

        /** Prompt block appended to the planner request; empty when there is nothing to say. */
        public String toPromptBlock() {
            if (isEmpty()) return "";
            StringBuilder block = new StringBuilder("\n\n用户画像与历史证据（优先依据这些安排任务）:");
            if (!profileSummary.isBlank()) block.append("\n- 画像: ").append(profileSummary);
            if (!facts.isEmpty()) block.append("\n- 长期事实: ").append(String.join("；", facts));
            if (!weakSkills.isEmpty()) {
                block.append("\n- 面试验证的薄弱项（应优先安排练习与复习）: ")
                        .append(String.join("、", weakSkills));
            }
            return block.toString();
        }
    }

    public LearnerContext load(long userId) {
        return new LearnerContext(profileSummary(userId), recentFacts(userId), weakSkills(userId));
    }

    private String profileSummary(long userId) {
        try {
            return renderProfile(profiles.snapshot(userId));
        } catch (Exception error) {
            log.warn("计划上下文: 画像读取失败 user={}: {}", userId, error.getMessage());
            return "";
        }
    }

    private List<String> recentFacts(long userId) {
        try {
            return facts.activeByUser(userId, MAX_FACTS).stream()
                    .map(FactStore.UserFact::factText)
                    .filter(text -> text != null && !text.isBlank())
                    .limit(MAX_FACTS)
                    .toList();
        } catch (Exception error) {
            log.warn("计划上下文: 长期事实读取失败 user={}: {}", userId, error.getMessage());
            return List.of();
        }
    }

    private List<String> weakSkills(long userId) {
        try {
            return evidence.recentWeakSkills(userId, ProfileMerger.INTERVIEW_MIN_SCORE, MAX_WEAK_SKILLS).stream()
                    .map(ProfileMerger::normalizeSkillName)
                    .filter(name -> !name.isEmpty())
                    .distinct()
                    .limit(MAX_WEAK_SKILLS)
                    .toList();
        } catch (Exception error) {
            log.warn("计划上下文: 面试证据读取失败 user={}: {}", userId, error.getMessage());
            return List.of();
        }
    }

    /** Renders the profile snapshot as one compact, prompt-safe line. */
    private String renderProfile(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        appendScalar(parts, snapshot, "target_position", "目标岗位");
        appendScalar(parts, snapshot, "experience_years", "工作年限");
        appendScalar(parts, snapshot, "daily_hours", "每日可学");
        appendScalar(parts, snapshot, "education", "学历");
        appendPreferredFormat(parts, snapshot);
        appendSkills(parts, snapshot);
        return String.join("｜", parts);
    }

    private void appendScalar(List<String> parts, Map<String, Object> snapshot, String field, String label) {
        Object raw = snapshot.get(field);
        if (raw instanceof Map<?, ?> value) {
            Object text = value.get("value");
            if (text != null && !String.valueOf(text).isBlank()) parts.add(label + ": " + text);
            return;
        }
        if (raw != null && !String.valueOf(raw).isBlank()) parts.add(label + ": " + raw);
    }

    private void appendPreferredFormat(List<String> parts, Map<String, Object> snapshot) {
        Object raw = snapshot.get("preferred_format");
        if (raw instanceof List<?> formats && !formats.isEmpty()) {
            parts.add("偏好形式: " + String.join("/", formats.stream()
                    .filter(item -> item != null && !String.valueOf(item).isBlank())
                    .map(String::valueOf).toList()));
        }
    }

    private void appendSkills(List<String> parts, Map<String, Object> snapshot) {
        Object raw = snapshot.get("skills");
        if (!(raw instanceof List<?> skills) || skills.isEmpty()) return;
        List<String> rendered = new ArrayList<>();
        for (Object item : skills) {
            if (!(item instanceof Map<?, ?> skill)) continue;
            Object name = skill.get("name");
            if (name == null || String.valueOf(name).isBlank()) continue;
            Object confidence = skill.get("confidence");
            String suffix = confidence instanceof Number number
                    ? "(" + String.format(Locale.ROOT, "%.2f", number.doubleValue()) + ")"
                    : "";
            rendered.add(ProfileMerger.normalizeSkillName(String.valueOf(name)) + suffix);
            if (rendered.size() >= MAX_SKILLS) break;
        }
        if (!rendered.isEmpty()) parts.add("已掌握: " + String.join("、", rendered));
    }
}
