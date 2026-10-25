package com.tutor.identity.admin;

import com.tutor.identity.auth.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@link RequireAdmin} 注解的执行侧: 在进入 Controller 前统一完成管理员鉴权。
 * 必须注册在 {@code AuthInterceptor} 之后 (依赖其写入的 {@link AuthContext})。
 * 错误语义与原 Service 层 {@code requireAdmin()} 一致: 未登录 401「未登录」、非管理员 403「需要管理员权限」。
 */
@Component
public class AdminGateInterceptor implements HandlerInterceptor {
    private final AdminStore store;

    public AdminGateInterceptor(AdminStore store) {
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod method)) return true;
        if (!method.hasMethodAnnotation(RequireAdmin.class) && !method.getBeanType().isAnnotationPresent(RequireAdmin.class)) {
            return true;
        }
        Long id = AuthContext.currentUserId();
        if (id == null) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"未登录\"}");
            return false;
        }
        if (!store.isAdmin(id)) {
            res.setStatus(403);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"需要管理员权限\"}");
            return false;
        }
        return true;
    }
}
