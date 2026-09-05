package com.tutor.conversation.chat.application;

import com.tutor.contract.CancellationToken;
import com.tutor.conversation.memory.local.ConversationStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/** Application API for durable chat-turn admission, cancellation, and completion. */
@Service
public class ChatTurnService {
    private final ChatTurnJobStore jobs;
    private final ConversationStore conversations;
    private final ChatTurnWorker worker;

    @Autowired
    public ChatTurnService(ChatTurnJobStore jobs, ConversationStore conversations,
                           ChatTurnWorker worker, MeterRegistry metrics) {
        this.jobs = jobs;
        this.conversations = conversations;
        this.worker = worker;
        registerMetrics(metrics);
    }

    /** Compatibility constructor retained for database-focused integration tests. */
    public ChatTurnService(org.springframework.jdbc.core.JdbcTemplate jdbc,
                           ConversationStore conversations,
                           ObjectProvider<ChatService> chatService,
                           MeterRegistry metrics) {
        this.jobs = new ChatTurnJobStore(jdbc);
        this.conversations = conversations;
        this.worker = new ChatTurnWorker(jobs, chatService);
        registerMetrics(metrics);
    }

    public record Turn(String id, long conversationId, String requestId, String question, String traceId,
                       String status, int attempts, Long answerMessageId, String lastError,
                       Instant createdAt, Instant finishedAt) {}

    public record Claim(String id, long userId, long conversationId, String question, String traceId,
                        int attempts, java.util.UUID leaseToken) {}

    /** Admission is one short transaction. It also makes the user message durable exactly once. */
    @Transactional
    public Turn submit(long userId, Long requestedConversationId, String requestId,
                       String question, String traceId) {
        jobs.ensureUser(userId);
        Optional<Turn> existing = jobs.findByRequest(userId, requestId);
        if (existing.isPresent()) {
            Turn turn = existing.get();
            if (!turn.question().equals(question)
                    || (requestedConversationId != null && requestedConversationId != turn.conversationId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "幂等键已被另一条聊天请求使用");
            }
            return turn;
        }

        long conversationId = conversations.ensureConversation(requestedConversationId, userId);
        String id = java.util.UUID.randomUUID().toString();
        int inserted = jobs.insert(id, userId, conversationId, requestId, question, traceId);
        if (inserted != 1) {
            return jobs.findByRequest(userId, requestId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "当前会话已有正在处理的请求"));
        }
        conversations.appendMessage(conversationId, "user", question, null, null, traceId,
                question.length() / 2, null, null, id);
        return get(userId, id);
    }

    public Turn get(long userId, String id) {
        return jobs.find(userId, id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "聊天回合不存在"));
    }

    /** Explicit cancellation is durable and fences any old worker immediately. */
    public Turn cancel(long userId, String id) {
        int changed = jobs.cancel(userId, id);
        worker.cancel(id);
        if (changed == 0) {
            Turn current = get(userId, id);
            if ("CANCELLED".equals(current.status())) return current;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该聊天回合当前不可取消");
        }
        return get(userId, id);
    }

    /** Starts an admitted turn, or does nothing if another worker already claimed it. */
    public void start(Turn turn, ChatTurnEvents events, CancellationToken cancellation) {
        worker.start(turn, events, cancellation);
    }

    @Transactional
    public OptionalLong completeWithMessage(Claim claim, String content, String intent, String citationsJson,
                                            int tokenCount, String citationStatus, String citationIssuesJson) {
        if (!jobs.markCompleted(claim)) return OptionalLong.empty();
        long messageId = conversations.appendMessage(claim.conversationId(), "assistant", content, intent,
                citationsJson, claim.traceId(), tokenCount, citationStatus, citationIssuesJson, claim.id());
        jobs.setAnswerMessageId(claim, messageId);
        return OptionalLong.of(messageId);
    }

    public boolean owns(Claim claim) {
        return jobs.owns(claim);
    }

    public void fail(Claim claim, String error) {
        jobs.fail(claim, error);
    }

    /** Releases the compatibility worker; the Spring worker is closed by its own lifecycle hook. */
    public void shutdown() {
        worker.shutdown();
    }

    private void registerMetrics(MeterRegistry metrics) {
        Gauge.builder("tutor.chat.turns.active", jobs, ChatTurnJobStore::activeCount)
                .description("Chat turns admitted but not yet terminal").register(metrics);
    }
}
