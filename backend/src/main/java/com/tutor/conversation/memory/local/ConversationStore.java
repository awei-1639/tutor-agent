package com.tutor.conversation.memory.local;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Stable conversation persistence facade; SQL is split into message and state stores. */
@Component
public class ConversationStore {
    private final ConversationMessageStore messages;
    private final ConversationStateStore state;

    public ConversationStore(JdbcTemplate jdbc) {
        this(jdbc, "");
    }

    @Autowired
    public ConversationStore(JdbcTemplate jdbc, @Value("${security.resume-enc-key:}") String encKey) {
        this.messages = new ConversationMessageStore(jdbc, encKey);
        this.state = new ConversationStateStore(jdbc, encKey);
    }

    public static class Msg {
        public final long id;
        public final String role;
        public final String content;
        public String citations;
        public String citationStatus;
        public String citationIssues;
        public String traceId;
        public String feedback;

        public Msg(String role, String content) {
            this(0, role, content);
        }

        public Msg(long id, String role, String content) {
            this.id = id;
            this.role = role;
            this.content = content;
        }
    }

    public long ensureConversation(Long conversationId, long userId) {
        return messages.ensureConversation(conversationId, userId);
    }

    public void updateCitationVerification(long messageId, String status, String issuesJson) {
        messages.updateCitationVerification(messageId, status, issuesJson);
    }

    public long appendMessage(long conversationId, String role, String content,
                              String intent, String citationsJson, String traceId, int tokenCount) {
        return messages.appendMessage(conversationId, role, content, intent, citationsJson, traceId, tokenCount);
    }

    public long appendMessage(long conversationId, String role, String content,
                              String intent, String citationsJson, int tokenCount) {
        return messages.appendMessage(conversationId, role, content, intent, citationsJson, tokenCount);
    }

    public long appendMessage(long conversationId, String role, String content,
                              String intent, String citationsJson, String traceId, int tokenCount,
                              String citationStatus, String citationIssuesJson) {
        return messages.appendMessage(conversationId, role, content, intent, citationsJson, traceId,
                tokenCount, citationStatus, citationIssuesJson);
    }

    public long appendMessage(long conversationId, String role, String content,
                              String intent, String citationsJson, String traceId, int tokenCount,
                              String citationStatus, String citationIssuesJson, String chatTurnId) {
        return messages.appendMessage(conversationId, role, content, intent, citationsJson, traceId,
                tokenCount, citationStatus, citationIssuesJson, chatTurnId);
    }

    public List<Msg> recentMessages(long conversationId, int limit) {
        return messages.recentMessages(conversationId, limit);
    }

    public List<Msg> recentMessagesForUser(long conversationId, long userId, int limit) {
        return messages.recentMessagesForUser(conversationId, userId, limit);
    }

    public boolean belongsToUser(long conversationId, long userId) {
        return messages.belongsToUser(conversationId, userId);
    }

    public boolean memoryGenerationCurrent(long conversationId, long userId, long generation) {
        return messages.memoryGenerationCurrent(conversationId, userId, generation);
    }

    public boolean deleteConversationForUser(long conversationId, long userId) {
        return messages.deleteConversationForUser(conversationId, userId);
    }

    public void deleteAllForUser(long userId) {
        messages.deleteAllForUser(userId);
    }

    public List<java.util.Map<String, Object>> listConversations(long userId) {
        return messages.listConversations(userId);
    }

    public record SummaryState(String summary, long uptoMsgId) {
    }

    public record ClarificationState(boolean pending, String intent, Instant expiresAt) {
        static ClarificationState none() {
            return new ClarificationState(false, null, null);
        }
    }

    public ClarificationState clarificationState(long conversationId) {
        return state.clarificationState(conversationId);
    }

    public void setClarificationPending(long conversationId, String intent, Instant expiresAt) {
        state.setClarificationPending(conversationId, intent, expiresAt);
    }

    public void clearClarification(long conversationId) {
        state.clearClarification(conversationId);
    }

    public SummaryState summaryState(long conversationId) {
        return state.summaryState(conversationId);
    }

    public long episodeUptoMsgId(long conversationId) {
        return state.episodeUptoMsgId(conversationId);
    }

    public List<Msg> messagesAfter(long conversationId, long messageId, int limit) {
        return state.messagesAfter(conversationId, messageId, limit);
    }

    public void advanceEpisodeWatermark(long conversationId, long messageId) {
        state.advanceEpisodeWatermark(conversationId, messageId);
    }

    public List<Msg> messagesToFold(long conversationId, long uptoMsgId, int keepRecent) {
        return state.messagesToFold(conversationId, uptoMsgId, keepRecent);
    }

    public long maxFoldableMsgId(long conversationId, int keepRecent) {
        return state.maxFoldableMsgId(conversationId, keepRecent);
    }

    public void saveSummary(long conversationId, String summary, long uptoMsgId) {
        state.saveSummary(conversationId, summary, uptoMsgId);
    }

    public boolean saveSummaryIfGeneration(long conversationId, long userId, long generation,
                                           String summary, long uptoMsgId) {
        return state.saveSummaryIfGeneration(conversationId, userId, generation, summary, uptoMsgId);
    }
}
