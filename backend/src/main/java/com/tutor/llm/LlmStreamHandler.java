package com.tutor.llm;

/** Provider-neutral streaming callbacks used by application code. */
public interface LlmStreamHandler {
    void onToken(String token);

    void onComplete(LlmStreamResult result);

    void onError(Throwable error);
}
