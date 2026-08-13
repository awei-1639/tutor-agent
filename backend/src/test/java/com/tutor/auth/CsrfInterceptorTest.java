package com.tutor.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CsrfInterceptorTest {
    private final CsrfInterceptor interceptor = new CsrfInterceptor(new CsrfTokenService());

    @Test
    void rejectsCookieWriteWithoutMatchingToken() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = responseWithWriter();
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/profile/confirm");
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie(CsrfTokenService.COOKIE, "cookie")});
        when(request.getHeader(CsrfTokenService.HEADER)).thenReturn("wrong");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(403);
    }

    @Test
    void acceptsMatchingTokenAndLegacyBearerClient() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/profile/confirm");
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie(CsrfTokenService.COOKIE, "same")});
        when(request.getHeader(CsrfTokenService.HEADER)).thenReturn("same");
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();

        when(request.getHeader(CsrfTokenService.HEADER)).thenReturn(null);
        when(request.getHeader("Authorization")).thenReturn("Bearer legacy");
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    private static HttpServletResponse responseWithWriter() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return response;
    }
}
