package com.tutor.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.time.Duration;

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
    private final boolean devLoginEnabled;
    private final boolean cookieSecure;
    private final CsrfTokenService csrf;

    public AuthController(AuthService auth,
                          @Value("${tutor.auth.dev-login-enabled:false}") boolean devLoginEnabled,
                          @Value("${tutor.auth.cookie-secure:false}") boolean cookieSecure,
                          CsrfTokenService csrf) {
        this.auth = auth;
        this.devLoginEnabled = devLoginEnabled;
        this.cookieSecure = cookieSecure;
        this.csrf = csrf;
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
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest req) {
        try {
            AuthService.AuthResult r = auth.register(req.email(), req.password(), req.name());
            return authenticated(r);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        try {
            AuthService.AuthResult r = auth.login(req.email(), req.password());
            return authenticated(r);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    /** Dev 兼容: 单字段 name 登录, 自动创建 user_id=1 占位 */
    @PostMapping("/dev-login")
    public ResponseEntity<Map<String, Object>> devLogin(@Valid @RequestBody DevLoginRequest req) {
        if (!devLoginEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "endpoint not available");
        }
        try {
            AuthService.AuthResult r = auth.register("dev@" + req.name() + ".local",
                    "devpass", req.name());
            return authenticated(r);
        } catch (IllegalArgumentException e) {
            // 邮箱已注册 → 用真实登录
            return login(new LoginRequest("dev@" + req.name() + ".local", "devpass"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(value = "tutor_refresh", required = false) String refreshToken) {
        return logoutSession(refreshToken);
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@CookieValue(value = "tutor_refresh", required = false) String refreshToken) {
        try {
            return authenticated(auth.refresh(refreshToken));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    private ResponseEntity<Void> logoutSession(String refreshToken) {
        auth.revokeRefreshToken(refreshToken);
        return ResponseEntity.noContent()
                .header("Set-Cookie", clearAccessCookie().toString())
                .header("Set-Cookie", clearRefreshCookie().toString())
                .header("Set-Cookie", clearCsrfCookie().toString())
                .build();
    }

    private ResponseEntity<Map<String, Object>> authenticated(AuthService.AuthResult result) {
        // Browser authentication is cookie-only. Do not expose an access token to JavaScript.
        return ResponseEntity.ok()
                .header("Set-Cookie", accessCookie(result.token()).toString())
                .header("Set-Cookie", refreshCookie(result.refreshToken()).toString())
                .header("Set-Cookie", csrfCookie(csrf.issue()).toString())
                .body(Map.of("user_id", result.userId(),
                        "name", result.name() == null ? "" : result.name(),
                        "role", result.role() == null ? "USER" : result.role()));
    }

    private ResponseCookie accessCookie(String token) {
        return ResponseCookie.from(AuthInterceptor.ACCESS_COOKIE, token)
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/")
                .maxAge(Duration.ofDays(30)).build();
    }

    private ResponseCookie clearAccessCookie() {
        return ResponseCookie.from(AuthInterceptor.ACCESS_COOKIE, "")
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/")
                .maxAge(Duration.ZERO).build();
    }

    private ResponseCookie refreshCookie(String token) {
        return ResponseCookie.from("tutor_refresh", token)
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/")
                .maxAge(Duration.ofDays(30)).build();
    }

    private ResponseCookie csrfCookie(String token) {
        return ResponseCookie.from(CsrfTokenService.COOKIE, token)
                .httpOnly(false).secure(cookieSecure).sameSite("Lax").path("/")
                .maxAge(Duration.ofDays(30)).build();
    }

    private ResponseCookie clearRefreshCookie() {
        return refreshCookie("").mutate().maxAge(Duration.ZERO).build();
    }

    private ResponseCookie clearCsrfCookie() {
        return csrfCookie("").mutate().maxAge(Duration.ZERO).build();
    }
}
