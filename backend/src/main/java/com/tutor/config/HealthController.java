package com.tutor.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 容器探针：healthz 仅检查进程，readyz 检查必要存储依赖。 */
@RestController
public class HealthController {
    private final HealthReadinessService readiness;

    public HealthController(HealthReadinessService readiness) {
        this.readiness = readiness;
    }

    @GetMapping("/healthz")
    public Map<String, String> live() {
        return Map.of("status", "up");
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> ready() {
        List<String> unavailable = readiness.unavailableDependencies();
        if (unavailable.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "ready"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "not_ready", "unavailable", unavailable));
    }
}
