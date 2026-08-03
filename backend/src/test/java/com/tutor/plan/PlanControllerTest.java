package com.tutor.plan;

import com.tutor.auth.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlanControllerTest {
    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void loadsTasksForAuthenticatedUserInsteadOfDefaultAccount() throws Exception {
        PlanService service = mock(PlanService.class);
        when(service.todayTasks(42L)).thenReturn(List.of(new PlanService.PlanTask(
                9L, 3L, LocalDate.now(), "完成一个练习", "practice", 30)));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlanController(service)).build();

        mvc.perform(get("/plans/today"))
                .andExpect(status().isOk());

        verify(service).todayTasks(42L);
    }
}
