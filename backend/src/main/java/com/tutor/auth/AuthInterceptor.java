package com.tutor.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 拦截器 (Phase 4 V4 4.x): 解析 Authorization Bearer 或 HttpOnly cookie → 注入 userId。
 * 未带 token → 拒绝请求；内部评估端点才使用 DEV_USER_ID=1。
 * /auth/* 端点跳过；/internal/* 仅允许本地评估环境启用。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String USER_ID_ATTR = "tutor.userId";
    public static final String ACCESS_COOKIE = "tutor_access";
    public static final long DEV_USER_ID = 1L;

    private final JwtService jwt;
    private final boolean internalEndpointsEnabled;
    private final boolean internalEndpointsLoopbackOnly;

    public AuthInterceptor(JwtService jwt,
                           @Value("${tutor.internal.enabled:false}") boolean internalEndpointsEnabled,
                           @Value("${tutor.internal.loopback-only:true}") boolean internalEndpointsLoopbackOnly) {
        this.jwt = jwt;
        this.internalEndpointsEnabled = internalEndpointsEnabled;
        this.internalEndpointsLoopbackOnly = internalEndpointsLoopbackOnly;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        addSecurityHeaders(res);
        String path = req.getRequestURI();
        if (path.startsWith("/internal")) {
            if (!internalEndpointsEnabled || (internalEndpointsLoopbackOnly && !isLoopback(req.getRemoteAddr()))) {
                res.setStatus(404);
                return false;
            }
            req.setAttribute(USER_ID_ATTR, DEV_USER_ID);
            AuthContext.set(DEV_USER_ID);
            return true;
        }
        if (path.startsWith("/auth") || path.equals("/healthz") || path.equals("/readyz")
                || path.equals("/actuator/health")) {
            req.setAttribute(USER_ID_ATTR, DEV_USER_ID);
            AuthContext.set(DEV_USER_ID);
            return true;
        }
        String auth = req.getHeader("Authorization");
        String token = null;
        if (auth != null && auth.startsWith("Bearer ")) {
            token = auth.substring(7);
        } else if (auth == null) {
            token = cookieValue(req, ACCESS_COOKIE);
        }
        // 业务端点必须带 token, 没带 / 格式错 / 解析失败都返 401
        // 不能再 fallback 到 DEV_USER_ID=1 (会泄露其他用户数据)
        if (token == null || token.isBlank()) {
            sendUnauthorized(res, "missing or invalid authentication");
            return false;
        }
        Long uid = jwt.parse(token);
        if (uid == null) {
            sendUnauthorized(res, "invalid or expired token");
            return false;
        }
        req.setAttribute(USER_ID_ATTR, uid);
        AuthContext.set(uid, jwt.parseTenantId(token));
        return true;
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private static boolean isLoopback(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) return false;
        return "127.0.0.1".equals(remoteAddress)
                || "::1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress);
    }

    private void sendUnauthorized(HttpServletResponse res, String msg) throws java.io.IOException {
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + msg + "\"}");
    }

    private static void addSecurityHeaders(HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        AuthContext.clear();
    }
}
