package com.tutor.auth;

import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
    /** Business code must fail closed: silently using a development account risks cross-user data access. */
    public static long requireUserId() {
        Long userId = CURRENT.get();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        return userId;
    }
    public static void clear() { CURRENT.remove(); }
}
