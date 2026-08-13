package com.tutor.context;

import java.util.List;

/**
 * Prompt 分区接口 (实现设计 3.3)。
 * 每个分区独立可单测; 新增分区 = 新增实现类, PromptAssembler 不改。
 * 分区顺序即缓存前缀顺序: 静态在前、高频变化在后 (3.2 前缀稳定原则)。
 */
public interface ContextSection {
    record CitationMarker(String id, int endOffset) {}
    record Rendered(String text, List<CitationMarker> citationMarkers) {
        public Rendered {
            citationMarkers = citationMarkers == null ? List.of() : List.copyOf(citationMarkers);
        }
    }

    String name();

    /** 本区 token 预算上限 (超限由各区自己的 render 负责裁剪) */
    int budget();

    /** 渲染本区文本; 返回空串表示本轮不注入 */
    String render(TurnContextView ctx, TokenBudget budget);

    /**
     * Renders a section together with trusted metadata.  EvidenceSection uses
     * this to retain the exact citation ids that survived local rendering;
     * ordinary sections keep the default empty metadata.
     */
    default Rendered renderWithMetadata(TurnContextView ctx, TokenBudget budget) {
        return new Rendered(render(ctx, budget), List.of());
    }
}
