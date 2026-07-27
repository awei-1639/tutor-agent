package com.tutor.context;

/**
 * Prompt 分区接口 (实现设计 3.3)。
 * 每个分区独立可单测; 新增分区 = 新增实现类, PromptAssembler 不改。
 * 分区顺序即缓存前缀顺序: 静态在前、高频变化在后 (3.2 前缀稳定原则)。
 */
public interface ContextSection {
    String name();

    /** 本区 token 预算上限 (超限由各区自己的 render 负责裁剪) */
    int budget();

    /** 渲染本区文本; 返回空串表示本轮不注入 */
    String render(TurnContextView ctx, TokenBudget budget);
}
