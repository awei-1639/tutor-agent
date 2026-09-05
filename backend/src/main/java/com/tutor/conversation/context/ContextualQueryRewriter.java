package com.tutor.conversation.context;

import com.tutor.conversation.memory.local.ConversationStore;

import java.util.List;
import java.util.Objects;

/**
 * 用户查询进入路由和检索前的唯一上下文改写入口。
 *
 * 代词消解是其中一个子步骤；短而没有明确主题的追问则使用历史主题锚点降级。
 * 检索过程中由 AgenticRetriever 生成的证据缺口子查询不属于本类职责。
 */
public final class ContextualQueryRewriter {
    private final CoreferenceResolver coreferenceResolver;

    public ContextualQueryRewriter(CoreferenceResolver coreferenceResolver) {
        this.coreferenceResolver = coreferenceResolver;
    }

    public RewriteResult rewrite(
            String question,
            List<ConversationStore.Msg> history,
            String traceId
    ) {
        CoreferenceResult coreference =
                coreferenceResolver.resolve(question, history, traceId);

        if (coreference.needsClarification()) {
            return new RewriteResult(
                    coreference.originalQuery(),
                    coreference.resolvedQuery(),
                    coreference.references(),
                    true,
                    Mode.CLARIFICATION
            );
        }

        if (!Objects.equals(coreference.resolvedQuery(), question)) {
            return new RewriteResult(
                    coreference.originalQuery(),
                    coreference.resolvedQuery(),
                    coreference.references(),
                    false,
                    Mode.COREFERENCE
            );
        }

        // 只有没有明确主题的短追问才使用主题锚点；明确问题保持原文，避免污染 embedding。
        if (ConversationContextSelector.needsContextAnchor(question)) {
            String contextualized =
                    ConversationContextSelector.contextualize(question, history);
            if (!contextualized.equals(question)) {
                return new RewriteResult(
                        question,
                        contextualized,
                        List.of(),
                        false,
                        Mode.CONTEXT_ANCHOR_FALLBACK
                );
            }
        }

        return new RewriteResult(
                question,
                question,
                List.of(),
                false,
                Mode.UNCHANGED
        );
    }

    public enum Mode {
        UNCHANGED,
        COREFERENCE,
        CONTEXT_ANCHOR_FALLBACK,
        CLARIFICATION
    }

    public record RewriteResult(
            String originalQuery,
            String standaloneQuery,
            List<CoreferenceResult.Reference> references,
            boolean needsClarification,
            Mode mode
    ) {
        public RewriteResult {
            references = references == null ? List.of() : List.copyOf(references);
            mode = mode == null ? Mode.UNCHANGED : mode;
        }
    }
}
