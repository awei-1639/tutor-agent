package com.tutor.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * JWT 拦截器 (Phase 4 V4 4.x): 解析 Authorization Bearer 或 HttpOnly cookie → 注入 userId。
 * 未带 token → 拒绝请求；内部评估端点才使用 DEV_USER_ID=1。
 * /auth/* 端点跳过；/internal/* 仅允许本地评估环境启用。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String USER_ID_ATTR = "tutor.userId";
    public static final String ACCESS_COOKIE = "tutor_access";
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    public static final long DEV_USER_ID = 1L;

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    private final JwtService jwt;
    private final boolean internalEndpointsEnabled;
    private final boolean internalEndpointsLoopbackOnly;
    private final String metricsScrapeToken;
    private final String internalToken;

    public AuthInterceptor(JwtService jwt,
                           @Value("${tutor.internal.enabled:false}") boolean internalEndpointsEnabled,
                           @Value("${tutor.internal.loopback-only:true}") boolean internalEndpointsLoopbackOnly) {
        this(jwt, internalEndpointsEnabled, internalEndpointsLoopbackOnly, "", "");
    }

    public AuthInterceptor(JwtService jwt,
                           @Value("${tutor.internal.enabled:false}") boolean internalEndpointsEnabled,
                           @Value("${tutor.internal.loopback-only:true}") boolean internalEndpointsLoopbackOnly,
                           @Value("${tutor.metrics.scrape-token:}") String metricsScrapeToken) {
        this(jwt, internalEndpointsEnabled, internalEndpointsLoopbackOnly, metricsScrapeToken, "");
    }

    // 必须显式指定：多于一个构造器且都无 @Autowired 时，Spring 会退化去找无参构造器并直接失败。
    @Autowired
    public AuthInterceptor(JwtService jwt,
                           @Value("${tutor.internal.enabled:false}") boolean internalEndpointsEnabled,
                           @Value("${tutor.internal.loopback-only:true}") boolean internalEndpointsLoopbackOnly,
                           @Value("${tutor.metrics.scrape-token:}") String metricsScrapeToken,
                           @Value("${tutor.internal.token:}") String internalToken) {
        this.jwt = jwt;
        this.internalEndpointsEnabled = internalEndpointsEnabled;
        this.internalEndpointsLoopbackOnly = internalEndpointsLoopbackOnly;
        this.metricsScrapeToken = metricsScrapeToken == null ? "" : metricsScrapeToken.trim();
        this.internalToken = internalToken == null ? "" : internalToken.trim();
        // /internal 的 loopback 判定只看 remoteAddr, 任何同机反向代理(含内网穿透)都会让
        // 公网请求看起来来自 127.0.0.1。没有令牌时这条防线只剩"代理不转发 /internal"这一条隐含假设,
        // 因此必须在启动日志里显式可见。
        if (internalEndpointsEnabled && this.internalToken.isBlank()) {
            log.warn("/internal 端点已启用但未配置 INTERNAL_TOKEN: 仅靠 remoteAddr 判定本机, "
                    + "同机反向代理/内网穿透会绕过该判定");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        addSecurityHeaders(res);
        String path = req.getRequestURI();
        if (path.equals("/actuator/prometheus") && matchesScrapeToken(req.getHeader("Authorization"))) {
            // Prometheus 抓取令牌认证; 指标端点不建立用户上下文, 令牌未配置时维持 401 行为
            return true;
        }
        if (path.startsWith("/internal")) {
            if (!internalEndpointsEnabled || !internalAccessAllowed(req)) {
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
        if (path.equals("/error")) {
            // Spring 的 ERROR dispatch 会以原始 URI=/error 重新过一遍拦截器; 若这里要求 token,
            // 任何业务端点的 500 都会被改写成 401, 客户端再也看不到真实错误状态。
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

    /**
     * 恒定时间比较抓取令牌: String.equals 按字符提前返回, 响应耗时随匹配前缀长度变化,
     * 给持有网络邻近位置的攻击者逐字节猜令牌的机会 (诊断报告 2026-09-10 §4.2)。
     */
    /**
     * /internal 门禁。两条路互斥：
     * <ol>
     *   <li>配置了 {@code INTERNAL_TOKEN} → 令牌是主控制，必须恒定时间匹配。此时不再强制
     *       loopback：令牌本身就是凭证，本机与远端一视同仁（远端要比本机更严格才有意义）。</li>
     *   <li>未配置令牌 → 退回原先的 loopback 判定，并额外拒绝携带代理头的请求。
     *       同机反向代理会让 remoteAddr 恒为 127.0.0.1，代理头是唯一还能看出来的信号。</li>
     * </ol>
     */
    private boolean internalAccessAllowed(HttpServletRequest req) {
        if (!internalToken.isBlank()) {
            return matchesInternalToken(req.getHeader(INTERNAL_TOKEN_HEADER));
        }
        if (internalEndpointsLoopbackOnly && !isLoopback(req.getRemoteAddr())) return false;
        return !hasProxyMarker(req);
    }

    /** 出现任一代理头即说明真正客户端不在这条连接上，此时 loopback 判定不可信。 */
    private static boolean hasProxyMarker(HttpServletRequest req) {
        return hasText(req.getHeader("X-Forwarded-For"))
                || hasText(req.getHeader("X-Real-IP"))
                || hasText(req.getHeader("Forwarded"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean matchesInternalToken(String provided) {
        if (internalToken.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private boolean matchesScrapeToken(String authorizationHeader) {
        if (metricsScrapeToken.isBlank() || authorizationHeader == null) return false;
        return MessageDigest.isEqual(("Bearer " + metricsScrapeToken).getBytes(StandardCharsets.UTF_8),
                authorizationHeader.getBytes(StandardCharsets.UTF_8));
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
