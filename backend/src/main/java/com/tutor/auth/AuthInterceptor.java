package com.tutor.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 拦截器 (Phase 4 V4 4.x): 解析 Authorization Bearer → 注入 userId。
 * 未带 token → 设为 DEV_USER_ID=1 (向后兼容 Phase 1-3 单用户模式)。
 * 内部 /internal/* 与 /auth/* 端点跳过。
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
        // 跳过内部评估端点 + auth 自身 (避免循环)
        if (path.startsWith("/internal") || path.startsWith("/auth")) {
            req.setAttribute(USER_ID_ATTR, DEV_USER_ID);
            return true;
        }
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            Long uid = jwt.parse(auth.substring(7));
            req.setAttribute(USER_ID_ATTR, uid == null ? DEV_USER_ID : uid);
        } else {
            req.setAttribute(USER_ID_ATTR, DEV_USER_ID);
        }
        return true;
    }
}