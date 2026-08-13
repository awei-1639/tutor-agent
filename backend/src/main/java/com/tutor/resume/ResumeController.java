package com.tutor.resume;

import com.fasterxml.jackson.databind.JsonNode;
import com.tutor.auth.AuthContext;
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
    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @PostMapping("/resumes")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) throw new IllegalArgumentException("文件为空");
        long uid = AuthContext.requireUserId();
        ResumeService.UploadResult r = resumeService.upload(uid, file);
        return Map.of(
                "resume_id", r.resumeId(),
                "masked_pii_count", r.maskedPiiCount(),
                "structured", (JsonNode) r.structured());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> serverErr(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
    }
}
