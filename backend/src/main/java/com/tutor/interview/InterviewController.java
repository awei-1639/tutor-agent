package com.tutor.interview;

import com.tutor.identity.auth.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.Timer;

/** HTTP boundary for the durable, authenticated interview runtime. */
@RestController
@RequestMapping("/interview")
public class InterviewController {
    private final InterviewSession sessions;
    private final InterviewRateLimiter rateLimiter;
    private final InterviewMetrics metrics;
    private final InterviewTurnService turns;

    public InterviewController(InterviewSession sessions, InterviewRateLimiter rateLimiter, InterviewMetrics metrics, InterviewTurnService turns) {
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.turns = turns;
    }

    public record OpenRequest(@Size(max = 160) String targetRole,
                              @Size(max = 12000) String jobDescription,
                              @Pattern(regexp = "technical|project|behavioral|system_design") String interviewType,
                              @Pattern(regexp = "JUNIOR|MID|SENIOR") String difficulty,
                              @Min(15) @Max(120) Integer durationMinutes) {}
    public record AnswerRequest(@NotBlank @Size(max = 12000) String answer,
                                @NotBlank @Size(max = 80) String requestId) {}
    public record FeedbackRequest(@NotBlank @Pattern(regexp = "accurate|inaccurate") String rating,
                                  @Size(max = 1000) String reason) {}

    @PostMapping("/open")
    public ResponseEntity<InterviewSession.InterviewMessage> open(@Valid @RequestBody OpenRequest req,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        long userId = currentUserId();
        if (!rateLimiter.tryAcquireOpen(userId)) {
            metrics.request("open", "rate_limited");
            enforce(false, "创建面试请求过于频繁，请稍后再试");
        }
        Timer.Sample timer = metrics.startTimer();
        try {
            InterviewSession.InterviewMessage result = sessions.open(userId, req.targetRole(), req.jobDescription(), req.interviewType(),
                    req.difficulty(), req.durationMinutes(), traceIdOrDefault(traceId));
            metrics.request("open", "success");
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (RuntimeException ex) {
            metrics.request("open", "failure");
            throw ex;
        } finally { metrics.stop(timer, "open"); }
    }

    @PostMapping("/{sessionId}/answer")
    public ResponseEntity<?> answer(@PathVariable @NotBlank String sessionId,
            @Valid @RequestBody AnswerRequest req,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        long userId = currentUserId();
        if (!rateLimiter.tryAcquireAnswer(userId)) {
            metrics.request("answer", "rate_limited");
            enforce(false, "提交回答过于频繁，请稍后再试");
        }
        Timer.Sample timer = metrics.startTimer();
        try {
            InterviewTurnService.TurnJob result = turns.submit(userId, sessionId, req.answer(), req.requestId(), traceIdOrDefault(traceId));
            metrics.request("answer", result.status().toLowerCase(java.util.Locale.ROOT));
            return ResponseEntity.accepted().body(result);
        } catch (RuntimeException ex) {
            metrics.request("answer", "failure");
            throw ex;
        } finally { metrics.stop(timer, "answer"); }
    }

    @GetMapping("/{sessionId}/turns/{turnId}")
    public InterviewTurnService.TurnJob turn(@PathVariable @NotBlank String sessionId, @PathVariable @NotBlank String turnId) {
        return turns.get(currentUserId(), sessionId, turnId);
    }

    @PostMapping("/{sessionId}/turns/{turnId}/retry")
    public ResponseEntity<InterviewTurnService.TurnJob> retryTurn(@PathVariable @NotBlank String sessionId,
            @PathVariable @NotBlank String turnId) {
        InterviewTurnService.TurnJob result = turns.retry(currentUserId(), sessionId, turnId);
        metrics.request("answer_retry", "accepted");
        return ResponseEntity.accepted().body(result);
    }

    @PostMapping("/{sessionId}/retest")
    public ResponseEntity<InterviewSession.InterviewMessage> retest(@PathVariable @NotBlank String sessionId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        long userId = currentUserId();
        if (!rateLimiter.tryAcquireOpen(userId)) {
            metrics.request("retest", "rate_limited");
            enforce(false, "复测创建请求过于频繁，请稍后再试");
        }
        Timer.Sample timer = metrics.startTimer();
        try {
            InterviewSession.InterviewMessage result = sessions.retest(userId, sessionId, traceIdOrDefault(traceId));
            metrics.request("retest", "success");
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (RuntimeException ex) {
            metrics.request("retest", "failure");
            throw ex;
        } finally { metrics.stop(timer, "retest"); }
    }

    @PostMapping("/{sessionId}/cancel")
    public InterviewSession.InterviewMessage cancel(@PathVariable @NotBlank String sessionId) {
        long userId = currentUserId();
        Timer.Sample timer = metrics.startTimer();
        try {
            InterviewSession.InterviewMessage result = sessions.cancel(userId, sessionId);
            metrics.request("cancel", result.status().toLowerCase(java.util.Locale.ROOT));
            return result;
        } catch (RuntimeException ex) {
            metrics.request("cancel", "failure");
            throw ex;
        } finally { metrics.stop(timer, "cancel"); }
    }

    @GetMapping("/{sessionId}")
    public InterviewSession.SessionView session(@PathVariable @NotBlank String sessionId) {
        return sessions.session(currentUserId(), sessionId);
    }

    @GetMapping("/history")
    public java.util.List<InterviewSession.HistoryItem> history(
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return sessions.history(currentUserId(), limit);
    }

    @GetMapping("/{sessionId}/report")
    public InterviewSession.Report report(@PathVariable @NotBlank String sessionId) {
        return sessions.report(currentUserId(), sessionId);
    }

    @GetMapping("/{sessionId}/completion")
    public InterviewReportService.CompletionStatus completion(@PathVariable @NotBlank String sessionId) {
        return sessions.completionStatus(currentUserId(), sessionId);
    }

    @PostMapping("/{sessionId}/feedback")
    public ResponseEntity<Void> feedback(@PathVariable @NotBlank String sessionId,
            @Valid @RequestBody FeedbackRequest req) {
        sessions.feedback(currentUserId(), sessionId, req.rating(), req.reason());
        return ResponseEntity.noContent().build();
    }

    private long currentUserId() {
        Long userId = AuthContext.currentUserId();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        return userId;
    }

    private void enforce(boolean allowed, String message) {
        if (!allowed) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    private String traceIdOrDefault(String traceId) {
        return traceId == null || traceId.isBlank() ? "interview" : traceId;
    }
}
