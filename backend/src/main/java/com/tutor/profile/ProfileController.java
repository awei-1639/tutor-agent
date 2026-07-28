package com.tutor.profile;

import com.tutor.auth.AuthContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 画像查看与关键字段确认 (实现设计 8.1: POST /profile/confirm) */
@RestController
public class ProfileController {
    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/profile")
    public Map<String, Object> get() {
        long uid = AuthContext.currentUserId() == null ? 1L : AuthContext.currentUserId();
        return profileService.snapshot(uid);
    }

    public record ConfirmRequest(@NotBlank String field, boolean accept) {}

    @PostMapping("/profile/confirm")
    public Map<String, Object> confirm(@Valid @RequestBody ConfirmRequest req) {
        long uid = AuthContext.currentUserId() == null ? 1L : AuthContext.currentUserId();
        return profileService.confirmField(uid, req.field(), req.accept());
    }
}
