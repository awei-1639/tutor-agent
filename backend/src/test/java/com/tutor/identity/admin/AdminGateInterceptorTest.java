package com.tutor.identity.admin;

import com.tutor.identity.auth.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.*;

class AdminGateInterceptorTest {
    private final AdminStore store = mock(AdminStore.class);
    private final AdminGateInterceptor interceptor = new AdminGateInterceptor(store);

    @RequireAdmin
    static class GuardedController {
        @SuppressWarnings("unused")
        public void entry() {
        }
    }

    static class OpenController {
        @SuppressWarnings("unused")
        public void entry() {
        }
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void nonAdminHandlerPassesThroughWithoutAnyCheck() throws Exception {
        HandlerMethod handler = new HandlerMethod(new OpenController(), OpenController.class.getMethod("entry"));
        assertThat(interceptor.preHandle(request(), response(), handler)).isTrue();
        verifyNoInteractions(store);
    }

    @Test
    void guardedHandlerRejectsAnonymousRequest() throws Exception {
        HandlerMethod handler = new HandlerMethod(new GuardedController(), GuardedController.class.getMethod("entry"));

        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> interceptor.preHandle(request(), response(), handler))
                .withMessageContaining("未登录")
                .satisfies(e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(store, never()).isAdmin(anyLong());
    }

    @Test
    void guardedHandlerRejectsNonAdminUser() throws Exception {
        HandlerMethod handler = new HandlerMethod(new GuardedController(), GuardedController.class.getMethod("entry"));
        AuthContext.set(7L);
        when(store.isAdmin(7L)).thenReturn(false);

        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> interceptor.preHandle(request(), response(), handler))
                .withMessageContaining("需要管理员权限")
                .satisfies(e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void guardedHandlerAcceptsAdminUser() throws Exception {
        HandlerMethod handler = new HandlerMethod(new GuardedController(), GuardedController.class.getMethod("entry"));
        AuthContext.set(1L);
        when(store.isAdmin(1L)).thenReturn(true);

        assertThat(interceptor.preHandle(request(), response(), handler)).isTrue();
    }

    @Test
    void nonHandlerObjectPassesThrough() throws Exception {
        assertThat(interceptor.preHandle(request(), response(), new Object())).isTrue();
        verifyNoInteractions(store);
    }

    private static HttpServletRequest request() {
        return mock(HttpServletRequest.class);
    }

    private static HttpServletResponse response() {
        return mock(HttpServletResponse.class);
    }
}
