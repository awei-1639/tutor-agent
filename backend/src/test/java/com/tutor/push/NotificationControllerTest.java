package com.tutor.push;

import com.tutor.auth.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationControllerTest {
    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void listsNotificationsForTheAuthenticatedUser() throws Exception {
        NotificationStore store = mock(NotificationStore.class);
        when(store.list(42L, true)).thenReturn(List.of(Map.of("id", 1L, "type", "guide")));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new NotificationController(store)).build();

        mvc.perform(get("/notifications").param("unreadOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("guide"));

        verify(store).list(42L, true);
    }

    @Test
    void marksOnlyIdsForTheAuthenticatedUser() throws Exception {
        NotificationStore store = mock(NotificationStore.class);
        when(store.markRead(42L, List.of(3L, 5L))).thenReturn(2);
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new NotificationController(store)).build();

        mvc.perform(post("/notifications/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[3,5]}"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("2"));

        verify(store).markRead(eq(42L), eq(List.of(3L, 5L)));
    }
}
