package com.tutor.context;

/**
 * 预算压力的最小视图，供上下文规划器在不依赖 llm 包实现的情况下感知全局水位。
 * 由 {@link com.tutor.llm.BudgetPressureService} 实现。
 */
public interface BudgetPressureView {
    /** SEVERE 及以上：关键分区保底减半，优先保住本轮可用性。 */
    boolean severePressure();
}
