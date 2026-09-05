package com.tutor.conversation.chat.application;

import com.tutor.contract.Evidence;

import java.util.List;
import java.util.Map;

/**
 * Application-level events emitted while a chat turn executes.
 * The HTTP/SSE adapter consumes this contract; ChatService does not own it.
 */
public interface ChatTurnEvents {
    void onMeta(long conversationId, String traceId);

    /** quotaRemainingPercent: user's remaining daily quota, or null when unavailable. */
    default void onMeta(long conversationId, String traceId, Integer quotaRemainingPercent) {
        onMeta(conversationId, traceId);
    }

    void onStage(String phase);

    default void onExpertDone(String expert, String status, String detail) {
        onStage("expert_done:" + expert + ":" + status);
    }

    void onCitations(List<Evidence> evidences);

    /** Cross-session memories actually included in this prompt. */
    default void onMemories(List<ChatModels.MemoryRef> memories) {
    }

    void onToken(String token);

    void onClarify(String question);

    default void onClarify(String question, List<Map<String, String>> options) {
        onClarify(question);
    }

    void onDone(long messageId, String fullText);

    default void onDone(long messageId, String fullText, String citationStatus,
                        List<String> citationIssues) {
        onDone(messageId, fullText);
    }

    default void onDone(long messageId, String fullText, String citationStatus,
                        List<String> citationIssues, boolean truncated) {
        onDone(messageId, fullText, citationStatus, citationIssues);
    }

    void onError(String message);

    /** Stable machine code plus user-facing message. */
    default void onError(String code, String message) {
        onError(message);
    }
}
