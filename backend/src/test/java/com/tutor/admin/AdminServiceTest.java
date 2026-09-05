package com.tutor.admin;

import com.tutor.auth.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminServiceTest {
    private final AdminStore store = mock(AdminStore.class);
    private final AdminService service = new AdminService(store);

    @AfterEach
    void clearAuth() { AuthContext.clear(); }

    @Test
    void rejectsAnonymousAndNonAdminUsersBeforeStoreOperations() {
        assertThatThrownBy(service::requireAdmin).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("未登录");
        AuthContext.set(7L);
        when(store.isAdmin(7L)).thenReturn(false);
        assertThatThrownBy(service::requireAdmin).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("需要管理员权限");
    }

    @Test
    void preventsAdminFromMutatingOwnAccount() {
        AuthContext.set(7L);
        when(store.isAdmin(7L)).thenReturn(true);
        assertThatThrownBy(() -> service.disable(7L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("不能修改当前管理员账号");
        verify(store).isAdmin(7L);
    }

    @Test
    void clampsUserListParametersAndDelegatesFiltering() {
        AuthContext.set(7L);
        when(store.isAdmin(7L)).thenReturn(true);
        when(store.users("ada", "active", 0, 100)).thenReturn(List.of(Map.of("id", 1L)));
        when(store.userCount("ada", "active")).thenReturn(1L);

        Map<String, Object> result = service.listUsers("ada", "active", -2, 1000);

        assertThat(result).containsEntry("page", 0).containsEntry("size", 100).containsEntry("total", 1L);
        verify(store).users("ada", "active", 0, 100);
    }
}
