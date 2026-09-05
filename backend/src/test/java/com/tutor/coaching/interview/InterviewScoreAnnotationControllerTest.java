package com.tutor.coaching.interview;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InterviewScoreAnnotationControllerTest {
    private final InterviewScoreAnnotationService service = mock(InterviewScoreAnnotationService.class);
    private final InterviewScoreEvalService evals = mock(InterviewScoreEvalService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new InterviewScoreAnnotationController(service, evals)).build();

    @Test
    void submitsAnAdminCalibrationLabel() throws Exception {
        when(service.upsert(42L, 8, "覆盖了权限和超时")).thenReturn(Map.of("questionId", 42L, "humanScore", 8));

        mvc.perform(post("/admin/interview-evals/annotations/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"humanScore\":8,\"rationale\":\"覆盖了权限和超时\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionId").value(42))
                .andExpect(jsonPath("$.humanScore").value(8));
        verify(service).upsert(eq(42L), eq(8), eq("覆盖了权限和超时"));
    }

    @Test
    void exposesOnlyTheDeidentifiedReviewQueue() throws Exception {
        when(service.queue(20, 2, true, 1)).thenReturn(java.util.List.of(
                Map.of("questionId", 42L, "prompt", "说明缓存击穿", "answer", "使用互斥锁",
                        "modelScore", 7, "modelConfidence", 0.8, "reviewerCount", 1)));

        mvc.perform(get("/admin/interview-evals/annotations/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].questionId").value(42))
                .andExpect(jsonPath("$[0].answer").value("使用互斥锁"));
        verify(service).queue(eq(20), eq(2), eq(true), eq(1));
    }

    @Test
    void runsReplayFromTheAdminAnnotationWorkflow() throws Exception {
        InterviewScoreEvalService.ReplayRequest input = new InterviewScoreEvalService.ReplayRequest("human-gold-v1", java.util.List.of(
                new InterviewScoreEvalService.ReplayCase("42", 8, 7, 0.8, 2, 1)));
        when(service.exportReplay("human-gold-v1", 2)).thenReturn(input);
        when(evals.replay(input)).thenReturn(Map.of("runId", 11L, "datasetVersion", "human-gold-v1"));

        mvc.perform(post("/admin/interview-evals/annotations/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetVersion\":\"human-gold-v1\",\"minReviewers\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(11));
        verify(service).exportReplay(eq("human-gold-v1"), eq(2));
        verify(evals).replay(input);
    }
}
