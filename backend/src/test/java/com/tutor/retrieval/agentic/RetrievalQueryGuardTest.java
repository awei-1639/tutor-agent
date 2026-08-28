package com.tutor.retrieval.agentic;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalQueryGuardTest {

    @Test
    void rejectsDuplicateOversizedAndOffTopicCandidates() {
        assertThat(RetrievalQueryGuard.sanitize(
                "零基础学 NLP", "零基础学 NLP", "NLP 基础", Set.of("nlp 基础")))
                .isNull();
        assertThat(RetrievalQueryGuard.sanitize(
                "零基础学 NLP", "零基础学 NLP", "NLP".repeat(241), Set.of()))
                .isNull();
        assertThat(RetrievalQueryGuard.sanitize(
                "零基础学 NLP", "零基础学 NLP", "完全无关的数据库索引", Set.of()))
                .isNull();
    }

    @Test
    void cleansControlCharactersAndCollapsesWhitespace() {
        assertThat(RetrievalQueryGuard.sanitize(
                "神经网络怎么学", "神经网络怎么学", "神经网络\n\t前置知识", Set.of()))
                .isEqualTo("神经网络 前置知识");
    }

    @Test
    void fallbackKeepsCoreTopicAndAddsBoundedHint() {
        assertThat(RetrievalQueryGuard.missingFallback(
                "零基础想学神经网络，需要哪些前置知识？", "线性代数、概率统计"))
                .isEqualTo("神经网络 前置知识 线性代数、概率统计");
        assertThat(RetrievalQueryGuard.narrowFallback(
                "零基础想学神经网络，需要哪些前置知识？", 2))
                .isEqualTo("神经网络 前置知识 依赖关系 底层原理");
    }
}
