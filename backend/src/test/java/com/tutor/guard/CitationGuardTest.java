package com.tutor.guard;

import com.tutor.contract.Evidence;
import com.tutor.llm.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 引用护栏 (Phase 3 V4 3.4): 解析 [S#] 引用 + 调 judge LLM; 失败降级不阻断。
 */
@ExtendWith(MockitoExtension.class)
class CitationGuardTest {

    @Mock LlmGateway gateway;
    CitationGuard guard;

    @BeforeEach
    void setUp() {
        guard = new CitationGuard(gateway);
    }

    private final List<Evidence> evs = List.of(
            new Evidence("skill:foo", "skill", "事实A: 神经网络是深度学习基础", 0.9, null),
            new Evidence("res:bar", "resource", "事实B: 推荐CS231n课程", 0.8, null));

    @Test
    @DisplayName("无引用回答 → 0 supported, rate=1.0 (按护栏视角, 无引用即 100% 未支撑)")
    void noCitations() {
        var r = guard.guard("纯粹的寒暄回答, 没有任何数据", evs, "t1");
        assertThat(r.supportRate()).isEqualTo(1.0);
        assertThat(r.supported()).isEqualTo(0);
    }

    @Test
    @DisplayName("LLM 返回合法 JSON → 解析 supported/unsupported")
    void parseValid() {
        when(gateway.chatJson(any(), any(), anyString())).thenReturn(
                "{\"claims\":[{\"text\":\"a\",\"sid\":\"S1\",\"verdict\":\"supported\"}," +
                "{\"text\":\"b\",\"sid\":\"S2\",\"verdict\":\"unsupported\"}],\"summary\":\"1/2\"}");
        var r = guard.guard("回答 [S1] 与 [S2]", evs, "t2");
        assertThat(r.supported()).isEqualTo(1);
        assertThat(r.unsupported()).isEqualTo(1);
        assertThat(r.supportRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("LLM 失败 → 静默降级 (返回 0 supported, rate=0)")
    void llmFailureFallback() {
        when(gateway.chatJson(any(), any(), anyString())).thenThrow(new RuntimeException("网络超时"));
        var r = guard.guard("回答 [S1]", evs, "t3");
        assertThat(r.supported()).isEqualTo(0);
        assertThat(r.unsupported()).isEqualTo(1);
        assertThat(r.supportRate()).isEqualTo(0.0);
    }
}