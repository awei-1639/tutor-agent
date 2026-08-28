package com.tutor.context;

import com.tutor.memory.local.ConversationStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextualQueryRewriterTest {
    private final ContextualQueryRewriter rewriter =
            new ContextualQueryRewriter(new CoreferenceResolver());

    @Test
    void combinesCoreferenceIntoOneStandaloneQuery() {
        ContextualQueryRewriter.RewriteResult result = rewriter.rewrite(
                "这个项目用了什么数据库？",
                List.of(new ConversationStore.Msg(
                        "assistant", "上一轮讨论的是订单系统项目。")),
                "trace"
        );

        assertThat(result.standaloneQuery())
                .isEqualTo("订单系统项目用了什么数据库？");
        assertThat(result.mode())
                .isEqualTo(ContextualQueryRewriter.Mode.COREFERENCE);
    }

    @Test
    void usesAnchorOnlyForShortUnresolvedFollowUpWithoutExplicitReference() {
        ContextualQueryRewriter.RewriteResult result = rewriter.rewrite(
                "继续",
                List.of(
                        new ConversationStore.Msg("user", "我想学习 Spring Boot"),
                        new ConversationStore.Msg("assistant", "可以先做一个用户登录项目")
                ),
                "trace"
        );

        assertThat(result.standaloneQuery())
                .isEqualTo("相关上下文主题：可以先做一个用户登录项目\n当前问题：继续");
        assertThat(result.mode())
                .isEqualTo(ContextualQueryRewriter.Mode.CONTEXT_ANCHOR_FALLBACK);
    }

    @Test
    void doesNotAddHistoryToExplicitQuery() {
        ContextualQueryRewriter.RewriteResult result = rewriter.rewrite(
                "分析后端岗位的学历要求",
                List.of(new ConversationStore.Msg(
                        "assistant", "可以先做一个用户登录项目")),
                "trace"
        );

        assertThat(result.standaloneQuery())
                .isEqualTo("分析后端岗位的学历要求");
        assertThat(result.mode())
                .isEqualTo(ContextualQueryRewriter.Mode.UNCHANGED);
    }
}
