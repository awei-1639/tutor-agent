package com.tutor.conversation.chat.application;

import com.tutor.contract.Evidence;
import com.tutor.conversation.chat.application.ProfilePersonalizer.Result;
import com.tutor.conversation.chat.application.ProfilePersonalizer.Signal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 画像感知重排的纯函数契约: 信号提取、加成排序、封顶与假阳性防护。 */
class ProfilePersonalizerTest {

    private static Evidence ev(String id, String text) {
        return new Evidence(id, "skill", text, 0.5, null, null, null, "h1", Evidence.KIND_REFERENCE);
    }

    // ---- fromProfile ----

    @Test
    void emptyOrNullProfileYieldsAbsentSignal() {
        assertThat(ProfilePersonalizer.fromProfile(null).present()).isFalse();
        assertThat(ProfilePersonalizer.fromProfile(Map.of()).present()).isFalse();
    }

    @Test
    void extractsTargetPositionAndSkilledItemsOnly() {
        Map<String, Object> profile = Map.of(
                "target_position", "Java 后端开发",
                "skills", List.of(
                        Map.of("name", "Spring Boot", "confidence", 0.8),
                        Map.of("name", "弱技能", "confidence", 0.3),
                        Map.of("name", "  "),                       // 空名忽略
                        Map.of("confidence", 0.9)));                // 缺名忽略
        Signal signal = ProfilePersonalizer.fromProfile(profile);
        assertThat(signal.present()).isTrue();
        assertThat(signal.targetPosition()).isEqualTo("Java 后端开发");
        assertThat(signal.skills()).hasSize(1);
        assertThat(signal.skills().getFirst().name()).isEqualTo("spring boot");
        assertThat(signal.skills().getFirst().confidence()).isEqualTo(0.8);
    }

    // ---- personalize ----

    @Test
    void absentSignalKeepsRetrievalOrderAndCutsToTopK() {
        List<Evidence> candidates = List.of(ev("a", "向量检索"), ev("b", "图数据库"), ev("c", "消息队列"));
        Result result = ProfilePersonalizer.personalize(
                ProfilePersonalizer.fromProfile(Map.of()), candidates, 2);
        assertThat(result.evidences()).extracting(Evidence::nodeId).containsExactly("a", "b");
        assertThat(result.boostedCount()).isZero();
    }

    @Test
    void targetPositionMatchPromotesEvidenceAcrossOriginalRank() {
        Signal signal = ProfilePersonalizer.fromProfile(Map.of("target_position", "Java后端"));
        // 检索序: a 最前但与画像无关; c 命中画像应被顶到最前
        List<Evidence> candidates = List.of(ev("a", "向量数据库原理"), ev("b", "Go 并发模型"), ev("c", "Java 后端面试要点"));
        Result result = ProfilePersonalizer.personalize(signal, candidates, 3);
        assertThat(result.evidences()).extracting(Evidence::nodeId).containsExactly("c", "a", "b");
        assertThat(result.boostedCount()).isEqualTo(1);
    }

    @Test
    void skillMatchBoostWeightedByConfidence() {
        Signal signal = ProfilePersonalizer.fromProfile(Map.of("skills",
                List.of(Map.of("name", "Redis", "confidence", 1.0))));
        List<Evidence> candidates = List.of(ev("a", "MySQL 索引优化"), ev("b", "Redis 缓存穿透"));
        Result result = ProfilePersonalizer.personalize(signal, candidates, 2);
        assertThat(result.evidences()).extracting(Evidence::nodeId).containsExactly("b", "a");
    }

    @Test
    void lowConfidenceSkillsDoNotParticipate() {
        Signal signal = ProfilePersonalizer.fromProfile(Map.of("skills",
                List.of(Map.of("name", "Redis", "confidence", 0.2))));
        List<Evidence> candidates = List.of(ev("a", "MySQL 索引优化"), ev("b", "Redis 缓存穿透"));
        Result result = ProfilePersonalizer.personalize(signal, candidates, 2);
        assertThat(result.evidences()).extracting(Evidence::nodeId).containsExactly("a", "b");
        assertThat(result.boostedCount()).isZero();
    }

    @Test
    void totalFactorIsCapped() {
        // 目标命中(0.20) + 3 个技能全命中(0.06×3=0.18) = 0.38 > 封顶 0.25
        Signal signal = ProfilePersonalizer.fromProfile(Map.of(
                "target_position", "后端",
                "skills", List.of(
                        Map.of("name", "后端", "confidence", 1.0),
                        Map.of("name", "Java", "confidence", 1.0),
                        Map.of("name", "微服务", "confidence", 1.0))));
        List<Evidence> candidates = List.of(
                ev("a", "Java 微服务后端架构"), ev("b", "前端组件设计"));
        Result result = ProfilePersonalizer.personalize(signal, candidates, 2);
        assertThat(result.evidences()).extracting(Evidence::nodeId).containsExactly("a", "b");
        assertThat(result.boostedCount()).isEqualTo(1);
    }

    @Test
    void asciiTokensMatchOnWordBoundaryOnly() {
        // "go" 不得命中 "google"; "go 语言" 的 CJK 词元走子串匹配
        assertThat(ProfilePersonalizer.matches("google cloud 入门", "go")).isFalse();
        assertThat(ProfilePersonalizer.matches("从 go 到 cloud", "go")).isTrue();
        assertThat(ProfilePersonalizer.matches("golang 基础", "go")).isFalse();
        assertThat(ProfilePersonalizer.matches("消息队列选型", "队列")).isTrue();
        // 词元太短不参与
        assertThat(ProfilePersonalizer.matches("k8s 实战", "k8")).isFalse();
    }

    @Test
    void stableOrderForEqualBoost() {
        Signal signal = ProfilePersonalizer.fromProfile(Map.of("target_position", "后端"));
        List<Evidence> candidates = List.of(ev("a", "后端缓存"), ev("b", "后端网关"), ev("c", "无关"));
        Result result = ProfilePersonalizer.personalize(signal, candidates, 3);
        // a/b 同等加成 → 保持检索原序
        assertThat(result.evidences()).extracting(Evidence::nodeId).containsExactly("a", "b", "c");
    }

    @Test
    void personalizeKeepsOriginalScoreFieldsUntouched() {
        Signal signal = ProfilePersonalizer.fromProfile(Map.of("target_position", "后端"));
        Evidence candidate = ev("a", "后端技能图谱");
        Result result = ProfilePersonalizer.personalize(signal, List.of(candidate), 1);
        // 只重排不改写: score/来源标注等字段保持检索内核原值, 引用链路不受影响
        assertThat(result.evidences().getFirst()).isSameAs(candidate);
    }
}
