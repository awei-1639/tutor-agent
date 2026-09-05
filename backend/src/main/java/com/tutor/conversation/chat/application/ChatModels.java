package com.tutor.conversation.chat.application;

import com.tutor.conversation.memory.local.ConversationStore;
import com.tutor.expert.IntentRouter;
import com.tutor.expert.RoutingPolicy;
import com.tutor.conversation.memory.local.EpisodeStore;
import com.tutor.conversation.memory.local.FactStore;

import java.util.List;
import java.util.Map;

/** Stable application models shared by chat adapters and the turn use case. */
public final class ChatModels {
    private ChatModels() {
    }

    /** Reference to a memory that was actually included in the current prompt. */
    public record MemoryRef(String kind, long id, String text) {
    }

    /** Read-only context fixed at the beginning of a chat turn. */
    public record TurnContext(long userId, long convId, long memoryGeneration,
                              ConversationStore.ClarificationState clarificationState,
                              List<ConversationStore.Msg> recentWindow,
                              Map<String, Object> profile) {
    }

    /** Original routing decision paired with the effective, deterministic execution plan. */
    public record RoutedTurn(IntentRouter.RouteDecision decision, RoutingPolicy.ExecutionPlan plan) {
    }

    /** Evidence and cross-session memory produced by the retrieval stage. */
    public record RetrievedContext(List<com.tutor.contract.Evidence> evidences,
                                   List<EpisodeStore.Episode> episodes,
                                   List<FactStore.UserFact> facts) {
    }

    /** Immutable state passed between the rewrite, route, retrieval, and answer stages. */
    public record TurnState(String originalQuestion, String executionQuestion, TurnContext context,
                            RoutedTurn routed, RetrievedContext retrieved) {
        public TurnState withExecutionQuestion(String value) {
            return new TurnState(originalQuestion, value, context, routed, retrieved);
        }

        public TurnState withRouting(RoutedTurn value) {
            return new TurnState(originalQuestion, executionQuestion, context, value, retrieved);
        }

        public TurnState withRetrieved(RetrievedContext value) {
            return new TurnState(originalQuestion, executionQuestion, context, routed, value);
        }
    }
}
