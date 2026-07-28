package com.tutor.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 认证端点 (Phase 4 V4 4.x): 注册 + 登录。
 * - POST /auth/register: email + password + name → token
 * - POST /auth/login: email + password → token
 * /auth/login 单参数模式 (name only) 保留为 dev 入口, 自动建账号
 */
@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 6, max = 64) String password,
            String name) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password) {}

    /** Dev 单用户模式: name 直接登录 (向后兼容) */
    public record DevLoginRequest(@NotBlank String name) {}

    @PostMapping("/register")
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest req) {
        try {
            AuthService.AuthResult r = auth.register(req.email(), req.password(), req.name());
            return Map.of("user_id", r.userId(), "token", r.token(), "name", r.name());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req) {
        try {
            AuthService.AuthResult r = auth.login(req.email(), req.password());
            return Map.of("user_id", r.userId(), "token", r.token(), "name", r.name());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    /** Dev 兼容: 单字段 name 登录, 自动创建 user_id=1 占位 */
    @PostMapping("/dev-login")
    public Map<String, Object> devLogin(@Valid @RequestBody DevLoginRequest req) {
        try {
            AuthService.AuthResult r = auth.register("dev@" + req.name() + ".local",
                    "devpass", req.name());
            return Map.of("user_id", r.userId(), "token", r.token(), "name", r.name());
        } catch (IllegalArgumentException e) {
            // 邮箱已注册 → 用真实登录
            return login(new LoginRequest("dev@" + req.name() + ".local", "devpass"));
        }
    }
}