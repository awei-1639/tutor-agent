package com.tutor.knowledge.retrieval.fusion;

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
    void firesOnSynonymPhrasingsThatTheFirstVersionMissed() {
        // 评测集里同样在问技能清单, 但动词是「要求什么/具备哪些/要啥」——首版漏判 7/60。
        List<String> queries = List.of(
                "视界科技的NLP多模态岗，要求什么技能？",
                "华为的大模型应用开发工程师，要求哪些技术？",
                "网易的算法岗要求什么呀？",
                "字节跳动的算法实习生，得具备什么能力？",
                "三维视界的计算机视觉工程师（三维重建），得懂哪些东西？",
                "百川智能大模型训练实习岗要啥技能？");
        for (String query : queries) {
            assertThat(JobSkillQueryClassifier.classify(query).seeking())
                    .as("应该判定为技能寻求型: %s", query)
                    .isTrue();
        }
    }

    @Test
    void doesNotFireWithoutRoleContextEvenIfADemandVerbIsPresent() {
        // 「需要改哪些层」是学习建议, 不是岗位需求; 缺岗位语境时不得触发答案形态转换,
        // 否则会把一个无关岗位的 REQUIRES 技能塞进答案（实测会让 single_hop 切片掉 5pp）。
        List<String> queries = List.of(
                "微调BERT做情感分析需要改哪些层？",
                "做数据清洗需要掌握哪些工具？");
        for (String query : queries) {
            assertThat(JobSkillQueryClassifier.classify(query).seeking())
                    .as("无岗位语境不应判定为技能寻求型: %s", query)
                    .isFalse();
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
