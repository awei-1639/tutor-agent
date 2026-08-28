package com.tutor.resume;

import com.tutor.auth.AuthContext;
import com.tutor.config.RequestTraceFilter;
import com.tutor.tool.ToolExecutionContext;
import com.tutor.tool.ToolExecutor;
import com.tutor.tool.ToolInputs;
import com.tutor.tool.ToolExecutionException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** POST /resumes (实现设计 8.1): multipart上传, 同步解析返回结构化预览, 失败明确报错 */
@RestController
public class ResumeController {
    private final ToolExecutor toolExecutor;

    public ResumeController(ToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/resumes")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @org.springframework.web.bind.annotation.RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        if (file.isEmpty()) throw new IllegalArgumentException("文件为空");
        String traceId = MDC.get(RequestTraceFilter.MDC_KEY);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? traceId : idempotencyKey;
        return (Map<String, Object>) toolExecutor.execute("resume_upload", new ToolInputs.ResumeUpload(file),
                new ToolExecutionContext(traceId, "resume", AuthContext.requireUserId(), key, false));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> serverErr(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ToolExecutionException.class)
    public ResponseEntity<Map<String, String>> toolError(ToolExecutionException e) {
        HttpStatus status = switch (e.code()) {
            case "INVALID_INPUT" -> HttpStatus.BAD_REQUEST;
            case "TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }
}
