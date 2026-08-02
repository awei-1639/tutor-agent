package com.tutor.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 拦截器 (Phase 4 V4 4.x): 解析 Authorization Bearer → 注入 userId。
 * 未带 token → 设为 DEV_USER_ID=1 (向后兼容 dev)。
 * /auth/* 端点跳过；/internal/* 仅允许本地评估环境启用。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String USER_ID_ATTR = "tutor.userId";
    public static final long DEV_USER_ID = 1L;

    private final JwtService jwt;
    private final boolean internalEndpointsEnabled;

    public AuthInterceptor(JwtService jwt,
                           @Value("${tutor.internal.enabled:true}") boolean internalEndpointsEnabled) {
        this.jwt = jwt;
        this.internalEndpointsEnabled = internalEndpointsEnabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String path = req.getRequestURI();
        if (path.startsWith("/internal")) {
            if (!internalEndpointsEnabled) {
                res.setStatus(404);
                return false;
            }
            req.setAttribute(USER_ID_ATTR, DEV_USER_ID);
            AuthContext.set(DEV_USER_ID);
            return true;
        }
        if (path.startsWith("/auth")) {
            req.setAttribute(USER_ID_ATTR, DEV_USER_ID);
            AuthContext.set(DEV_USER_ID);
            return true;
        }
        String auth = req.getHeader("Authorization");
        // 业务端点必须带 token, 没带 / 格式错 / 解析失败都返 401
        // 不能再 fallback 到 DEV_USER_ID=1 (会泄露其他用户数据)
        if (auth == null || !auth.startsWith("Bearer ")) {
            sendUnauthorized(res, "missing or invalid Authorization header");
            return false;
        }
        Long uid = jwt.parse(auth.substring(7));
        if (uid == null) {
            sendUnauthorized(res, "invalid or expired token");
            return false;
        }
        req.setAttribute(USER_ID_ATTR, uid);
        AuthContext.set(uid);
        return true;
    }

    private void sendUnauthorized(HttpServletResponse res, String msg) throws java.io.IOException {
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + msg + "\"}");
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        AuthContext.clear();
    }
}
