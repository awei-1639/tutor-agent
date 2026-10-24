package com.tutor.identity.profile;

import com.tutor.identity.auth.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProfileControllerTest {
    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void exposesOnlyCurrentUsersProfileEventLedger() throws Exception {
        ProfileService service = mock(ProfileService.class);
        when(service.recentEvents(42L, 12, null)).thenReturn(List.of(new ProfileService.ProfileEvent(
                7L, List.of("技能新增: Java(explicit)"), "conversation",
                Instant.parse("2026-08-03T07:00:00Z"), "trace-7")));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProfileController(service)).build();

        mvc.perform(get("/profile/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].changes[0]").value("技能新增: Java(explicit)"))
                .andExpect(jsonPath("$[0].trigger").value("conversation"));

        verify(service).recentEvents(42L, 12, null);
    }

    /** 复盘页按场次回溯画像变更：traceId 必须原样透传，且 userId 仍取自会话而非入参。 */
    @Test
    void passesTraceIdThroughForSingleSourceLookup() throws Exception {
        ProfileService service = mock(ProfileService.class);
        when(service.recentEvents(42L, 20, "session-abc")).thenReturn(List.of(new ProfileService.ProfileEvent(
                9L, List.of("技能新增(面试): Redis"), "interview",
                Instant.parse("2026-08-03T07:00:00Z"), "session-abc")));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProfileController(service)).build();

        mvc.perform(get("/profile/events").param("limit", "20").param("traceId", "session-abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].changes[0]").value("技能新增(面试): Redis"))
                .andExpect(jsonPath("$[0].traceId").value("session-abc"));

        verify(service).recentEvents(42L, 20, "session-abc");
    }
}
