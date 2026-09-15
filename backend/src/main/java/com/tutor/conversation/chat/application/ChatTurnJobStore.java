package com.tutor.conversation.chat.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** SQL boundary for durable chat-turn admission, leases, and terminal states. */
@Repository
class ChatTurnJobStore {
    private static final String ACTIVE = "status IN ('ACCEPTED', 'RUNNING')";
    private static final long LEASE_SECONDS = 120;
    private static final int MAX_ATTEMPTS = 3;
    private static final com.tutor.platform.jobs.LeasedJobQueue.LeaseTable TABLE =
            com.tutor.platform.jobs.LeasedJobQueue.LeaseTable.of(
                    "chat_turns", "RUNNING", "COMPLETED", "id=?::uuid");
    private final JdbcTemplate jdbc;
    private final com.tutor.platform.jobs.LeasedJobQueue queue;

    ChatTurnJobStore(JdbcTemplate jdbc, com.tutor.platform.jobs.LeasedJobQueue queue) {
        this.jdbc = jdbc;
        this.queue = queue;
    }

    int activeCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM chat_turns WHERE status IN ('ACCEPTED', 'RUNNING')",
                Integer.class);
        return count == null ? 0 : count;
    }

    void ensureUser(long userId) {
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, userId);
    }

    Optional<ChatTurnService.Turn> findByRequest(long userId, String requestId) {
        return jdbc.query("""
                SELECT id, conversation_id, request_id, question, trace_id, status, attempts,
                       answer_message_id, last_error, created_at, finished_at
                FROM chat_turns WHERE user_id=? AND request_id=?
                """, (rs, i) -> mapTurn(rs), userId, requestId).stream().findFirst();
    }

    int insert(String id, long userId, long conversationId, String requestId,
               String question, String traceId) {
        return jdbc.update("""
                INSERT INTO chat_turns (id, user_id, conversation_id, request_id, question, trace_id)
                VALUES (?::uuid, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, id, userId, conversationId, requestId, question, traceId);
    }

    Optional<ChatTurnService.Turn> find(long userId, String id) {
        return jdbc.query("""
                SELECT id, conversation_id, request_id, question, trace_id, status, attempts,
                       answer_message_id, last_error, created_at, finished_at
                FROM chat_turns WHERE id=?::uuid AND user_id=?
                """, (rs, i) -> mapTurn(rs), id, userId).stream().findFirst();
    }

    int cancel(long userId, String id) {
        return jdbc.update("""
                UPDATE chat_turns
                SET status='CANCELLED', cancel_requested_at=now(), lease_token=NULL, lease_until=NULL,
                    finished_at=now(), updated_at=now()
                WHERE id=?::uuid AND user_id=? AND %s
                """.formatted(ACTIVE), id, userId);
    }

    Optional<ChatTurnService.Claim> claimById(String id) {
        return jdbc.query("""
                UPDATE chat_turns
                SET status='RUNNING', attempts=attempts+1, started_at=COALESCE(started_at, now()),
                    lease_token=?::uuid, lease_until=now() + (? * interval '1 second'), updated_at=now()
                WHERE id=?::uuid AND status='ACCEPTED'
                RETURNING id, user_id, conversation_id, question, trace_id, attempts, lease_token
                """, (rs, i) -> mapClaim(rs), UUID.randomUUID(), LEASE_SECONDS, id)
                .stream().findFirst();
    }

    Optional<ChatTurnService.Claim> claimNext() {
        return queue.claimNext(TABLE,
                "(status='ACCEPTED')"
                        + " OR (status='RUNNING' AND lease_until < now() AND attempts < " + MAX_ATTEMPTS + ")",
                "status='RUNNING', attempts=j.attempts+1, started_at=COALESCE(j.started_at, now()),"
                        + " lease_token=?, lease_until=now() + (? * interval '1 second'), updated_at=now()",
                "created_at",
                "j.id, j.user_id, j.conversation_id, j.question, j.trace_id, j.attempts, j.lease_token",
                (rs, i) -> mapClaim(rs), UUID.randomUUID(), LEASE_SECONDS);
    }

    boolean owns(ChatTurnService.Claim claim) {
        return queue.owns(TABLE, claim.id(), claim.leaseToken());
    }

    boolean markCompleted(ChatTurnService.Claim claim) {
        return queue.complete(TABLE, claim.id(), claim.leaseToken(),
                ", finished_at=now(), updated_at=now()");
    }

    void setAnswerMessageId(ChatTurnService.Claim claim, long messageId) {
        jdbc.update("UPDATE chat_turns SET answer_message_id=? WHERE id=?::uuid AND status='COMPLETED'",
                messageId, claim.id());
    }

    void renew(ChatTurnService.Claim claim) {
        queue.renew(TABLE, claim.id(), claim.leaseToken(), LEASE_SECONDS);
    }

    void fail(ChatTurnService.Claim claim, String error) {
        queue.fail(TABLE, claim.id(), claim.leaseToken(), "FAILED",
                ", last_error=?, finished_at=now(), updated_at=now()", error);
    }

    void cancelClaim(ChatTurnService.Claim claim) {
        queue.fencedUpdate(TABLE, claim.id(), claim.leaseToken(), """
                status='CANCELLED', cancel_requested_at=COALESCE(cancel_requested_at, now()),
                    lease_token=NULL, lease_until=NULL, finished_at=now(), updated_at=now()""");
    }

    void expireExhaustedLeases() {
        queue.expireExhausted(TABLE,
                "status='FAILED', last_error=?, lease_token=NULL, lease_until=NULL, finished_at=now(), updated_at=now()",
                new Object[]{"任务租约已耗尽，未能恢复"}, "attempts >= ?", MAX_ATTEMPTS);
    }

    private ChatTurnService.Claim mapClaim(ResultSet rs) throws SQLException {
        return new ChatTurnService.Claim(rs.getString(1), rs.getLong(2), rs.getLong(3),
                rs.getString(4), rs.getString(5), rs.getInt(6), rs.getObject(7, UUID.class));
    }

    private ChatTurnService.Turn mapTurn(ResultSet rs) throws SQLException {
        return new ChatTurnService.Turn(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7), (Long) rs.getObject(8), rs.getString(9),
                instant(rs, 10), nullableInstant(rs, 11));
    }

    private static Instant instant(ResultSet rs, int index) throws SQLException {
        return rs.getTimestamp(index).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, int index) throws SQLException {
        return rs.getTimestamp(index) == null ? null : rs.getTimestamp(index).toInstant();
    }
}
