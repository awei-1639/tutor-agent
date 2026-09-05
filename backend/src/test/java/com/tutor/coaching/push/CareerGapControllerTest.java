package com.tutor.coaching.push;

import com.tutor.identity.auth.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CareerGapControllerTest {
    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void returnsOnlyAuthenticatedUsersGapCards() throws Exception {
        CareerGapService service = mock(CareerGapService.class);
        when(service.topGaps(42L)).thenReturn(List.of(new CareerGapService.GapCard(
                203L, "NLP算法工程师", "示例公司", "杭州", .5,
                List.of("skill:python"), List.of("skill:rag"), List.of("skill:transformers"))));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CareerGapController(service)).build();

        mvc.perform(get("/career/gaps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value(203))
                .andExpect(jsonPath("$[0].coverage").value(.5))
                .andExpect(jsonPath("$[0].missing[0]").value("skill:transformers"));

        verify(service).topGaps(42L);
    }

    @Test
    void addsOnlySelectedGapSkillsToAuthenticatedUsersPlan() throws Exception {
        CareerGapService service = mock(CareerGapService.class);
        when(service.addGapTasks(eq(42L), eq(203L), eq(List.of("skill:transformers"))))
                .thenReturn(List.of(new com.tutor.coaching.plan.PlanModels.PlanTask(
                        9L, 3L, LocalDate.now(), "完成练习", "practice", 45, "提交练习答案")));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CareerGapController(service)).build();

        mvc.perform(post("/career/gaps/tasks").contentType("application/json")
                        .content("{\"jobId\":203,\"skillIds\":[\"skill:transformers\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].evidenceHint").value("提交练习答案"));

        verify(service).addGapTasks(42L, 203L, List.of("skill:transformers"));
    }
}
