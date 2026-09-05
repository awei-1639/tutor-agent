package com.tutor.memory.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 确定性重要性门控：显著性信号命中、长消息兜底与闲聊拒判。 */
class MemoryImportanceGateTest {

    private final MemoryImportanceGate gate = new MemoryImportanceGate();

    @Test
    @DisplayName("目标/偏好/纠正/规划/掌握度信号命中")
    void salientStatementsPass() {
        assertThat(gate.hasSalientSignal("用户: 我想找一份后端开发的工作")).isTrue();
        assertThat(gate.hasSalientSignal("用户: 我偏好中文讲解和代码示例")).isTrue();
        assertThat(gate.hasSalientSignal("用户: 你理解错了，我说的是Redis缓存")).isTrue();
        assertThat(gate.hasSalientSignal("用户: 帮我制定一个两周复习计划")).isTrue();
        assertThat(gate.hasSalientSignal("用户: 我每周只有10小时学习时间")).isTrue();
        assertThat(gate.hasSalientSignal("用户: 消息队列我不太熟悉")).isTrue();
        assertThat(gate.hasSalientSignal("用户: 我是计算机专业的应届生")).isTrue();
    }

    @Test
    @DisplayName("闲聊窗口拒判")
    void smalltalkRejected() {
        assertThat(gate.hasSalientSignal("用户: 好的，谢谢你\n用户: 哈哈有意思\n用户: 那先这样")).isFalse();
        assertThat(gate.hasSalientSignal("用户: 今天天气怎么样")).isFalse();
    }

    @Test
    @DisplayName("超长消息兜底命中")
    void longMessagePasses() {
        String longLine = "用户: " + "这个问题我琢磨了很久，从架构设计的角度想请教".repeat(6);
        assertThat(longLine.length()).isGreaterThan(MemoryImportanceGate.SALIENT_MESSAGE_CHARS);
        assertThat(gate.hasSalientSignal(longLine)).isTrue();
    }

    @Test
    @DisplayName("空输入拒判")
    void blankRejected() {
        assertThat(gate.hasSalientSignal(null)).isFalse();
        assertThat(gate.hasSalientSignal("   ")).isFalse();
    }
}
