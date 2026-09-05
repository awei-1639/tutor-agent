package com.tutor.plan;

import com.tutor.identity.auth.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlanControllerTest {
    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void loadsTasksForAuthenticatedUserInsteadOfDefaultAccount() throws Exception {
        PlanService service = mock(PlanService.class);
        when(service.todayTasks(42L)).thenReturn(List.of(new PlanModels.PlanTask(
                9L, 3L, LocalDate.now(), "完成一个练习", "practice", 30, "提交练习答案")));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlanController(service)).build();

        mvc.perform(get("/plans/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(9))
                .andExpect(jsonPath("$[0].planId").value(3))
                .andExpect(jsonPath("$[0].content").value("完成一个练习"))
                .andExpect(jsonPath("$[0].kind").value("practice"))
                .andExpect(jsonPath("$[0].minutes").value(30))
                .andExpect(jsonPath("$[0].evidenceHint").value("提交练习答案"));

        verify(service).todayTasks(42L);
    }

    @Test
    void enqueuesPlanGenerationWithoutWaitingForLlm() throws Exception {
        PlanService service = mock(PlanService.class);
        when(service.enqueueWeeklyPlan(42L, "转 NLP 岗", "Java", "", "trace-1"))
                .thenReturn(new PlanModels.PlanGenerationJob(11L, "queued", null, null,
                        Instant.parse("2026-09-02T00:00:00Z"), null));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlanController(service)).build();

        mvc.perform(post("/plans").contentType(APPLICATION_JSON)
                        .header("X-Trace-Id", "trace-1")
                        .content("{\"goal\":\"转 NLP 岗\",\"currentSkills\":\"Java\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.planId").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(service).enqueueWeeklyPlan(42L, "转 NLP 岗", "Java", "", "trace-1");
    }
}
