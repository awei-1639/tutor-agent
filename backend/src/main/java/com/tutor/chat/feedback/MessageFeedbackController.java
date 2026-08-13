package com.tutor.chat.feedback;

import com.tutor.auth.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 回答级反馈入口，用于后续按 trace 定位检索或生成问题。 */
@RestController
@RequestMapping("/feedback")
public class MessageFeedbackController {
    private final MessageFeedbackService feedback;

    public MessageFeedbackController(MessageFeedbackService feedback) {
        this.feedback = feedback;
    }

    public record SubmitRequest(
            @NotNull Long messageId,
            @NotBlank @Pattern(regexp = "helpful|not_helpful") String rating,
            @Size(max = 64) String reason) {}

    @PostMapping
    public MessageFeedbackService.Feedback submit(@Valid @RequestBody SubmitRequest request) {
        Long userId = AuthContext.currentUserId();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        MessageFeedbackService.Feedback saved = feedback.save(userId, request.messageId(), request.rating(), request.reason());
        if (saved == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "回答不存在");
        return saved;
    }
}
