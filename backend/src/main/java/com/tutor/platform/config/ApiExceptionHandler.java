package com.tutor.platform.config;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理: 把异常翻译成统一 JSON, 让 Controller 不再各自 try-catch 转状态码。
 *
 * <p>响应体同时提供 {@code message} 与 {@code error} 两个字段, 匹配前端
 * {@code backendDetail()} 的读取优先级 (message &gt; detail &gt; error), 并附上
 * traceId 便于用一次请求链路串联日志。
 *
 * <p>安全策略: 4xx 视为业务语义明确, 回传后端文案; 5xx 一律回传通用文案,
 * 详情只进日志——避免把内部堆栈与实现细节暴露给调用方。
 *
 * <p>SSE 例外: 以 {@code response.isCommitted()} 为准, 而不是按端点声明判断。
 * 流式响应一旦提交, 状态码与报头都无法再改写, 此时 advice 写 JSON 会击穿 SSE
 * 帧格式、让客户端静默失败——必须放行交由容器关闭连接, 流中错误由
 * {@code ChatTurnEvents.onError} 发出的 SSE error 事件承担。而流开始前抛出的
 * 异常(限流/参数/未启用)响应尚未提交, 仍走本类的统一 JSON。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 5xx 对外的统一文案: 内部错误详情不得出网。 */
    private static final String SERVER_ERROR_MESSAGE = "服务暂时不可用，请稍后重试。";

    /**
     * CodedException 错误码 → HTTP 状态码。
     *
     * <p>已覆盖 {@code ToolExecutionException} 当前全部 code, 以及未来业务异常可能复用的
     * 通用code; 未登记的 code 按 500 处理, 保证新增错误码不会意外地以 4xx 暴露给调用方。
     */
    private static final Map<String, HttpStatus> CODED_STATUS = Map.ofEntries(
            Map.entry("INVALID_INPUT", HttpStatus.BAD_REQUEST),
            Map.entry("IDEMPOTENCY_REQUIRED", HttpStatus.BAD_REQUEST),
            Map.entry("TOOL_RESULT_INVALID", HttpStatus.BAD_REQUEST),
            Map.entry("FORBIDDEN", HttpStatus.FORBIDDEN),
            Map.entry("NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("CONFLICT", HttpStatus.CONFLICT),
            Map.entry("IDEMPOTENCY_IN_PROGRESS", HttpStatus.CONFLICT),
            Map.entry("CONFIRMATION_REQUIRED", HttpStatus.CONFLICT),
            Map.entry("REPEATED_TOOL_CALL", HttpStatus.CONFLICT),
            Map.entry("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("TOOL_STEP_LIMIT", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("TIMEOUT", HttpStatus.GATEWAY_TIMEOUT),
            Map.entry("UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE));

    private final MeterRegistry registry;

    public ApiExceptionHandler(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 统一错误响应体: message 供用户展示, error 为英文摘要, traceId 用于排错。 */
    public record ApiError(String message, String error, String path, String traceId) {}

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException ex,
                                               HandlerMethod handler,
                                               HttpServletRequest request,
                                               HttpServletResponse response) throws Exception {
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage(), ex, handler, request, response);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> illegalState(IllegalStateException ex,
                                                 HandlerMethod handler,
                                                 HttpServletRequest request,
                                                 HttpServletResponse response) throws Exception {
        // 内部状态错误不带业务文案, 只回通用 500
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, SERVER_ERROR_MESSAGE, ex, handler, request, response);
    }

    @ExceptionHandler(CodedException.class)
    public ResponseEntity<ApiError> coded(CodedException ex,
                                          HandlerMethod handler,
                                          HttpServletRequest request,
                                          HttpServletResponse response) throws Exception {
        HttpStatus status = CODED_STATUS.getOrDefault(ex.code(), HttpStatus.INTERNAL_SERVER_ERROR);
        boolean expose = status.is4xxClientError();
        String message = expose ? ex.getMessage() : SERVER_ERROR_MESSAGE;
        return respond(status, message, ex, handler, request, response);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> statusException(ResponseStatusException ex,
                                                    HandlerMethod handler,
                                                    HttpServletRequest request,
                                                    HttpServletResponse response) throws Exception {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String reason = ex.getReason();
        String message = (reason != null && !reason.isBlank()) ? reason : status.getReasonPhrase();
        return respond(status, message, ex, handler, request, response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validationFailed(MethodArgumentNotValidException ex,
                                                     HandlerMethod handler,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(err -> fields.putIfAbsent(err.getField(), err.getDefaultMessage()));
        String message = fields.isEmpty()
                ? "请求参数校验失败"
                : String.join("; ", fields.entrySet().stream()
                        .limit(3)
                        .map(e -> e.getKey() + " " + e.getValue())
                        .toList());
        return respond(HttpStatus.BAD_REQUEST, message, ex, handler, request, response);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
                       MethodArgumentTypeMismatchException.class,
                       HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> malformedRequest(Exception ex,
                                                     HandlerMethod handler,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) throws Exception {
        return respond(HttpStatus.BAD_REQUEST, "请求格式不正确", ex, handler, request, response);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> uploadTooLarge(MaxUploadSizeExceededException ex,
                                                   HandlerMethod handler,
                                                   HttpServletRequest request,
                                                   HttpServletResponse response) throws Exception {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "文件超过大小限制", ex, handler, request, response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> fallback(Exception ex,
                                             HandlerMethod handler,
                                             HttpServletRequest request,
                                             HttpServletResponse response) throws Exception {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, SERVER_ERROR_MESSAGE, ex, handler, request, response);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status,
                                             String message,
                                             Exception ex,
                                             HandlerMethod handler,
                                             HttpServletRequest request,
                                             HttpServletResponse response) throws Exception {
        if (response != null && response.isCommitted()) {
            // 响应已提交(典型: SSE 流已开始写入), 状态码与报头都无法再改写,
            // 此时写 JSON 会击穿流式帧格式导致客户端静默失败——放行交由容器关闭连接,
            // 流中错误由 ChatTurnEvents.onError 发出的 SSE error 事件承担。
            // 流开始前抛出的异常(限流/参数/未启用)响应尚未提交, 走下方统一 JSON。
            log.debug("响应已提交, 异常交由容器处理: {}", ex.toString());
            throw ex;
        }
        String path = request == null ? null : request.getRequestURI();
        String traceId = MDC.get(RequestTraceFilter.MDC_KEY);
        trackFailure(handler, status);

        if (status.is5xxServerError()) {
            log.error("HTTP {} {} 未处理异常 traceId={}: {}", status.value(), path, traceId, ex.getMessage(), ex);
        } else {
            log.info("HTTP {} {} 业务异常 traceId={}: {}", status.value(), path, traceId, ex.getMessage());
        }
        return ResponseEntity.status(status)
                .body(new ApiError(message, status.getReasonPhrase(), path, traceId));
    }

    /**
     * 补非成功计数: success 由 Controller 自己打, 其余全部按状态码在这里派生,
     * 保证同一请求不会被重复计入两个 result 标签 (限流只算 rate_limited)。
     */
    private void trackFailure(HandlerMethod handler, HttpStatus status) {
        if (handler == null || registry == null) return;
        TrackedOperation tracked = handler.getMethodAnnotation(TrackedOperation.class);
        if (tracked == null) return;
        String result = status == HttpStatus.TOO_MANY_REQUESTS ? "rate_limited" : "failure";
        registry.counter(tracked.counter(), "operation", tracked.operation(), "result", result)
                .increment();
    }
}
