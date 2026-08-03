package com.tutor.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuthInterceptorTest {
    private final JwtService jwt = mock(JwtService.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(jwt, true);

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
    void productionCanDisableInternalEndpoints() throws Exception {
        AuthInterceptor productionInterceptor = new AuthInterceptor(jwt, false);
        HttpServletRequest request = request("/internal/push-run", null);
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(productionInterceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(404);
        verifyNoInteractions(jwt);
    }

    private static HttpServletRequest request(String path, String authorization) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getHeader("Authorization")).thenReturn(authorization);
        return request;
    }

    private static HttpServletResponse responseWithWriter() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return response;
    }
}
