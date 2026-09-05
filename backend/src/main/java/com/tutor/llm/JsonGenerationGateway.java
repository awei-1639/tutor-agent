package com.tutor.llm;

import com.tutor.contract.Purpose;
import java.time.Duration;
import java.util.List;

/** 受预算、超时和重试约束的结构化文本生成能力。 */
public interface JsonGenerationGateway {
    String chatJson(Purpose purpose, List<LlmMessage> messages, String traceId);

    String chatJson(Purpose purpose, List<LlmMessage> messages, String traceId,
                    Duration timeout, int maxAttempts);
}
