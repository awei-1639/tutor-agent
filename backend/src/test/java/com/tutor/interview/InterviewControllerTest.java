package com.tutor.interview;

import com.tutor.auth.AuthContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InterviewControllerTest {
    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void opensInterviewForAuthenticatedUserInsteadOfDefaultAccount() throws Exception {
        InterviewSession service = mock(InterviewSession.class);
        when(service.open(42L, "后端开发", "负责高并发服务", "technical", "MID", 45, "trace-1"))
                .thenReturn(new InterviewSession.InterviewMessage("session-1", "IN_PROGRESS", "问题 1"));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(service)).build();

        mvc.perform(post("/interview/open").contentType(APPLICATION_JSON)
                        .header("X-Trace-Id", "trace-1")
                        .content("{\"targetRole\":\"后端开发\",\"jobDescription\":\"负责高并发服务\",\"interviewType\":\"technical\",\"difficulty\":\"MID\",\"durationMinutes\":45}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value("session-1"));

        verify(service).open(42L, "后端开发", "负责高并发服务", "technical", "MID", 45, "trace-1");
    }

    @Test
    void submitsAnswerWithClientRequestIdForSafeRetry() throws Exception {
        InterviewSession service = mock(InterviewSession.class);
        InterviewTurnService turns = mock(InterviewTurnService.class);
        when(turns.submit(42L, "session-1", "我的回答", "request-1", "interview"))
                .thenReturn(new InterviewTurnService.TurnJob("turn-1", "session-1", "request-1",
                        "PENDING", 1, null, null, null, java.time.Instant.now(), null));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(service, turns, new InterviewRateLimiter(5, 30))).build();

        mvc.perform(post("/interview/session-1/answer").contentType(APPLICATION_JSON)
                        .content("{\"answer\":\"我的回答\",\"requestId\":\"request-1\"}"))
                .andExpect(status().isAccepted());

        verify(turns).submit(42L, "session-1", "我的回答", "request-1", "interview");
    }

    @Test
    void explicitlyRetriesOnlyTheAuthenticatedUsersFailedTurn() throws Exception {
        InterviewSession service = mock(InterviewSession.class);
        InterviewTurnService turns = mock(InterviewTurnService.class);
        InterviewTurnService.TurnJob retry = new InterviewTurnService.TurnJob("turn-1", "session-1", "request-1",
                "PENDING", 0, null, null, null, java.time.Instant.now(), null);
        when(turns.retry(42L, "session-1", "turn-1")).thenReturn(retry);
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(service, turns, new InterviewRateLimiter(5, 30))).build();

        mvc.perform(post("/interview/session-1/turns/turn-1/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(turns).retry(42L, "session-1", "turn-1");
    }

    @Test
    void listsOnlyAuthenticatedUsersInterviewHistory() throws Exception {
        InterviewSession service = mock(InterviewSession.class);
        when(service.history(42L, 5)).thenReturn(java.util.List.of());
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(service)).build();

        mvc.perform(get("/interview/history?limit=5"))
                .andExpect(status().isOk());

        verify(service).history(42L, 5);
    }

    @Test
    void createsRetestForAuthenticatedUserOnly() throws Exception {
        InterviewSession service = mock(InterviewSession.class);
        when(service.retest(42L, "completed-1", "interview"))
                .thenReturn(new InterviewSession.InterviewMessage("retest-1", "IN_PROGRESS", "问题 1"));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(service)).build();

        mvc.perform(post("/interview/completed-1/retest"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value("retest-1"));

        verify(service).retest(42L, "completed-1", "interview");
    }

    @Test
    void returnsRetestComparisonWithReport() throws Exception {
        InterviewSession service = mock(InterviewSession.class);
        InterviewSession.RetestComparison comparison = new InterviewSession.RetestComparison(
                "source-1", 5.5, 1.2, java.util.List.of("补充缓存一致性边界"));
        when(service.report(42L, "retest-1")).thenReturn(new InterviewSession.Report(
                5, 6.7, 0.82, java.util.List.of("表达清晰"), java.util.List.of("补充指标"),
                java.util.List.of("skill:Redis"), comparison));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(service)).build();

        mvc.perform(get("/interview/retest-1/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retestComparison.baselineAvgScore").value(5.5))
                .andExpect(jsonPath("$.retestComparison.scoreDelta").value(1.2));

        verify(service).report(42L, "retest-1");
    }

    @Test
    void cancelsInterviewForAuthenticatedUser() throws Exception {
        InterviewSession service = mock(InterviewSession.class);
        when(service.cancel(42L, "session-1"))
                .thenReturn(new InterviewSession.InterviewMessage("session-1", "CANCELLED", "已结束"));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(service)).build();

        mvc.perform(post("/interview/session-1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(service).cancel(42L, "session-1");
    }

    @Test
    void storesScoringCalibrationFeedbackForAuthenticatedUser() throws Exception {
        InterviewSession service = mock(InterviewSession.class);
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(service)).build();

        mvc.perform(post("/interview/session-1/feedback").contentType(APPLICATION_JSON)
                        .content("{\"rating\":\"inaccurate\",\"reason\":\"遗漏了项目取舍说明\"}"))
                .andExpect(status().isNoContent());

        verify(service).feedback(42L, "session-1", "inaccurate", "遗漏了项目取舍说明");
    }

    @Test
    void throttlesInterviewOpenRequests() throws Exception {
        InterviewSession service = mock(InterviewSession.class);
        when(service.open(42L, "后端开发", null, "technical", "MID", 45, "interview"))
                .thenReturn(new InterviewSession.InterviewMessage("session-1", "IN_PROGRESS", "问题 1"));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                controller(service, mock(InterviewTurnService.class), new InterviewRateLimiter(1, 1))).build();
        String body = "{\"targetRole\":\"后端开发\",\"interviewType\":\"technical\",\"difficulty\":\"MID\",\"durationMinutes\":45}";

        mvc.perform(post("/interview/open").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/interview/open").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());

        verify(service).open(42L, "后端开发", null, "technical", "MID", 45, "interview");
    }

    private InterviewController controller(InterviewSession sessions) {
        return controller(sessions, mock(InterviewTurnService.class), new InterviewRateLimiter(5, 30));
    }

    private InterviewController controller(InterviewSession sessions, InterviewTurnService turns,
                                            InterviewRateLimiter rateLimiter) {
        return new InterviewController(sessions, rateLimiter, new InterviewMetrics(new SimpleMeterRegistry()), turns);
    }
}
