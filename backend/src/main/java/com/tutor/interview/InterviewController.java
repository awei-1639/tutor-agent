package com.tutor.interview;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 模拟面试 HTTP API (Phase 3 V4 3.3)
 */
@RestController
@RequestMapping("/interview")
public class InterviewController {
    private final InterviewSession sessions;

    public InterviewController(InterviewSession sessions) {
        this.sessions = sessions;
    }

    public record OpenRequest(@NotBlank String sessionId, String targetRole) {}

    @PostMapping("/open")
    public Map<String, Object> open(@Valid @RequestBody OpenRequest req,
                                     @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String msg = sessions.open(req.sessionId(), 1L, req.targetRole(), traceId == null ? "user" : traceId);
        return Map.of("session_id", req.sessionId(), "message", msg);
    }

    public record AnswerRequest(@NotBlank String sessionId, @NotBlank String answer) {}

    @PostMapping("/answer")
    public Map<String, Object> answer(@Valid @RequestBody AnswerRequest req,
                                      @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String msg = sessions.answer(req.sessionId(), req.answer(), traceId == null ? "user" : traceId);
        return Map.of("session_id", req.sessionId(), "message", msg);
    }

    @GetMapping("/report/{sessionId}")
    public InterviewSession.Report report(@PathVariable String sessionId) {
        InterviewSession.Report r = sessions.report(sessionId);
        // 不存在 session 时返空 Report (而非 null → 前端 JSON 解析崩)
        return r == null ? new InterviewSession.Report(0, 0.0, List.of(), List.of(), List.of()) : r;
    }
}