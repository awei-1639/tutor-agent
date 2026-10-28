package com.tutor.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
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
 *
 * <p>三个入口都先经过 {@link AuthAttemptLimiter}: 这是全站唯一「无认证 + BCrypt」
 * 的路径, 不加限流即可被无限撞库或被并发请求烧满 CPU。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService auth;
    private final boolean devLoginEnabled;
    private final boolean cookieSecure;
    private final CsrfTokenService csrf;
    private final AuthAttemptLimiter attempts;

    public AuthController(AuthService auth,
                          @Value("${tutor.auth.dev-login-enabled:false}") boolean devLoginEnabled,
                          @Value("${tutor.auth.cookie-secure:false}") boolean cookieSecure,
                          CsrfTokenService csrf,
                          AuthAttemptLimiter attempts) {
        this.auth = auth;
        this.devLoginEnabled = devLoginEnabled;
        this.cookieSecure = cookieSecure;
        this.csrf = csrf;
        this.attempts = attempts;
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
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest req,
                                                        HttpServletRequest request) {
        // 注册不区分成功/失败计次: 邮箱占用探测本身也是攻击面
        attempts.acquire(request, null);
        // IllegalArgumentException → 400 由全局异常处理统一映射
        return authenticated(auth.register(req.email(), req.password(), req.name()));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest req,
                                                     HttpServletRequest request) {
        attempts.acquire(request, req.email());
        try {
            return authenticated(auth.login(req.email(), req.password()));
        } catch (IllegalArgumentException e) {
            // 邮箱不存在 / 密码错 / 已禁用 / 已删除 一律计一次失败: 若只对「密码错」计次,
            // 攻击者就能用响应差异反推账号是否存在。
            attempts.recordFailure(req.email());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    /** 开发环境单字段 name 登录，自动创建开发账号。 */
    @PostMapping("/dev-login")
    public ResponseEntity<Map<String, Object>> devLogin(@Valid @RequestBody DevLoginRequest req,
                                                        HttpServletRequest request) {
        if (!devLoginEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "endpoint not available");
        }
        // dev-login 用固定口令创建账号, 公网可达时等价于注册后门, 同样纳入 IP 计次
        attempts.acquire(request, null);
        String email = "dev@" + req.name() + ".local";
        try {
            return authenticated(auth.register(email, "devpass", req.name()));
        } catch (IllegalArgumentException e) {
            // 邮箱已注册 → 用真实登录
            try {
                return authenticated(auth.login(email, "devpass"));
            } catch (IllegalArgumentException failed) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, failed.getMessage());
            }
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
