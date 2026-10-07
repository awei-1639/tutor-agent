package com.tutor.conversation.chat.application;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 交付不变量 (2026-10-06): 一个回合结束时, 必须满足下列之一——
 * 已交付回答 token/思考增量、已发送追问、已发送明确错误、或取消方主动放弃。
 * 否则即"静默死亡": 后端持久化了答案而前端永远等不到 (工具循环空泡 bug 的根因)。
 * 纯计数判定, 由 ChatTurnWorker 的事件包装器喂养。
 */
final class TurnDeliveryGuard {
    private final AtomicInteger tokens = new AtomicInteger();
    private final AtomicInteger reasoning = new AtomicInteger();
    private final AtomicBoolean clarify = new AtomicBoolean();
    private final AtomicBoolean done = new AtomicBoolean();
    private final AtomicBoolean error = new AtomicBoolean();

    void onToken() { tokens.incrementAndGet(); }
    void onReasoning() { reasoning.incrementAndGet(); }
    void onClarify() { clarify.set(true); }
    void onDone() { done.set(true); }
    void onError() { error.set(true); }
    void onCancel() { error.set(true); /* 取消视作显式终态, 不计入静默死亡 */ }

    int tokensDelivered() { return tokens.get(); }
    int reasoningsDelivered() { return reasoning.get(); }

    /**
     * 回合结束后的判定。返回问题描述 (可观测/告警用); empty 表示交付合规。
     *
     * @param cancelled 取消方主动放弃 (用户停止/通道超时), 不算静默死亡
     */
    Optional<String> verdict(boolean cancelled) {
        if (cancelled) return Optional.empty();
        if (error.get()) return Optional.empty();
        if (!done.get()) return Optional.of("stream ended without done event (silent death)");
        if (done.get() && !clarify.get() && tokens.get() == 0 && reasoning.get() == 0) {
            return Optional.of("done delivered but no answer tokens (empty-bubble class)");
        }
        return Optional.empty();
    }
}
