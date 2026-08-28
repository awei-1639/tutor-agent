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
    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();

    public static void set(Long userId) { set(userId, null); }
    public static void set(Long userId, String tenantId) {
        if (userId == null) {
            CURRENT.remove();
            TENANT.remove();
            return;
        }
        CURRENT.set(userId);
        if (tenantId == null || tenantId.isBlank()) TENANT.remove();
        else TENANT.set(tenantId.trim());
    }
    public static Long currentUserId() { return CURRENT.get(); }
    public static String currentTenantId() { return TENANT.get(); }
    /** 业务代码必须安全失败：静默使用开发账号会造成跨用户数据访问风险。 */
    public static long requireUserId() {
        Long userId = CURRENT.get();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        return userId;
    }
    public static void clear() { CURRENT.remove(); TENANT.remove(); }
}
