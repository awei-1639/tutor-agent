package com.tutor.evaluation.eval;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 开发/评测工作台接口；生产 profile 通过 internal endpoint 开关关闭。 */
@RestController
@RequestMapping("/internal/evals")
public class RagEvalController {
    private final RagEvalService service;

    public RagEvalController(RagEvalService service) { this.service = service; }

    @PostMapping
    public Map<String, Object> start(@RequestBody(required = false) RagEvalService.StartRequest request) {
        return service.start(request);
    }

    @GetMapping
    public List<Map<String, Object>> list() { return service.listRuns(); }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) { return service.getRun(id); }
}
