package com.tutor.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuthInterceptorTest {
    private final JwtService jwt = mock(JwtService.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(jwt, true, true, "");

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void publicAuthEndpointDoesNotRequireToken() throws Exception {
        HttpServletRequest request = request("/auth/login", null);
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(AuthContext.currentUserId()).isEqualTo(AuthInterceptor.DEV_USER_ID);
        verifyNoInteractions(jwt);
    }

    @Test
    void publicHealthEndpointDoesNotRequireToken() throws Exception {
        HttpServletRequest request = request("/readyz", null);
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verifyNoInteractions(jwt);
    }

    @Test
    void errorDispatchPassesThroughWithoutAuthenticating() throws Exception {
        // Spring 把业务端点异常 ERROR dispatch 到 /error; 若这里拦下要求 token,
        // 所有 5xx 都会被改写成 401, 客户端永远看不到真实状态码。
        HttpServletRequest request = request("/error", null);
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verifyNoInteractions(jwt);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void prometheusEndpointRequiresAuthentication() throws Exception {
        HttpServletRequest request = request("/actuator/prometheus", null);
        HttpServletResponse response = responseWithWriter();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(401);
        verifyNoInteractions(jwt);
    }

    @Test
    void protectedEndpointRejectsMissingToken() throws Exception {
        HttpServletRequest request = request("/profile", null);
        HttpServletResponse response = responseWithWriter();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(401);
    }

    @Test
    void protectedEndpointRejectsInvalidToken() throws Exception {
        when(jwt.parse("bad-token")).thenReturn(null);
        HttpServletRequest request = request("/profile", "Bearer bad-token");
        HttpServletResponse response = responseWithWriter();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(401);
    }

    @Test
    void protectedEndpointInjectsAuthenticatedUser() throws Exception {
        when(jwt.parse("good-token")).thenReturn(42L);
        HttpServletRequest request = request("/profile", "Bearer good-token");
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(AuthContext.currentUserId()).isEqualTo(42L);
        verify(request).setAttribute(AuthInterceptor.USER_ID_ATTR, 42L);
    }

    @Test
    void protectedEndpointAcceptsHttpOnlyAccessCookie() throws Exception {
        HttpServletRequest request = request("/profile", null);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie(AuthInterceptor.ACCESS_COOKIE, "cookie-token")});
        when(jwt.parse("cookie-token")).thenReturn(42L);
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(AuthContext.currentUserId()).isEqualTo(42L);
    }

    @Test
    void productionCanDisableInternalEndpoints() throws Exception {
        AuthInterceptor productionInterceptor = new AuthInterceptor(jwt, false, true, "");
        HttpServletRequest request = request("/internal/push-run", null);
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(productionInterceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(404);
        verifyNoInteractions(jwt);
    }

    @Test
    void enabledInternalEndpointsRejectNonLoopbackRequests() throws Exception {
        HttpServletRequest request = request("/internal/push-run", null);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(404);
        verifyNoInteractions(jwt);
    }

    @Test
    void prometheusScrapeWithConfiguredTokenIsAccepted() throws Exception {
        AuthInterceptor tokenInterceptor = new AuthInterceptor(jwt, true, true, "scrape-secret");
        HttpServletRequest request = request("/actuator/prometheus", "Bearer scrape-secret");
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(tokenInterceptor.preHandle(request, response, new Object())).isTrue();
        verifyNoInteractions(jwt);
        assertThat(AuthContext.currentUserId()).isNull();
    }

    @Test
    void prometheusScrapeWithWrongOrMissingTokenIsRejected() throws Exception {
        AuthInterceptor tokenInterceptor = new AuthInterceptor(jwt, true, true, "scrape-secret");
        HttpServletResponse response = responseWithWriter();
        // 显式打桩: 本机 Mockito 环境下未打桩的 Long 返回 0 而非 null, 会把非法 token 误判为合法
        when(jwt.parse(anyString())).thenReturn(null);

        assertThat(tokenInterceptor.preHandle(request("/actuator/prometheus", "Bearer wrong"), response, new Object())).isFalse();
        assertThat(tokenInterceptor.preHandle(request("/actuator/prometheus", null), responseWithWriter(), new Object())).isFalse();
        assertThat(interceptor.preHandle(request("/actuator/prometheus", "Bearer scrape-secret"), responseWithWriter(), new Object())).isFalse();
    }

    @Test
    void internalEndpointRejectsMissingOrWrongToken() throws Exception {
        AuthInterceptor tokenInterceptor = new AuthInterceptor(jwt, true, true, "", "internal-secret");

        assertThat(tokenInterceptor.preHandle(request("/internal/retrieve", null), mock(HttpServletResponse.class), new Object()))
                .isFalse();
        assertThat(tokenInterceptor.preHandle(requestWithInternalToken("/internal/retrieve", "wrong"),
                mock(HttpServletResponse.class), new Object())).isFalse();
    }

    @Test
    void internalEndpointAcceptsMatchingTokenEvenOffLoopback() throws Exception {
        // 令牌一旦配置就是主控制: 同机反向代理会让 remoteAddr 恒为 127.0.0.1,
        // 继续要求 loopback 对隧道场景毫无约束力, 只会制造"已防护"的错觉。
        AuthInterceptor tokenInterceptor = new AuthInterceptor(jwt, true, true, "", "internal-secret");
        HttpServletRequest request = requestWithInternalToken("/internal/retrieve", "internal-secret");
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");

        assertThat(tokenInterceptor.preHandle(request, mock(HttpServletResponse.class), new Object())).isTrue();
        assertThat(AuthContext.currentUserId()).isEqualTo(AuthInterceptor.DEV_USER_ID);
    }

    @Test
    void internalEndpointWithoutTokenRejectsProxiedRequests() throws Exception {
        // 未配置令牌时只能靠 loopback 判定, 而代理头是"真正客户端在别处"的唯一信号
        HttpServletRequest request = request("/internal/retrieve", null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.10");
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(404);
    }

    @Test
    void internalEndpointWithoutTokenStillAllowsPlainLoopback() throws Exception {
        HttpServletRequest request = request("/internal/retrieve", null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertThat(interceptor.preHandle(request, mock(HttpServletResponse.class), new Object())).isTrue();
    }

    private static HttpServletRequest request(String path, String authorization) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getHeader("Authorization")).thenReturn(authorization);
        return request;
    }

    private static HttpServletRequest requestWithInternalToken(String path, String token) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getHeader(AuthInterceptor.INTERNAL_TOKEN_HEADER)).thenReturn(token);
        return request;
    }

    private static HttpServletResponse responseWithWriter() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return response;
    }
}
