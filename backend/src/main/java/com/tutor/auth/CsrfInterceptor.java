package com.tutor.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 只保护已认证的写请求；认证入口本身不依赖既有会话 Cookie。 */
@Component
public class CsrfInterceptor implements HandlerInterceptor {
    private final CsrfTokenService csrf;

    public CsrfInterceptor(CsrfTokenService csrf) {
        this.csrf = csrf;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)
                || request.getRequestURI().startsWith("/auth")
                || request.getRequestURI().startsWith("/internal")
                || (request.getHeader("Authorization") != null
                    && request.getHeader("Authorization").startsWith("Bearer "))) {
            return true;
        }
        if (csrf.matches(request)) return true;
        response.setStatus(403);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"missing or invalid CSRF token\"}");
        return false;
    }
}
