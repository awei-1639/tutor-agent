package com.tutor.llm;

import com.tutor.contract.CancellationToken;
import com.tutor.contract.Purpose;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.List;

/** 面向用户响应的流式生成能力。 */
public interface StreamingGenerationGateway {
    void chatStream(Purpose purpose, List<ChatMessage> messages, String traceId,
                    StreamingChatResponseHandler handler, CancellationToken cancellation);

}
