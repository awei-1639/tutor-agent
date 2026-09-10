package com.tutor.platform.config;

import com.tutor.agent.tool.ToolExecutionException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 全局异常处理的回归测试: 状态码映射、文案外泄边界与 SSE 放行。 */
class ApiExceptionHandlerTest {

    private static final String SERVER_ERROR_MESSAGE = "服务暂时不可用，请稍后重试。";

    private MeterRegistry registry;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiExceptionHandler(registry))
                .build();
    }

    @Test
    void businessMessageReachesClientOn4xx() throws Exception {
        mvc.perform(get("/probe/arg"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("邮箱已注册"))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/probe/arg"));
    }

    @Test
    void serverErrorNeverExposesInternalDetail() throws Exception {
        mvc.perform(get("/probe/state"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(SERVER_ERROR_MESSAGE))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("连接池"))));
    }

    @Test
    void unexpectedFailureIsMasked() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(SERVER_ERROR_MESSAGE));
    }

    @Test
    void codedExceptionMapsByCode() throws Exception {
        mvc.perform(get("/probe/coded-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("格式不支持"));

        mvc.perform(get("/probe/coded-timeout"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.message").value(SERVER_ERROR_MESSAGE));
    }

    @Test
    void responseStatusKeepsReasonAndStatus() throws Exception {
        mvc.perform(get("/probe/unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("账号已被禁用"));
    }

    @Test
    void tooManyRequestsIsReportedAsRateLimited() throws Exception {
        mvc.perform(get("/probe/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("创建面试请求过于频繁，请稍后再试"));
    }

    @Test
    void validationFailureListsOffendingField() throws Exception {
        mvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("answer")));
    }

    @Test
    void failureCounterIsDerivedFromStatus() throws Exception {
        mvc.perform(get("/probe/tracked")).andExpect(status().isInternalServerError());

        assertThat(registry.counter("tutor.probe.requests", "operation", "tracked", "result", "failure").count())
                .isEqualTo(1D);
        assertThat(registry.find("tutor.probe.requests")
                .tag("result", "rate_limited").counter())
                .isNull();
    }

    @Test
    void rateLimitedCounterIsDerivedFrom429() throws Exception {
        mvc.perform(get("/probe/tracked-rate-limited")).andExpect(status().isTooManyRequests());

        assertThat(registry.counter("tutor.probe.requests", "operation", "tracked", "result", "rate_limited").count())
                .isEqualTo(1D);
        assertThat(registry.find("tutor.probe.requests")
                .tag("operation", "tracked").tag("result", "failure").counter())
                .isNull();
    }

    @Test
    void sseFailureBeforeStreamStartsStillGetsJson() throws Exception {
        // SSE 方法体内抛出的异常发生在流开始之前, 响应未提交,
        // 必须走统一 JSON 而不是被当成"流中错误"放行给容器。
        mvc.perform(get("/probe/stream"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(SERVER_ERROR_MESSAGE))
                .andExpect(jsonPath("$.path").value("/probe/stream"));
    }

    @Test
    void committedResponseRethrowsForContainerHandling() {
        // 响应已提交(SSE 流进行中): 状态码无法改写, 写 JSON 会击穿流式帧格式,
        // advice 必须原样放行交由容器关闭连接。
        org.springframework.mock.web.MockHttpServletResponse committed =
                new org.springframework.mock.web.MockHttpServletResponse();
        committed.setCommitted(true);
        ApiExceptionHandler handler = new ApiExceptionHandler(registry);

        assertThatThrownBy(() -> handler.badRequest(new IllegalArgumentException("邮箱已注册"), null, null, committed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("邮箱已注册");
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/arg")
        String arg() {
            throw new IllegalArgumentException("邮箱已注册");
        }

        @GetMapping("/probe/state")
        String state() {
            throw new IllegalStateException("连接池已耗尽: 内部细节不得出网");
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new RuntimeException("数据库主从延迟 45s, ssl=internal-host:5432");
        }

        @GetMapping("/probe/coded-invalid")
        String codedInvalid() {
            throw new ToolExecutionException("INVALID_INPUT", "格式不支持");
        }

        @GetMapping("/probe/coded-timeout")
        String codedTimeout() {
            throw new ToolExecutionException("TIMEOUT", "模型超时");
        }

        @GetMapping("/probe/unauthorized")
        String unauthorized() {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号已被禁用");
        }

        @GetMapping("/probe/rate-limited")
        String rateLimited() {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "创建面试请求过于频繁，请稍后再试");
        }

        @GetMapping("/probe/tracked")
        @TrackedOperation(counter = "tutor.probe.requests", operation = "tracked")
        String tracked() {
            throw new IllegalStateException("boom");
        }

        @GetMapping("/probe/tracked-rate-limited")
        @TrackedOperation(counter = "tutor.probe.requests", operation = "tracked")
        String trackedRateLimited() {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请稍后再试");
        }

        @PostMapping("/probe/validate")
        String validate(@org.springframework.web.bind.annotation.RequestBody @jakarta.validation.Valid Answer body) {
            return body.answer();
        }

        @GetMapping(value = "/probe/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream() {
            throw new IllegalStateException("stream interrupted");
        }

        record Answer(@jakarta.validation.constraints.NotBlank String answer) {}
    }
}
