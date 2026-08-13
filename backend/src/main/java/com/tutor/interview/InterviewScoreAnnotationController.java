package com.tutor.interview;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Administrator workflow for collecting human labels used by replay evaluation. */
@RestController
@RequestMapping("/admin/interview-evals/annotations")
public class InterviewScoreAnnotationController {
    private final InterviewScoreAnnotationService service;
    private final InterviewScoreEvalService evals;

    public InterviewScoreAnnotationController(InterviewScoreAnnotationService service, InterviewScoreEvalService evals) {
        this.service = service;
        this.evals = evals;
    }

    public record Request(@Min(0) @Max(10) int humanScore, @Size(max = 2000) String rationale) {}
    public record ReplayRequest(@Size(max = 128) String datasetVersion, @Min(1) @Max(5) Integer minReviewers) {}

    @PostMapping("/replay")
    public Map<String, Object> replay(@Valid @RequestBody ReplayRequest request) {
        InterviewScoreEvalService.ReplayRequest input = service.exportReplay(
                request.datasetVersion() == null || request.datasetVersion().isBlank() ? "human-gold-current" : request.datasetVersion(),
                request.minReviewers() == null ? 2 : request.minReviewers());
        return evals.replay(input);
    }

    @PostMapping("/{questionId}")
    public Map<String, Object> upsert(@PathVariable long questionId, @Valid @RequestBody Request request) {
        return service.upsert(questionId, request.humanScore(), request.rationale());
    }

    @GetMapping("/queue")
    public List<Map<String, Object>> queue(@RequestParam(defaultValue = "20") int limit,
                                           @RequestParam(defaultValue = "2") int minReviewers,
                                           @RequestParam(defaultValue = "true") boolean blind,
                                           @RequestParam(defaultValue = "1") int maxPerSession) {
        return service.queue(limit, minReviewers, blind, maxPerSession);
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "50") int limit) {
        return service.list(limit);
    }
}
