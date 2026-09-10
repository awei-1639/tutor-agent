package com.tutor.identity.auth;

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
 * /auth/dev-login 仅在开发配置显式开启时可用，自动创建开发账号
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

    /** 开发环境单用户登录请求。 */
    public record DevLoginRequest(@NotBlank String name) {}

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest req) {
        // IllegalArgumentException → 400 由全局异常处理统一映射
        return authenticated(auth.register(req.email(), req.password(), req.name()));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        return authenticatedOrUnauthorized(() -> auth.login(req.email(), req.password()));
    }

    /** 开发环境单字段 name 登录，自动创建开发账号。 */
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
        return authenticatedOrUnauthorized(() -> auth.refresh(refreshToken));
    }

    /**
     * 认证失败必须是 401, 而全局异常处理对 IllegalArgumentException 的默认是 400。
     * 这个差异是端点语义而非横切规则, 因此保留在此显式声明, 不下沉到全局处理器。
     */
    private ResponseEntity<Map<String, Object>> authenticatedOrUnauthorized(
            java.util.function.Supplier<AuthService.AuthResult> action) {
        try {
            return authenticated(action.get());
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
        // 浏览器认证仅使用 Cookie，绝不向 JavaScript 暴露访问令牌。
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
