package com.tutor.profile;

import com.tutor.auth.AuthContext;
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
        when(service.recentEvents(42L, 12)).thenReturn(List.of(new ProfileService.ProfileEvent(
                7L, List.of("技能新增: Java(explicit)"), "conversation",
                Instant.parse("2026-08-03T07:00:00Z"), "trace-7")));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProfileController(service)).build();

        mvc.perform(get("/profile/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].changes[0]").value("技能新增: Java(explicit)"))
                .andExpect(jsonPath("$[0].trigger").value("conversation"));

        verify(service).recentEvents(42L, 12);
    }
}
