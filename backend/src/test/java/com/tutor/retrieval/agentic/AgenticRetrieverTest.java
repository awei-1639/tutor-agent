package com.tutor.retrieval.agentic;

import com.tutor.retrieval.agentic.AgenticRetriever;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgenticRetrieverTest {

    @Test
    @DisplayName("触发正则: 多跳关键词命中")
    void triggerMatches() {
        assertThat(AgenticRetriever.isMultiHopQuery("零基础想学NLP, 需要学什么前置?")).isTrue();
        assertThat(AgenticRetriever.isMultiHopQuery("如何规划学习路径?")).isTrue();
        assertThat(AgenticRetriever.isMultiHopQuery("什么是LoRA?")).isFalse();
        assertThat(AgenticRetriever.isMultiHopQuery("推荐NLP岗位")).isFalse();
    }

    @Test
    @DisplayName("parse: 合法 JSON → sufficient+followup")
    void parseValid() {
        AgenticRetriever.JudgeDecision d = AgenticRetriever.parse(
                "{\"sufficient\":false,\"followup_query\":\"查Python基础\"}");
        assertThat(d).isNotNull();
        assertThat(d.sufficient()).isFalse();
        assertThat(d.followupQuery()).isEqualTo("查Python基础");
    }

    @Test
    @DisplayName("parse: 充分时 followup 为 null")
    void parseSufficient() {
        AgenticRetriever.JudgeDecision d = AgenticRetriever.parse(
                "{\"sufficient\":true,\"followup_query\":null}");
        assertThat(d.sufficient()).isTrue();
        assertThat(d.followupQuery()).isNull();
    }

    @Test
    @DisplayName("parse: 非法 JSON → null (调用方降级)")
    void parseInvalidReturnsNull() {
        assertThat(AgenticRetriever.parse("not json")).isNull();
        assertThat(AgenticRetriever.parse("{}")).isNotNull(); // 空对象合法, sufficient 默认 false
    }

    @Test
    @DisplayName("parse: 缺字段默认 sufficient=false 跳出循环 (因为 followup 也会为空)")
    void parseMissingFields() {
        AgenticRetriever.JudgeDecision d = AgenticRetriever.parse("{\"foo\":\"bar\"}");
        assertThat(d).isNotNull();
        assertThat(d.sufficient()).isFalse();
        assertThat(d.followupQuery()).isNull();
    }
}
