package com.tutor.memory.policy;

import com.tutor.auth.AuthContext;
import com.tutor.memory.application.LongTermMemoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/memory/consent")
public class MemoryConsentController {
    private final MemoryConsentService consent;
    private final LongTermMemoryService memory;

    public MemoryConsentController(MemoryConsentService consent, LongTermMemoryService memory) {
        this.consent = consent;
        this.memory = memory;
    }

    public record UpdateRequest(@NotNull Boolean enabled) {}

    @GetMapping
    public Map<String, Boolean> get() {
        return Map.of("enabled", consent.enabledFor(currentUser()));
    }

    @PutMapping
    public Map<String, Boolean> update(@Valid @RequestBody UpdateRequest request) {
        long userId = currentUser();
        if (request.enabled()) {
            if (!consent.enabledFor(userId)) memory.enableExternalMemory(userId);
        } else {
            memory.forget(userId);
        }
        return Map.of("enabled", request.enabled());
    }

    @DeleteMapping
    public void delete() {
        memory.forget(currentUser());
    }

    private static long currentUser() {
        Long id = AuthContext.currentUserId();
        if (id == null) throw new IllegalStateException("未认证");
        return id;
    }
}
