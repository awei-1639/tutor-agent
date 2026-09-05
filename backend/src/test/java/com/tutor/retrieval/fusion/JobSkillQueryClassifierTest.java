package com.tutor.retrieval.fusion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobSkillQueryClassifierTest {

    @Test
    void firesOnRequirementPhrasingsFromTheEvalSlice() {
        List<String> queries = List.of(
                "腾讯做对话系统的NLP算法工程师，得会啥技能啊？",
                "天眼科技招遥感图像CV工程师，需要掌握哪些技术？",
                "移视科技的移动端优化CV岗，要会哪些技能？",
                "腾讯的大模型应用开发工程师，需要什么能力？",
                "安视科技做安防监控的CV工程师，得会啥？",
                "百度的强化学习算法实习生，要掌握哪些东西？");
        for (String query : queries) {
            assertThat(JobSkillQueryClassifier.classify(query).seeking())
                    .as("应该判定为技能寻求型: %s", query)
                    .isTrue();
        }
    }

    @Test
    void doesNotFireOnRecommendationOrDefinitionQueries() {
        List<String> queries = List.of(
                "推荐几个NLP算法岗的职位",
                "transformers的自注意力机制原理是什么",
                "推荐PyTorch入门学习资料",
                "有哪些讲RAG的公开课");
        for (String query : queries) {
            assertThat(JobSkillQueryClassifier.classify(query).seeking())
                    .as("不应判定为技能寻求型: %s", query)
                    .isFalse();
        }
    }

    @Test
    void blankQueryIsNotSeeking() {
        assertThat(JobSkillQueryClassifier.classify(null).seeking()).isFalse();
        assertThat(JobSkillQueryClassifier.classify("  ").seeking()).isFalse();
    }
}
