package com.tutor.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 拦截器 (Phase 4 V4 4.x): 解析 Authorization Bearer → 注入 userId。
 * 未带 token → 设为 DEV_USER_ID=1 (向后兼容 dev)。
 * 内部 /internal/* 与 /auth/* 端点跳过 (评估/登录接口免认证)。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String USER_ID_ATTR = "tutor.userId";
    public static final long DEV_USER_ID = 1L;

    private final JwtService jwt;

    public AuthInterceptor(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String path = req.getRequestURI();
        if (path.startsWith("/internal") || path.startsWith("/auth")) {
            req.setAttribute(USER_ID_ATTR, DEV_USER_ID);
            AuthContext.set(DEV_USER_ID);
            return true;
        }
        String auth = req.getHeader("Authorization");
        long userId = DEV_USER_ID;
        if (auth != null && auth.startsWith("Bearer ")) {
            Long uid = jwt.parse(auth.substring(7));
            if (uid != null) userId = uid;
        }
        req.setAttribute(USER_ID_ATTR, userId);
        AuthContext.set(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        AuthContext.clear();
    }
}