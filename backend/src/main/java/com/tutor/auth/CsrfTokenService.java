package com.tutor.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/** 双提交 Cookie CSRF 校验：token 本身不包含身份信息，也不进入日志。 */
@Component
public class CsrfTokenService {
    public static final String COOKIE = "tutor_csrf";
    public static final String HEADER = "X-CSRF-Token";
    private final SecureRandom random = new SecureRandom();

    public String issue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public boolean matches(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) return false;
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName())) return header.equals(cookie.getValue());
        }
        return false;
    }
}
