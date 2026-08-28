package com.tutor.chat.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Evidence;
import com.tutor.guard.CitationGuard;
import com.tutor.memory.local.ConversationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** 异步校验回答引用并持久化校验状态；校验故障不得影响已发送回答。 */
@Component
final class CitationVerificationService {
    private static final Logger log = LoggerFactory.getLogger(CitationVerificationService.class);
    private final ConversationStore conversations;
    private final CitationGuard guard;
    private final ObjectMapper mapper = new ObjectMapper();

    CitationVerificationService(ConversationStore conversations, CitationGuard guard) {
        this.conversations = conversations;
        this.guard = guard;
    }

    void verify(long messageId, String text, List<Evidence> evidences, String traceId) {
        try {
            CitationGuard.GuardResult result = guard.guard(text, evidences, traceId);
            if (result == null) throw new IllegalStateException("citation guard returned null");
            String issues = mapper.writeValueAsString(result.issues() == null ? List.of() : result.issues());
            conversations.updateCitationVerification(messageId,
                    result.status() == null ? "unavailable" : result.status(), issues);
        } catch (Exception error) {
            log.warn("引用校验结果不可用 trace={}", traceId, error);
            try {
                conversations.updateCitationVerification(messageId, "unavailable", "[]");
            } catch (Exception persistenceError) {
                log.warn("引用校验降级状态写入失败 trace={}", traceId, persistenceError);
            }
        }
    }
}
