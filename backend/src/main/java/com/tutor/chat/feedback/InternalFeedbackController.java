package com.tutor.chat.feedback;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 开发/评测环境的反馈聚合入口；prod profile 会整体关闭 /internal。 */
@RestController
@RequestMapping("/internal/feedback")
public class InternalFeedbackController {
    private final MessageFeedbackService feedback;

    public InternalFeedbackController(MessageFeedbackService feedback) {
        this.feedback = feedback;
    }

    @GetMapping("/summary")
    public MessageFeedbackService.Summary summary() {
        return feedback.summary();
    }
}
