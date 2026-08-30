package com.tutor.llm;

/** 预算硬上限触发。服务层据此映射稳定的错误码与用户文案，不再透传内部异常消息。 */
public class BudgetExhausted extends RuntimeException {
    public enum Kind { TURN, USER_DAILY, GLOBAL, BACKGROUND_DEFERRED }

    private final Kind kind;

    public BudgetExhausted(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() { return kind; }

    /** SSE error 事件与前端文案映射使用的稳定机器码。 */
    public String code() { return "budget_" + kind.name().toLowerCase(); }

    /** 面向用户的文案；预留/限额等内部术语不直接暴露。 */
    public String userMessage() {
        return switch (kind) {
            case TURN -> "本条消息需要的处理量超出单轮上限，请把问题拆小一些再试。";
            case USER_DAILY -> "你今天的 AI 额度已用完，明天 0 点自动恢复。";
            case GLOBAL -> "当前时段使用人数较多，请稍后再试。";
            case BACKGROUND_DEFERRED -> "系统繁忙，该任务已顺延，稍后会自动重试。";
        };
    }
}
