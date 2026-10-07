package com.tutor.platform.llm;

/** Provider-neutral streaming callbacks used by application code. */
public interface LlmStreamHandler {
    void onToken(String token);

    /** 混合思考模型 (如 GLM-4.5-Flash) 的推理增量; 默认忽略, 只有关心思考过程的调用方覆写。 */
    default void onReasoning(String token) {
    }

    void onComplete(LlmStreamResult result);

    void onError(Throwable error);
}
