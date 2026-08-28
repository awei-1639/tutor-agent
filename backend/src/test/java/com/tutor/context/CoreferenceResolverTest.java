package com.tutor.context;

import com.tutor.memory.local.ConversationStore;
import com.tutor.contract.Purpose;
import com.tutor.llm.JsonGenerationGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoreferenceResolverTest {
    private final CoreferenceResolver resolver = new CoreferenceResolver();

    @Test
    void resolvesTypedProjectReference() {
        CoreferenceResult result = resolver.resolve(
                "这个项目用了什么数据库？",
                List.of(new ConversationStore.Msg(
                        "assistant", "我们上一轮讨论的是订单系统项目。"))
        );

        assertThat(result.resolvedQuery())
                .isEqualTo("订单系统项目用了什么数据库？");
        assertThat(result.needsClarification()).isFalse();
        assertThat(result.references()).singleElement()
                .extracting(CoreferenceResult.Reference::confidence)
                .isEqualTo(0.96D);
    }

    @Test
    void resolvesItToTheOnlyRecentSystem() {
        CoreferenceResult result = resolver.resolve(
                "它支持分库分表吗？",
                List.of(new ConversationStore.Msg(
                        "assistant", "订单系统使用了 Redis。"))
        );

        assertThat(result.resolvedQuery())
                .isEqualTo("订单系统支持分库分表吗？");
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    void asksForClarificationWhenTwoProjectsAreInTheSameTurn() {
        CoreferenceResult result = resolver.resolve(
                "这个项目用了什么数据库？",
                List.of(new ConversationStore.Msg(
                        "assistant", "订单系统项目和推荐系统项目都值得保留。"))
        );

        assertThat(result.needsClarification()).isTrue();
        assertThat(result.resolvedQuery())
                .isEqualTo("这个项目用了什么数据库？");
    }

    @Test
    void resolvesFormerAndLatterFromMentionOrder() {
        List<ConversationStore.Msg> history = List.of(
                new ConversationStore.Msg("user", "我比较订单系统项目和推荐系统项目。"));

        assertThat(resolver.resolve("前者用了什么数据库？", history).resolvedQuery())
                .isEqualTo("订单系统项目用了什么数据库？");
        assertThat(resolver.resolve("后者用了什么模型？", history).resolvedQuery())
                .isEqualTo("推荐系统项目用了什么模型？");
    }

    @Test
    void leavesExplicitQuestionUnchanged() {
        CoreferenceResult result = resolver.resolve(
                "订单系统支持分库分表吗？",
                List.of()
        );

        assertThat(result).isEqualTo(CoreferenceResult.unchanged(
                "订单系统支持分库分表吗？"));
    }

    @Test
    void usesLlmFallbackForPersonReferenceAndValidatesHistoryEntity() {
        JsonGenerationGateway gateway = mock(JsonGenerationGateway.class);
        when(gateway.chatJson(eq(Purpose.EXTRACT), anyList(), eq("trace-1"),
                isNull(), eq(1)))
                .thenReturn("""
                        {"resolved_query":"张三负责什么？","resolved_to":"张三",
                         "confidence":0.93,"needs_clarification":false}
                        """);

        CoreferenceResult result = new CoreferenceResolver(gateway).resolve(
                "他负责什么？",
                List.of(new ConversationStore.Msg(
                        "assistant", "张三负责订单系统项目。")),
                "trace-1"
        );

        assertThat(result.resolvedQuery()).isEqualTo("张三负责什么？");
        assertThat(result.needsClarification()).isFalse();
        verify(gateway).chatJson(eq(Purpose.EXTRACT), anyList(), eq("trace-1"),
                isNull(), eq(1));
    }

    @Test
    void rejectsLlmEntityThatDoesNotAppearInHistory() {
        JsonGenerationGateway gateway = mock(JsonGenerationGateway.class);
        when(gateway.chatJson(eq(Purpose.EXTRACT), anyList(), eq("trace-2"),
                isNull(), eq(1)))
                .thenReturn("""
                        {"resolved_query":"王五负责什么？","resolved_to":"王五",
                         "confidence":0.99,"needs_clarification":false}
                        """);

        CoreferenceResult result = new CoreferenceResolver(gateway).resolve(
                "他负责什么？",
                List.of(new ConversationStore.Msg(
                        "assistant", "张三负责订单系统项目。")),
                "trace-2"
        );

        assertThat(result.needsClarification()).isTrue();
        assertThat(result.resolvedQuery()).isEqualTo("他负责什么？");
    }
}
