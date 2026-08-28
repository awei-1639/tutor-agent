package com.tutor.chat.application;

import com.tutor.auth.AuthContext;
import com.tutor.contract.CancellationToken;
import com.tutor.memory.local.ConversationStore;
import io.micrometer.core.instrument.Gauge;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.PreDestroy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable coordinator for chat turns. SSE is only a view of a turn; the
 * database row owns admission, cancellation and the final commit decision.
 */
@Service
public class ChatTurnService {
    private static final String ACTIVE = "status IN ('ACCEPTED', 'RUNNING')";
    private static final long LEASE_SECONDS = 120;
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final ConversationStore conversations;
    private final ObjectProvider<ChatService> chatService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots = new Semaphore(4);
    private final ConcurrentHashMap<String, ActiveRun> active = new ConcurrentHashMap<>();

    public ChatTurnService(JdbcTemplate jdbc, ConversationStore conversations, ObjectProvider<ChatService> chatService,
                           io.micrometer.core.instrument.MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.chatService = chatService;
        Gauge.builder("tutor.chat.turns.active", jdbc,
                        source -> source.queryForObject("SELECT count(*) FROM chat_turns WHERE " + ACTIVE, Integer.class))
                .description("Chat turns admitted but not yet terminal").register(metrics);
    }

    public record Turn(String id, long conversationId, String requestId, String question, String traceId,
                       String status, int attempts, Long answerMessageId, String lastError,
                       Instant createdAt, Instant finishedAt) {}

    public record Claim(String id, long userId, long conversationId, String question, String traceId,
                        int attempts, UUID leaseToken) {}

    private record ActiveRun(Claim claim, CancellationToken cancellation) {}

    /** Admission is one short transaction. It also makes the user message durable exactly once. */
    @Transactional
    public Turn submit(long userId, Long requestedConversationId, String requestId, String question, String traceId) {
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, userId);

        Optional<Turn> existing = findByRequest(userId, requestId);
        if (existing.isPresent()) {
            Turn turn = existing.get();
            if (!turn.question().equals(question)
                    || (requestedConversationId != null && requestedConversationId != turn.conversationId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "幂等键已被另一条聊天请求使用");
            }
            return turn;
        }

        long conversationId = conversations.ensureConversation(requestedConversationId, userId);
        String id = UUID.randomUUID().toString();
        int inserted = jdbc.update("""
                INSERT INTO chat_turns (id, user_id, conversation_id, request_id, question, trace_id)
                VALUES (?::uuid, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, id, userId, conversationId, requestId, question, traceId);
        if (inserted != 1) {
            Optional<Turn> duplicate = findByRequest(userId, requestId);
            if (duplicate.isPresent()) return duplicate.get();
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话已有正在处理的请求");
        }

        conversations.appendMessage(conversationId, "user", question, null, null, traceId,
                question.length() / 2, null, null, id);
        return get(userId, id);
    }

    public Turn get(long userId, String id) {
        return jdbc.query("""
                SELECT id, conversation_id, request_id, question, trace_id, status, attempts,
                       answer_message_id, last_error, created_at, finished_at
                FROM chat_turns WHERE id=?::uuid AND user_id=?
                """, (rs, i) -> map(rs), id, userId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "聊天回合不存在"));
    }

    /** Explicit cancellation is durable and fences any old worker immediately. */
    public Turn cancel(long userId, String id) {
        int changed = jdbc.update("""
                UPDATE chat_turns
                SET status='CANCELLED', cancel_requested_at=now(), lease_token=NULL, lease_until=NULL,
                    finished_at=now(), updated_at=now()
                WHERE id=?::uuid AND user_id=? AND """ + " " + ACTIVE, id, userId);
        ActiveRun run = active.remove(id);
        if (run != null) run.cancellation().cancel();
        if (changed == 0) {
            Turn current = get(userId, id);
            if ("CANCELLED".equals(current.status())) return current;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该聊天回合当前不可取消");
        }
        return get(userId, id);
    }

    /** Starts an admitted turn, or does nothing if another worker already claimed it. */
    public void start(Turn turn, ChatService.TurnEvents events, CancellationToken cancellation) {
        Claim claim = claimById(turn.id());
        if (claim == null) return;
        // The HTTP caller must retain its SSE subscriber even when the
        // recovery worker slots are full. Rate limiting and the per-
        // conversation unique index still bound admission.
        run(claim, events, cancellation, slots.tryAcquire());
    }

    @Scheduled(fixedDelayString = "${tutor.chat.turn.poll-ms:1000}")
    void recoverAndDispatch() {
        expireExhaustedLeases();
        if (!slots.tryAcquire()) return;
        Claim claim = claimNext();
        if (claim == null) {
            slots.release();
            return;
        }
        run(claim, new NoopEvents(), new CancellationToken(), true);
    }

    /** A live worker renews only its own lease. A crashed JVM therefore remains recoverable. */
    @Scheduled(fixedDelayString = "${tutor.chat.turn.lease-renew-ms:30000}")
    void renewLeases() {
        active.forEach((id, run) -> jdbc.update("""
                UPDATE chat_turns SET lease_until=now() + (? * interval '1 second'), updated_at=now()
                WHERE id=?::uuid AND status='RUNNING' AND lease_token=? AND lease_until > now()
                """, LEASE_SECONDS, id, run.claim().leaseToken()));
    }

    private void run(Claim claim, ChatService.TurnEvents events, CancellationToken cancellation, boolean ownsSlot) {
        ActiveRun run = new ActiveRun(claim, cancellation);
        active.put(claim.id(), run);
        executor.execute(() -> {
            AuthContext.set(claim.userId());
            AtomicBoolean turnFailed = new AtomicBoolean();
            ChatService.TurnEvents executionEvents = forwardingEvents(events, turnFailed);
            try {
                chatService.getObject().turn(claim.conversationId(), claim.question(), executionEvents, cancellation, claim);
                if (owns(claim)) {
                    if (turnFailed.get()) fail(claim, "聊天回合执行失败");
                    else if (cancellation.isCancelled()) cancelClaim(claim);
                    else fail(claim, "聊天回合未产生终态");
                }
            } catch (Exception error) {
                fail(claim, safeError(error));
            } finally {
                AuthContext.clear();
                active.remove(claim.id(), run);
                if (ownsSlot) slots.release();
            }
        });
    }

    @Transactional
    public OptionalLong completeWithMessage(Claim claim, String content, String intent, String citationsJson,
                                            int tokenCount, String citationStatus, String citationIssuesJson) {
        int changed = jdbc.update("""
                UPDATE chat_turns
                SET status='COMPLETED', lease_token=NULL, lease_until=NULL, finished_at=now(), updated_at=now()
                WHERE id=?::uuid AND status='RUNNING' AND lease_token=? AND lease_until > now()
                """, claim.id(), claim.leaseToken());
        if (changed != 1) return OptionalLong.empty();
        long messageId = conversations.appendMessage(claim.conversationId(), "assistant", content, intent,
                citationsJson, claim.traceId(), tokenCount, citationStatus, citationIssuesJson, claim.id());
        jdbc.update("UPDATE chat_turns SET answer_message_id=? WHERE id=?::uuid AND status='COMPLETED'",
                messageId, claim.id());
        return OptionalLong.of(messageId);
    }

    public boolean owns(Claim claim) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM chat_turns
                WHERE id=?::uuid AND status='RUNNING' AND lease_token=? AND lease_until > now()
                """, Integer.class, claim.id(), claim.leaseToken());
        return count != null && count == 1;
    }

    public void fail(Claim claim, String error) {
        jdbc.update("""
                UPDATE chat_turns
                SET status='FAILED', last_error=?, lease_token=NULL, lease_until=NULL,
                    finished_at=now(), updated_at=now()
                WHERE id=?::uuid AND status='RUNNING' AND lease_token=? AND lease_until > now()
                """, error, claim.id(), claim.leaseToken());
    }

    private void cancelClaim(Claim claim) {
        jdbc.update("""
                UPDATE chat_turns
                SET status='CANCELLED', cancel_requested_at=COALESCE(cancel_requested_at, now()),
                    lease_token=NULL, lease_until=NULL, finished_at=now(), updated_at=now()
                WHERE id=?::uuid AND status='RUNNING' AND lease_token=? AND lease_until > now()
                """, claim.id(), claim.leaseToken());
    }

    private void expireExhaustedLeases() {
        jdbc.update("""
                UPDATE chat_turns SET status='FAILED', last_error='任务租约已耗尽，未能恢复',
                    lease_token=NULL, lease_until=NULL, finished_at=now(), updated_at=now()
                WHERE status='RUNNING' AND lease_until < now() AND attempts >= ?
                """, MAX_ATTEMPTS);
    }

    private Claim claimById(String id) {
        return jdbc.query("""
                UPDATE chat_turns
                SET status='RUNNING', attempts=attempts+1, started_at=COALESCE(started_at, now()),
                    lease_token=?::uuid, lease_until=now() + (? * interval '1 second'), updated_at=now()
                WHERE id=?::uuid AND status='ACCEPTED'
                RETURNING id, user_id, conversation_id, question, trace_id, attempts, lease_token
                """, (rs, i) -> claim(rs), UUID.randomUUID(), LEASE_SECONDS, id).stream().findFirst().orElse(null);
    }

    private Claim claimNext() {
        return jdbc.query("""
                WITH candidate AS (
                  SELECT id FROM chat_turns
                  WHERE (status='ACCEPTED')
                     OR (status='RUNNING' AND lease_until < now() AND attempts < ?)
                  ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE chat_turns t
                SET status='RUNNING', attempts=t.attempts+1, started_at=COALESCE(t.started_at, now()),
                    lease_token=?::uuid, lease_until=now() + (? * interval '1 second'), updated_at=now()
                FROM candidate WHERE t.id=candidate.id
                RETURNING t.id, t.user_id, t.conversation_id, t.question, t.trace_id, t.attempts, t.lease_token
                """, (rs, i) -> claim(rs), MAX_ATTEMPTS, UUID.randomUUID(), LEASE_SECONDS)
                .stream().findFirst().orElse(null);
    }

    private Optional<Turn> findByRequest(long userId, String requestId) {
        return jdbc.query("""
                SELECT id, conversation_id, request_id, question, trace_id, status, attempts,
                       answer_message_id, last_error, created_at, finished_at
                FROM chat_turns WHERE user_id=? AND request_id=?
                """, (rs, i) -> map(rs), userId, requestId).stream().findFirst();
    }

    private Claim claim(ResultSet rs) throws SQLException {
        return new Claim(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getInt(6), rs.getObject(7, UUID.class));
    }

    private Turn map(ResultSet rs) throws SQLException {
        return new Turn(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getInt(7), (Long) rs.getObject(8), rs.getString(9),
                rs.getTimestamp(10).toInstant(), rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant());
    }

    private String safeError(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private ChatService.TurnEvents forwardingEvents(ChatService.TurnEvents target, AtomicBoolean failed) {
        return new ChatService.TurnEvents() {
            @Override public void onMeta(long conversationId, String traceId) { target.onMeta(conversationId, traceId); }
            @Override public void onStage(String phase) { target.onStage(phase); }
            @Override public void onExpertDone(String expert, String status, String detail) {
                target.onExpertDone(expert, status, detail);
            }
            @Override public void onCitations(List<com.tutor.contract.Evidence> evidences) { target.onCitations(evidences); }
            @Override public void onToken(String token) { target.onToken(token); }
            @Override public void onClarify(String question) { target.onClarify(question); }
            @Override public void onClarify(String question, List<java.util.Map<String, String>> options) {
                target.onClarify(question, options);
            }
            @Override public void onDone(long messageId, String fullText) { target.onDone(messageId, fullText); }
            @Override public void onDone(long messageId, String fullText, String status, List<String> issues) {
                target.onDone(messageId, fullText, status, issues);
            }
            @Override public void onError(String message) {
                failed.set(true);
                target.onError(message);
            }
        };
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static final class NoopEvents implements ChatService.TurnEvents {
        @Override public void onMeta(long conversationId, String traceId) { }
        @Override public void onStage(String phase) { }
        @Override public void onCitations(List<com.tutor.contract.Evidence> evidences) { }
        @Override public void onToken(String token) { }
        @Override public void onClarify(String question) { }
        @Override public void onDone(long messageId, String fullText) { }
        @Override public void onError(String message) { }
    }
}
