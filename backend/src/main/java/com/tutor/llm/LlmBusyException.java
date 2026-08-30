package com.tutor.llm;

/** LLM 并发队列已满等瞬时繁忙；属于可重试状态，服务层映射为友好提示而非内部错误。 */
public class LlmBusyException extends RuntimeException {
    public LlmBusyException(String message) { super(message); }
}
