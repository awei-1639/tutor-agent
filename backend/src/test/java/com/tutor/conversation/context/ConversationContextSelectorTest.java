package com.tutor.conversation.context;

import com.tutor.conversation.memory.local.ConversationStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationContextSelectorTest {
    @Test
    void keepsRecentMessagesAndPrefersRelevantOlderMessages() {
        List<ConversationStore.Msg> history = List.of(
                new ConversationStore.Msg(1, "user", "讨论 Java 后端基础"),
                new ConversationStore.Msg(2, "assistant", "可以继续学习 Spring Boot"),
                new ConversationStore.Msg(3, "user", "闲聊天气"),
                new ConversationStore.Msg(4, "assistant", "今天晴天"),
                new ConversationStore.Msg(5, "user", "我想继续学习 Java 后端"),
                new ConversationStore.Msg(6, "assistant", "可以从 Spring Boot 开始"));

        List<ConversationStore.Msg> selected = ConversationContextSelector.select(history, "Java 后端学习", 4);

        assertThat(selected).extracting(message -> message.id).contains(5L, 6L);
        assertThat(selected).extracting(message -> message.id).contains(1L, 2L);
        assertThat(selected).extracting(message -> message.id).doesNotContain(3L, 4L);
    }

    @Test
    void keepsChronologicalOrderAndHandlesSmallInputs() {
        List<ConversationStore.Msg> history = List.of(
                new ConversationStore.Msg(1, "user", "第一句"),
                new ConversationStore.Msg(2, "assistant", "第二句"));

        assertThat(ConversationContextSelector.select(history, "无关问题", 10))
                .extracting(message -> message.id).containsExactly(1L, 2L);
        assertThat(ConversationContextSelector.select(history, "问题", 0)).isEmpty();
    }

    @Test
    void prioritizesTechnicalAndDomainTermsInLongQuestions() {
        List<ConversationStore.Msg> history = List.of(
                new ConversationStore.Msg(1, "user", "这是一段可以继续讨论的普通内容"),
                new ConversationStore.Msg(2, "assistant", "Spring Boot 后端开发可以从项目实践开始"),
                new ConversationStore.Msg(3, "user", "最近天气不错"),
                new ConversationStore.Msg(4, "assistant", "好的，我们继续"));

        String question = "请你帮我分析一下这个问题，制定一个后端学习计划。"
                + "我希望了解 Java、Spring Boot、API、JWT 和上下文工程，"
                + "同时说明为什么这样安排以及后续如何优化。".repeat(20);

        assertThat(ConversationContextSelector.select(history, question, 3))
                .extracting(message -> message.id)
                .containsExactly(2L, 3L, 4L);
    }

    @Test
    void addsPreviousAssistantReplyForPronounFollowUp() {
        List<ConversationStore.Msg> history = List.of(
                new ConversationStore.Msg(1, "user", "我想学习 Spring Boot"),
                new ConversationStore.Msg(2, "assistant", "可以先做一个用户登录项目"),
                new ConversationStore.Msg(3, "user", "我每天只有一小时"));

        assertThat(ConversationContextSelector.routerContext(history, "这个怎么实现？"))
                .containsExactly(
                        "我想学习 Spring Boot",
                        "我每天只有一小时",
                        "[相关上一轮回复] 可以先做一个用户登录项目");
    }

    @Test
    void keepsRouterContextMinimalForNormalQuestions() {
        List<ConversationStore.Msg> history = List.of(
                new ConversationStore.Msg(1, "user", "我想学习 Java"),
                new ConversationStore.Msg(2, "assistant", "可以从基础开始"),
                new ConversationStore.Msg(3, "user", "请制定学习计划"));

        assertThat(ConversationContextSelector.routerContext(history, "分析后端岗位"))
                .containsExactly("我想学习 Java", "请制定学习计划");
    }

    @Test
    void contextualizesPronounQuestionForDownstreamRetrieval() {
        List<ConversationStore.Msg> history = List.of(
                new ConversationStore.Msg(1, "user", "我想学习 Spring Boot"),
                new ConversationStore.Msg(2, "assistant", "可以先做一个用户登录项目"));

        assertThat(ConversationContextSelector.contextualize("这个怎么实现？", history))
                .isEqualTo("相关上下文主题：可以先做一个用户登录项目\n当前问题：这个怎么实现？");
        assertThat(ConversationContextSelector.contextualize("分析后端岗位", history))
                .isEqualTo("分析后端岗位");
    }
}
