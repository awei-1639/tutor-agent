package com.tutor.memory.policy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** External-memory consent use cases; SQL and generation fencing live in MemoryConsentStore. */
@Service
public class MemoryConsentService {
    private final MemoryConsentStore store;

    @Autowired
    public MemoryConsentService(MemoryConsentStore store) {
        this.store = store;
    }

    public boolean enabledFor(long userId) {
        return store.enabledFor(userId);
    }

    public long currentGeneration(long userId) {
        return store.currentGeneration(userId);
    }

    public void setEnabled(long userId, boolean enabled) {
        // 重新启用会开启新的代际，避免旧的延迟远端删除任务清除用户重新授权后创建的记忆。
        store.setEnabled(userId, enabled);
    }

    /** 在本地删除前使所有已入队的记忆任务失效。 */
    public void invalidateMemoryGeneration(long userId) {
        store.incrementGeneration(userId);
    }
}