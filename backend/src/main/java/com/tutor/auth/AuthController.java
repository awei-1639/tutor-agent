package com.tutor.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证端点 (Phase 4 V4 4.x): 单用户模式 dev 登录。
 * 生产应接入 OAuth / SMS / Email 验证码, 这里仅 token 签发 demo。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {
    private final JwtService jwt;

    public AuthController(JwtService jwt) {
        this.jwt = jwt;
    }

    public record LoginRequest(@NotBlank String name) {}

    /** Dev 登录: 单用户模式直接返回 token (userId=1) */
    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req) {
        String token = jwt.issue(1L, req.name());
        return Map.of("user_id", 1L, "token", token);
    }
}