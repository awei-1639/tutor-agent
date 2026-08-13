package com.tutor.interview;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Internal endpoint for checking scoring-contract regressions during development and release. */
@RestController
@RequestMapping("/internal/interview-evals")
public class InterviewScoreEvalController {
    private final InterviewScoreEvalService service;

    public InterviewScoreEvalController(InterviewScoreEvalService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> run() {
        return service.run();
    }

    @PostMapping("/replay")
    public Map<String, Object> replay(@RequestBody InterviewScoreEvalService.ReplayRequest request) {
        return service.replay(request);
    }

    @GetMapping("/runs")
    public java.util.List<Map<String, Object>> runs() {
        return service.listReplayRuns();
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> run(@PathVariable long id) {
        return service.getReplayRun(id);
    }
}
