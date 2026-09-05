package com.tutor.chat.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.identity.auth.AuthContext;
import com.tutor.chat.application.ChatService;
import com.tutor.chat.application.ChatModels;
import com.tutor.chat.application.ChatTurnService;
import com.tutor.chat.application.ChatTurnEvents;
import com.tutor.chat.support.ChatRateLimiter;
import com.tutor.contract.CancellationToken;
import com.tutor.contract.Evidence;
import com.tutor.guard.CitationSourcePolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

/**
 * POST /chat — SSE 事件流。事件契约见实现设计 8.1:
 * meta / citation / token / done / error
 */
@RestController
public class ChatController {
    private final ChatService chatService;
    private final ChatRateLimiter rateLimiter;
    private final ChatTurnService turns;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatController(ChatService chatService, ChatRateLimiter rateLimiter) {
        this(chatService, rateLimiter, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChatController(ChatService chatService, ChatRateLimiter rateLimiter, ChatTurnService turns) {
        this.chatService = chatService;
        this.rateLimiter = rateLimiter;
        this.turns = turns;
    }

    public record ChatRequest(Long conversationId,
                              @NotBlank @Size(max = 4000) String message) {}

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest req,
                           @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        long userId = AuthContext.requireUserId();
        if (!rateLimiter.tryAcquire(userId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }
        String requestId = idempotencyKey == null || idempotencyKey.isBlank()
                ? UUID.randomUUID().toString() : idempotencyKey.trim();
        if (requestId.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "幂等键过长");
        }
        ChatTurnService.Turn turn = turns == null ? null
                : turns.submit(userId, req.conversationId(), requestId, req.message(),
                UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        SseEmitter emitter = new SseEmitter(120_000L);
        CancellationToken cancellation = new CancellationToken();
        emitter.onCompletion(cancellation::cancel);
        emitter.onTimeout(cancellation::cancel);
        emitter.onError(error -> cancellation.cancel());
        AtomicLong tokenSequence = new AtomicLong();
        ChatTurnEvents callbacks = new ChatTurnEvents() {
                    @Override public void onMeta(long conversationId, String traceId) {
                        onMeta(conversationId, traceId, null);
                    }

                    @Override public void onMeta(long conversationId, String traceId, Integer quotaRemainingPercent) {
                        Map<String, Object> payload = new java.util.LinkedHashMap<>();
                        payload.put("conversation_id", conversationId);
                        payload.put("trace_id", traceId);
                        payload.put("turn_id", turn == null ? "" : turn.id());
                        if (quotaRemainingPercent != null) {
                            payload.put("quota_remaining_percent", quotaRemainingPercent);
                        }
                        send(emitter, "meta", payload, cancellation);
                    }

                    @Override public void onStage(String phase) {
                        send(emitter, "stage", Map.of("phase", phase), cancellation);
                    }

                    @Override public void onExpertDone(String expert, String status, String detail) {
                        send(emitter, "stage", Map.of("phase", "expert_done", "expert", expert,
                                "status", status, "detail", detail == null ? "" : detail), cancellation);
                    }

                    @Override public void onClarify(String question) {
                        send(emitter, "clarify", Map.of("question", question), cancellation);
                    }

                    @Override public void onClarify(String question, List<Map<String, String>> options) {
                        send(emitter, "clarify", Map.of("question", question,
                                "options", options == null ? List.of() : options), cancellation);
                    }

                    @Override public void onCitations(List<Evidence> evidences) {
                        for (int i = 0; i < evidences.size(); i++) {
                            Evidence e = evidences.get(i);
                            CitationSourcePolicy.Provenance provenance = CitationSourcePolicy.inspect(e);
                            send(emitter, "citation", Map.of(
                                    "sid", "S" + (i + 1), "node_id", e.nodeId(), "type", e.nodeType(),
                                    "title", evidenceTitle(e),
                                    "text", e.chunkText(),  // 前端悬浮卡与忠实度评估共用
                                    "graph_path", e.graphPath() == null ? "" : e.graphPath(),
                                    "source_url", provenance.sourceUrl(),
                                    "source_status", provenance.sourceStatus(),
                                    "evidence_hash", provenance.evidenceHash()), cancellation);
                        }
                    }

                    @Override public void onMemories(List<ChatModels.MemoryRef> memories) {
                        if (memories == null || memories.isEmpty()) return;
                        send(emitter, "memories", Map.of("items", memories), cancellation);
                    }

                    @Override public void onToken(String token) {
                        send(emitter, "token", Map.of("text", token, "seq", tokenSequence.getAndIncrement()), cancellation);
                    }

                    @Override public void onDone(long messageId, String fullText) {
                        send(emitter, "done", Map.of("message_id", messageId), cancellation);
                        emitter.complete();
                    }

                    @Override public void onDone(long messageId, String fullText, String citationStatus,
                                                  List<String> citationIssues) {
                        onDone(messageId, fullText, citationStatus, citationIssues, false);
                    }

                    @Override public void onDone(long messageId, String fullText, String citationStatus,
                                                  List<String> citationIssues, boolean truncated) {
                        Map<String, Object> payload = new java.util.LinkedHashMap<>();
                        payload.put("message_id", messageId);
                        payload.put("citation_status", citationStatus == null ? "unavailable" : citationStatus);
                        payload.put("citation_issues", citationIssues == null ? List.of() : citationIssues);
                        payload.put("truncated", truncated);
                        send(emitter, "done", payload, cancellation);
                        emitter.complete();
                    }

                    @Override public void onError(String message) {
                        onError("TURN_FAILED", message);
                    }

                    @Override public void onError(String code, String message) {
                        send(emitter, "error", Map.of("code", code, "message", message), cancellation);
                        emitter.complete();
                    }
        };
        if (turns != null) {
            turns.start(turn, callbacks, cancellation);
        } else {
            // AuthContext 使用 ThreadLocal，虚拟线程不会自动继承请求线程的身份。
            Thread.startVirtualThread(() -> {
                AuthContext.set(userId);
                try {
                    chatService.turn(req.conversationId(), req.message(), callbacks, cancellation);
                } finally {
                    AuthContext.clear();
                }
            });
        }
        return emitter;
    }

    @PostMapping("/chat/turns/{turnId}/cancel")
    public ChatTurnService.Turn cancel(@org.springframework.web.bind.annotation.PathVariable String turnId) {
        if (turns == null) return throwUnsupportedCancellation();
        return turns.cancel(AuthContext.requireUserId(), turnId);
    }

    @org.springframework.web.bind.annotation.GetMapping("/chat/turns/{turnId}")
    public ChatTurnService.Turn turn(@org.springframework.web.bind.annotation.PathVariable String turnId) {
        if (turns == null) return throwUnsupportedCancellation();
        return turns.get(AuthContext.requireUserId(), turnId);
    }

    private ChatTurnService.Turn throwUnsupportedCancellation() {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "聊天回合取消未启用");
    }

    private void send(SseEmitter emitter, String event, Object payload, CancellationToken cancellation) {
        if (cancellation.isCancelled()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(payload)));
        } catch (IOException | IllegalStateException e) {
            // 客户端断开: 停止推送并取消仍在运行的专家任务。
            cancellation.cancel();
        }
    }

    private String evidenceTitle(Evidence evidence) {
        String[] parts = evidence.chunkText().split("\\|", 3);
        return parts.length > 1 ? parts[1] : evidence.nodeId();
    }

}
