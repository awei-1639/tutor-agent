package com.tutor.platform.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class LlmConcurrencyGate {
    private final Semaphore permits;

    public LlmConcurrencyGate(@Value("${llm.concurrency.max-in-flight:16}") int maxInFlight) {
        if (maxInFlight < 1) throw new IllegalArgumentException("llm concurrency must be positive");
        permits = new Semaphore(maxInFlight);
    }

    public void acquire() {
        try {
            if (!permits.tryAcquire(5, TimeUnit.SECONDS)) {
                throw new LlmBusyException("LLM 并发队列已满，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmBusyException("LLM 请求被中断");
        }
    }

    public void release() { permits.release(); }
}
