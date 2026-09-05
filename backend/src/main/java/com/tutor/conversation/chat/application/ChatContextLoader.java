package com.tutor.conversation.chat.application;

import com.tutor.identity.auth.AuthContext;
import com.tutor.conversation.chat.application.ChatModels.TurnContext;
import com.tutor.platform.llm.BudgetExhausted;
import com.tutor.platform.llm.LlmBudgetGuard;
import com.tutor.conversation.memory.local.ConversationStore;
import com.tutor.conversation.memory.policy.MemoryConsentService;
import com.tutor.identity.profile.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Loads the stable per-turn snapshot before routing; it does not own answer generation or finalization. */
final class ChatContextLoader {
    private static final Logger log = LoggerFactory.getLogger(ChatContextLoader.class);
    private static final int HISTORY_TURNS = 6;

    private final ConversationStore conversations;
    private final ProfileService profiles;
    private final MemoryConsentService memoryConsent;
    private volatile LlmBudgetGuard budgetGuard;

    ChatContextLoader(ConversationStore conversations, ProfileService profiles,
                      MemoryConsentService memoryConsent) {
        this.conversations = conversations;
        this.profiles = profiles;
        this.memoryConsent = memoryConsent;
    }

    void setBudgetGuard(LlmBudgetGuard budgetGuard) {
        this.budgetGuard = budgetGuard;
    }

    TurnContext load(Long conversationId, String question, String traceId,
                     ChatTurnEvents events, ChatTurnService.Claim claim) {
        long userId = claim == null ? AuthContext.requireUserId() : claim.userId();
        Integer quotaRemainingPercent = attributeAndCheckQuota(traceId, userId);
        long memoryGeneration = memoryConsent.currentGeneration(userId);
        long convId = claim == null
                ? conversations.ensureConversation(conversationId, userId)
                : claim.conversationId();
        events.onMeta(convId, traceId, quotaRemainingPercent);

        ConversationStore.ClarificationState clarificationState = conversations.clarificationState(convId);
        if (clarificationState == null) {
            clarificationState = new ConversationStore.ClarificationState(false, null, null);
        }
        List<ConversationStore.Msg> recentWindow =
                conversations.recentMessages(convId, HISTORY_TURNS * 4);
        if (claim == null) {
            conversations.appendMessage(convId, "user", question, null, null, traceId, question.length() / 2);
        } else if (!recentWindow.isEmpty()) {
            // Durable admission already wrote the user message. Remove it so recovery and first execution
            // receive the same prompt history.
            ConversationStore.Msg latest = recentWindow.getLast();
            if ("user".equals(latest.role) && question.equals(latest.content)) {
                recentWindow = new ArrayList<>(recentWindow.subList(0, recentWindow.size() - 1));
            }
        }
        if (clarificationState.pending()) {
            conversations.clearClarification(convId);
        }
        return new TurnContext(userId, convId, memoryGeneration, clarificationState, recentWindow,
                profiles.snapshot(userId));
    }

    /** Attribute before any conversation side effect; database/provider errors only lose user quota display. */
    private Integer attributeAndCheckQuota(String traceId, long userId) {
        if (budgetGuard == null) return null;
        try {
            budgetGuard.attributeTrace(traceId, userId);
            return budgetGuard.requireUserDailyAllowance(userId);
        } catch (BudgetExhausted error) {
            throw error;
        } catch (RuntimeException error) {
            log.warn("budget attribution failed trace={} type={}", traceId,
                    error.getClass().getSimpleName());
            return null;
        }
    }
}
