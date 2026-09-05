package com.tutor.chat.application;

import com.tutor.chat.application.ChatModels.TurnContext;
import com.tutor.chat.application.ChatTurnService.Claim;
import com.tutor.contract.CancellationToken;
import com.tutor.contract.Evidence;
import com.tutor.memory.local.ConversationStore;
import com.tutor.config.ExecutorLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the terminal side effects of a chat answer: citation mapping, durable message commit,
 * client completion, and non-critical post-turn work.
 */
final class ChatCompletionFinalizer {
    private static final Logger log = LoggerFactory.getLogger(ChatCompletionFinalizer.class);
    private static final java.time.Duration CLARIFICATION_TTL = java.time.Duration.ofMinutes(10);

    private final ConversationStore conversations;
    private final CitationVerificationService citationVerification;
    private final PostTurnTaskService postTurnTasks;
    private final ChatTurnService chatTurns;
    private final TurnCitations citations = new TurnCitations();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    ChatCompletionFinalizer(ConversationStore conversations,
                            CitationVerificationService citationVerification,
                            PostTurnTaskService postTurnTasks,
                            ChatTurnService chatTurns) {
        this.conversations = conversations;
        this.citationVerification = citationVerification;
        this.postTurnTasks = postTurnTasks;
        this.chatTurns = chatTurns;
    }

    void completeAnswer(String text, String intent, TurnContext context, String question,
                        List<Evidence> evidences, Set<String> citationIds, String traceId,
                        ChatTurnEvents events, CancellationToken cancellation,
                        Claim claim, boolean truncated) {
        CitationBundle bundle = citationsFor(text, evidences, citationIds);
        Long messageId = persistAssistant(context.convId(), text, intent, bundle.json(), traceId,
                text.length() / 2, bundle.status(), bundle.issuesJson(), claim, cancellation);
        if (messageId == null) return;

        events.onDone(messageId, text, bundle.status(), citations.parseIssues(bundle.issuesJson()), truncated);
        background.submit(() -> postTurnTasks.run(context.convId(), context.userId(), question, text,
                traceId, context.memoryGeneration()));
        background.submit(() -> citationVerification.verify(messageId, text,
                citations.forVerification(evidences, citationIds), traceId));
    }

    void completeClarification(TurnContext context, String question, String clarification,
                               List<java.util.Map<String, String>> options, String clarificationKind,
                               String traceId, ChatTurnEvents events, CancellationToken cancellation,
                               Claim claim) {
        events.onStage("clarifying");
        events.onClarify(clarification, options);
        Long messageId = persistAssistant(context.convId(), clarification, "clarify", null, traceId,
                clarification.length() / 2, "unavailable", "[]", claim, cancellation);
        if (messageId == null) return;

        conversations.setClarificationPending(context.convId(), clarificationKind,
                Instant.now().plus(CLARIFICATION_TTL));
        events.onDone(messageId, clarification, "unavailable", List.of());
        background.submit(() -> postTurnTasks.run(context.convId(), context.userId(), question,
                clarification, traceId, context.memoryGeneration()));
    }

    private Long persistAssistant(long convId, String content, String intent, String citationsJson,
                                  String traceId, int tokenCount, String citationStatus, String issuesJson,
                                  Claim claim, CancellationToken cancellation) {
        if (claim != null && chatTurns != null) {
            return chatTurns.completeWithMessage(claim, content, intent, citationsJson, tokenCount,
                    citationStatus, issuesJson).stream().boxed().findFirst().orElse(null);
        }
        if (cancellation.isCancelled()) return null;
        return conversations.appendMessage(convId, "assistant", content, intent, citationsJson, traceId,
                tokenCount, citationStatus, issuesJson);
    }

    private CitationBundle citationsFor(String text, List<Evidence> evidences,
                                        Set<String> availableCitationIds) {
        TurnCitations.Bundle bundle = citations.bundleFor(text, evidences, availableCitationIds);
        return new CitationBundle(bundle.json(), bundle.status(), bundle.issuesJson());
    }

    void shutdown() {
        ExecutorLifecycle.shutdown(background, "chat-background", log);
    }

    private record CitationBundle(String json, String status, String issuesJson) {
    }
}
