package com.tutor.identity.profile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ProfileMergerTest {

    private ExtractedDelta skillDelta(String name, boolean explicit) {
        return new ExtractedDelta(List.of(new ExtractedDelta.SkillDelta(name, explicit)), Map.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstSkill(Map<String, Object> profile) {
        return ((List<Map<String, Object>>) profile.get("skills")).get(0);
    }

    @Test
    void newInferredSkillStartsAt06() {
        Map<String, Object> next = ProfileMerger.merge(new HashMap<>(), skillDelta("Python", false), new ArrayList<>());
        assertThat(firstSkill(next))
                .containsEntry("confidence", ProfileMerger.INFERRED_INIT)
                .containsEntry("source", "inferred");
    }

    @Test
    void reinforcementAddsTenthCappedAt09() {
        Map<String, Object> p = ProfileMerger.merge(new HashMap<>(), skillDelta("Python", false), new ArrayList<>());
        for (int i = 0; i < 5; i++) p = ProfileMerger.merge(p, skillDelta("Python", false), new ArrayList<>());
        assertThat((double) firstSkill(p).get("confidence")).isEqualTo(ProfileMerger.INFERRED_CAP);
    }

    @Test
    void explicitOverridesInferredButNotViceVersa() {
        Map<String, Object> p = ProfileMerger.merge(new HashMap<>(), skillDelta("Java", false), new ArrayList<>());
        p = ProfileMerger.merge(p, skillDelta("Java", true), new ArrayList<>());
        assertThat(firstSkill(p)).containsEntry("confidence", ProfileMerger.EXPLICIT).containsEntry("source", "explicit");
        // 显式存量遇 inferred 新值: 不降级
        p = ProfileMerger.merge(p, skillDelta("Java", false), new ArrayList<>());
        assertThat(firstSkill(p)).containsEntry("confidence", ProfileMerger.EXPLICIT).containsEntry("source", "explicit");
    }

    @Test
    void keyFieldFirstSetAppliesButChangeGoesPending() {
        ExtractedDelta first = new ExtractedDelta(List.of(),
                Map.of("target_position", new ExtractedDelta.ScalarDelta("NLP算法工程师", true)), List.of());
        Map<String, Object> p = ProfileMerger.merge(new HashMap<>(), first, new ArrayList<>());
        assertThat(((Map<?, ?>) p.get("target_position")).get("value")).isEqualTo("NLP算法工程师");

        ExtractedDelta change = new ExtractedDelta(List.of(),
                Map.of("target_position", new ExtractedDelta.ScalarDelta("大模型应用开发", true)), List.of());
        p = ProfileMerger.merge(p, change, new ArrayList<>());
        // 原值不动, 新值进待确认
        assertThat(((Map<?, ?>) p.get("target_position")).get("value")).isEqualTo("NLP算法工程师");
        Map<?, ?> pending = (Map<?, ?>) ((Map<?, ?>) p.get("pending_confirm")).get("target_position");
        assertThat(pending.get("value")).isEqualTo("大模型应用开发");

        // 确认后生效
        Map<String, Object> confirmed = ProfileMerger.confirm(p, "target_position", true);
        assertThat(((Map<?, ?>) confirmed.get("target_position")).get("value")).isEqualTo("大模型应用开发");
        assertThat(confirmed).doesNotContainKey("pending_confirm");
    }

    @Test
    void decayOnlyAffectsInferredWithFloor() {
        Map<String, Object> p = ProfileMerger.merge(new HashMap<>(), skillDelta("Python", false), new ArrayList<>());
        p = ProfileMerger.merge(p, skillDelta("Java", true), new ArrayList<>());
        Map<String, Object> decayed = ProfileMerger.decay(p);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skills = (List<Map<String, Object>>) decayed.get("skills");
        Map<String, Object> python = skills.stream().filter(s -> s.get("name").equals("Python")).findFirst().orElseThrow();
        Map<String, Object> java = skills.stream().filter(s -> s.get("name").equals("Java")).findFirst().orElseThrow();
        assertThat((double) python.get("confidence")).isEqualTo(ProfileMerger.INFERRED_INIT * ProfileMerger.DECAY_DAILY);
        assertThat((double) java.get("confidence")).isEqualTo(ProfileMerger.EXPLICIT); // explicit 不衰减
    }

    @Test
    void interviewEvidenceAddsVerifiedSkillWithScoreDerivedConfidence() {
        List<String> events = new ArrayList<>();
        Map<String, Object> p = ProfileMerger.mergeInterviewSkills(new HashMap<>(),
                Map.of("skill:Redis", 9.0), events);

        assertThat(firstSkill(p)).containsEntry("name", "Redis")
                .containsEntry("source", ProfileMerger.SOURCE_INTERVIEW);
        assertThat((double) firstSkill(p).get("confidence")).isCloseTo(0.84, within(1e-9));
        assertThat(events).containsExactly("技能新增(面试): Redis");
    }

    @Test
    void interviewEvidenceIgnoresWeakSkillsBelowSeven() {
        Map<String, Object> p = ProfileMerger.mergeInterviewSkills(new HashMap<>(),
                Map.of("skill:Redis", 6.9), new ArrayList<>());

        assertThat(p).doesNotContainKey("skills");
    }

    @Test
    void interviewEvidenceRaisesButNeverOverridesExplicit() {
        Map<String, Object> p = ProfileMerger.merge(new HashMap<>(), skillDelta("Redis", true), new ArrayList<>());
        List<String> events = new ArrayList<>();
        p = ProfileMerger.mergeInterviewSkills(p, Map.of("skill:Redis", 10.0), events);

        assertThat(firstSkill(p)).containsEntry("confidence", ProfileMerger.EXPLICIT)
                .containsEntry("source", "explicit");
        assertThat(events).isEmpty();
    }

    @Test
    void interviewEvidenceOnlyRaisesConfidenceNeverLowers() {
        Map<String, Object> p = ProfileMerger.mergeInterviewSkills(new HashMap<>(),
                Map.of("RAG", 10.0), new ArrayList<>());
        List<String> events = new ArrayList<>();
        p = ProfileMerger.mergeInterviewSkills(p, Map.of("RAG", 7.0), events);

        assertThat((double) firstSkill(p).get("confidence")).isEqualTo(ProfileMerger.INTERVIEW_MAX_CONF);
        assertThat(events).isEmpty(); // 低分重考不降权
    }

    @Test
    void interviewEvidenceDecaysLikeInferredEvidence() {
        Map<String, Object> p = ProfileMerger.mergeInterviewSkills(new HashMap<>(),
                Map.of("Redis", 10.0), new ArrayList<>());
        Map<String, Object> decayed = ProfileMerger.decay(p);

        assertThat((double) firstSkill(decayed).get("confidence"))
                .isCloseTo(ProfileMerger.INTERVIEW_MAX_CONF * ProfileMerger.DECAY_DAILY, within(1e-9));
    }

    @Test
    void interviewConfidenceIsCappedBelowExplicit() {
        assertThat(ProfileMerger.interviewConfidence(10.0)).isCloseTo(0.9, within(1e-9));
        assertThat(ProfileMerger.interviewConfidence(7.0)).isCloseTo(0.72, within(1e-9));
        assertThat(ProfileMerger.interviewConfidence(0.0)).isEqualTo(ProfileMerger.INFERRED_INIT);
    }

    @Test
    void mergeDoesNotMutateInput() {
        Map<String, Object> original = ProfileMerger.merge(new HashMap<>(), skillDelta("Python", false), new ArrayList<>());
        double before = (double) firstSkill(original).get("confidence");
        ProfileMerger.merge(original, skillDelta("Python", false), new ArrayList<>());
        assertThat((double) firstSkill(original).get("confidence")).isEqualTo(before); // 入参未被修改
    }
}
