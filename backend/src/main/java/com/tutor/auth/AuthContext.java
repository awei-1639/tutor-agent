package com.tutor.auth;

import org.springframework.stereotype.Component;

/**
 * 全局 userId 注入: ChatService/PlanService 等从 AuthContext.currentUserId() 取
 * 当前请求的用户 ID (由 AuthInterceptor 写入 request attribute)。
 * 设计选择: 显式调用而非 ThreadLocal, 便于测试 + 单例 bean。
 */
@Component
public class AuthContext {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    public static void set(Long userId) { CURRENT.set(userId); }
    public static Long currentUserId() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}