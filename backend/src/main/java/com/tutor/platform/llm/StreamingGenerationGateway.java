package com.tutor.platform.llm;

import com.tutor.contract.CancellationToken;
import com.tutor.contract.Purpose;

import java.util.List;

/** 面向用户响应的流式生成能力。 */
public interface StreamingGenerationGateway {
    void chatStream(Purpose purpose, List<LlmMessage> messages, String traceId,
                    LlmStreamHandler handler, CancellationToken cancellation);

}
