package com.tutor.agent.expert;

import com.tutor.contract.Intent;
import com.tutor.platform.config.LlmProperties;
import com.tutor.platform.text.TokenBudget;
import com.tutor.contract.Evidence;
import com.tutor.contract.ExpertOutput;
import com.tutor.contract.Purpose;
import com.tutor.contract.CancellationToken;
import com.tutor.platform.llm.LlmGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpertRunnerTest {
    @Mock
    LlmGateway gateway;

    private ExpertRunner runner;

    @BeforeEach
    void setUp() {
        // 批次 deadline 不能取 1s：两个取消用例要求"专家任务在 deadline 到期前真正启动"，
        // 而 JVM 预热(加载分词表)或机器繁忙都可能让任务调度晚于 1s，届时任务会被 deadline
        // 直接判超时、根本不会调用网关，用例就退化成偶发失败。留足余量只增加耗时，不改语义。
        runner = new ExpertRunner(gateway, new TokenBudget(), properties(5));
    }

    @AfterEach
    void tearDown() {
        runner.shutdownExecutor();
    }

    @Test
    void keepsCurrentQuestionAfterContextTruncation() {
        ExpertRunner.Briefing result = runner.buildBriefing(
                "历史画像 ".repeat(5000),
                List.of(new Evidence("skill:agent", "skill", "证据 ".repeat(2000), 0.9, null, null, null, null)),
                "请分析 Agent 的失败重试和幂等设计");
        String briefing = result.text();

        assertThat(briefing).contains("<request>")
                .contains("请分析 Agent 的失败重试和幂等设计")
                .endsWith("</request>");
        assertThat(result.usage().profileOriginalTokens())
                .isGreaterThanOrEqualTo(result.usage().profileAllocatedTokens());
        assertThat(result.usage().questionTokens()).isGreaterThan(0);
    }

    @Test
    void citationIdsOnlyIncludeEvidenceBlocksThatSurvivedBudgeting() {
        List<Evidence> evidences = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> new Evidence("skill:" + i, "skill", "证据 " + i + "复杂内容".repeat(5000), 0.9, null, null, null, null))
                .toList();

        ExpertRunner.Briefing briefing = runner.buildBriefing("", evidences, "请分析这些证据");

        assertThat(briefing.citationIds()).contains("S1").doesNotContain("S10");
        assertThat(briefing.text()).contains("[S1]").doesNotContain("[S10]");
    }

    @Test
    void rejectsUnknownOrTooManyExpertsBeforeCallingGateway() {
        assertThatThrownBy(() -> runner.run(List.of("unknown"), "briefing", "trace", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知专家");
        assertThatThrownBy(() -> runner.run(List.of("resume", "interview", "planner", "resume2"),
                "briefing", "trace", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多执行");
    }

    @Test
    void validatesStructuredExpertOutput() {
        when(gateway.chatJson(eq(Purpose.EXPERT), anyList(), eq("trace"), any(Duration.class), eq(1)))
                .thenReturn("{\"advice\":[],\"confidence\":1.4,\"citations\":[\"S1\"]}");

        assertThatThrownBy(() -> runner.run(List.of("resume"), "briefing", "trace", null))
                .isInstanceOf(ExpertRunner.ExpertUnavailableException.class);
    }

    @Test
    void mixedSubIntentsDispatchOnlyRequestedExperts() {
        assertThat(ExpertRunner.expertsFor(List.of(Intent.RESUME, Intent.INTERVIEW)))
                .containsExactly("resume", "interview");
    }

    @Test
    void acceptsValidStructuredExpertOutput() {
        when(gateway.chatJson(eq(Purpose.EXPERT), anyList(), eq("trace"), any(Duration.class), eq(1)))
                .thenReturn("{\"advice\":[{\"point\":\"p\",\"reason\":\"r\",\"priority\":1}],"
                        + "\"confidence\":0.8,\"citations\":[\"S1\"]}");

        List<ExpertOutput> outputs = runner.run(List.of("resume"), "briefing", "trace", null,
                new CancellationToken(), Set.of("S1"));

        assertThat(outputs).hasSize(1);
        assertThat(outputs.getFirst().confidence()).isEqualTo(0.8);
        assertThat(outputs.getFirst().citations()).containsExactly("S1");
    }

    @Test
    void rejectsCitationOutsideTheRetrievedEvidenceSet() {
        when(gateway.chatJson(eq(Purpose.EXPERT), anyList(), eq("trace"), any(Duration.class), eq(1)))
                .thenReturn("{\"advice\":[{\"point\":\"p\",\"reason\":\"r\",\"priority\":1}],"
                        + "\"confidence\":0.8,\"citations\":[\"S99\"]}");

        assertThatThrownBy(() -> runner.run(List.of("resume"), "briefing", "trace", null,
                new CancellationToken(), Set.of("S1", "S2")))
                .isInstanceOf(ExpertRunner.ExpertUnavailableException.class);
    }

    @Test
    void rejectsMalformedExpertSpecificItem() {
        when(gateway.chatJson(eq(Purpose.EXPERT), anyList(), eq("trace"), any(Duration.class), eq(1)))
                .thenReturn("{\"advice\":[{\"point\":\"p\",\"reason\":\"r\",\"priority\":99}],"
                        + "\"confidence\":0.8,\"citations\":[]}");

        assertThatThrownBy(() -> runner.run(List.of("resume"), "briefing", "trace", null,
                new CancellationToken(), Set.of()))
                .isInstanceOf(ExpertRunner.ExpertUnavailableException.class);
    }

    @Test
    void cancelsUnderlyingExpertTaskWhenDeadlineExpires() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        when(gateway.chatJson(eq(Purpose.EXPERT), anyList(), eq("trace"), any(Duration.class), eq(1)))
                .thenAnswer(invocation -> {
                    started.countDown();
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                        throw new IllegalStateException("interrupted");
                    }
                    return "{\"advice\":[],\"confidence\":0.5}";
                });

        assertThatThrownBy(() -> runner.run(List.of("resume"), "briefing", "trace", null))
                .isInstanceOf(ExpertRunner.ExpertUnavailableException.class);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitTrue(interrupted)).isTrue();
    }

    @Test
    void cancelsUnderlyingExpertTaskWhenRequestIsAborted() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        CancellationToken cancellation = new CancellationToken();
        AtomicReference<List<ExpertOutput>> result = new AtomicReference<>();
        when(gateway.chatJson(eq(Purpose.EXPERT), anyList(), eq("trace"), any(Duration.class), eq(1)))
                .thenAnswer(invocation -> {
                    started.countDown();
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                        throw new IllegalStateException("interrupted");
                    }
                    return "{\"advice\":[],\"confidence\":0.5}";
                });

        Thread run = Thread.startVirtualThread(() -> result.set(
                runner.run(List.of("resume"), "briefing", "trace", null, cancellation)));

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        cancellation.cancel();
        run.join(5_000);

        assertThat(run.isAlive()).isFalse();
        assertThat(result.get()).isEmpty();
        assertThat(awaitTrue(interrupted)).isTrue();
    }

    /** 取消与中断是异步投递的：future 先异常完成、interrupt 后到达，必须轮询等待而不是立即断言。 */
    private static boolean awaitTrue(AtomicBoolean flag) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!flag.get() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return flag.get();
    }

    private static LlmProperties properties(int expertSeconds) {
        return new LlmProperties(
                new LlmProperties.Endpoint("deepseek-key", "https://api.deepseek.com"),
                new LlmProperties.Endpoint("silicon-key", "https://api.siliconflow.cn/v1"),
                Map.of("chat", "chat", "router", "router", "expert", "expert", "summary", "summary",
                        "extract", "extract", "embed", "embed"),
                new LlmProperties.Budget(100_000, 10_000),
                new LlmProperties.Timeout(1, 60, 120, expertSeconds), LlmProperties.TokenLimits.defaults());
    }
}
