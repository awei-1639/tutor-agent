package com.tutor.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.auth.AuthContext;
import com.tutor.auth.AuthInterceptor;
import com.tutor.contract.Evidence;
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * POST /chat — SSE 事件流。事件契约见实现设计 8.1:
 * meta / citation / token / done / error
 */
@RestController
public class ChatController {
    private final ChatService chatService;
    private final ChatRateLimiter rateLimiter;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatController(ChatService chatService, ChatRateLimiter rateLimiter) {
        this.chatService = chatService;
        this.rateLimiter = rateLimiter;
    }

    public record ChatRequest(Long conversationId,
                              @NotBlank @Size(max = 4000) String message) {}

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest req) {
        long userId = AuthContext.currentUserId() == null ? AuthInterceptor.DEV_USER_ID : AuthContext.currentUserId();
        if (!rateLimiter.tryAcquire(userId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }
        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicLong tokenSequence = new AtomicLong();
        Executors.newVirtualThreadPerTaskExecutor().submit(() ->
                chatService.turn(req.conversationId(), req.message(), new ChatService.TurnEvents() {
                    @Override public void onMeta(long conversationId, String traceId) {
                        send(emitter, "meta", Map.of("conversation_id", conversationId, "trace_id", traceId));
                    }

                    @Override public void onStage(String phase) {
                        send(emitter, "stage", Map.of("phase", phase));
                    }

                    @Override public void onClarify(String question) {
                        send(emitter, "clarify", Map.of("question", question));
                    }

                    @Override public void onCitations(List<Evidence> evidences) {
                        for (int i = 0; i < evidences.size(); i++) {
                            Evidence e = evidences.get(i);
                            send(emitter, "citation", Map.of(
                                    "sid", "S" + (i + 1), "node_id", e.nodeId(), "type", e.nodeType(),
                                    "title", evidenceTitle(e),
                                    "text", e.chunkText(),  // 前端悬浮卡与忠实度评估共用
                                    "graph_path", e.graphPath() == null ? "" : e.graphPath(),
                                    "source_url", e.sourceUrl() == null ? "" : e.sourceUrl()));
                        }
                    }

                    @Override public void onToken(String token) {
                        send(emitter, "token", Map.of("text", token, "seq", tokenSequence.getAndIncrement()));
                    }

                    @Override public void onDone(long messageId, String fullText) {
                        send(emitter, "done", Map.of("message_id", messageId));
                        emitter.complete();
                    }

                    @Override public void onError(String message) {
                        send(emitter, "error", Map.of("code", "TURN_FAILED", "message", message));
                        emitter.complete();
                    }
                }));
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(payload)));
        } catch (IOException | IllegalStateException e) {
            // 客户端断开: 停止推送即可, 消息已在服务侧落库
        }
    }

    private String evidenceTitle(Evidence evidence) {
        String[] parts = evidence.chunkText().split("\\|", 3);
        return parts.length > 1 ? parts[1] : evidence.nodeId();
    }
}
