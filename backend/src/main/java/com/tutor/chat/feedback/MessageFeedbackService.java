package com.tutor.chat.feedback;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Application facade for message feedback and internal quality summaries. */
@Service
public class MessageFeedbackService {
    private final MessageFeedbackStore store;

    @Autowired
    public MessageFeedbackService(MessageFeedbackStore store) { this.store = store; }

    public record Feedback(long id, long messageId, String rating, String reason, String traceId) {}
    public record ReasonCount(String reason, long count) {}
    public record TraceFeedback(String traceId, long messageId, String reason, java.time.Instant createdAt) {}
    public record Attribution(String retrievalFacets, String requestedMode, int hops, String retrievalProfileVersion,
                              long finalGraphEvidenceCount, long finalDirectEvidenceCount, long denseCandidateCount,
                              long sparseCandidateCount, long graphCandidateCount, long graphExpansionSourceCount,
                              boolean embeddingDegraded, boolean sparseDegraded, boolean rerankApplied,
                              boolean rerankDegraded, String citationStatus, String reason, long count) {}
    public record Summary(long total, long helpful, long notHelpful, java.util.List<ReasonCount> reasons,
                          java.util.List<TraceFeedback> latestNotHelpful, java.util.List<Attribution> attributions) {}

    public Feedback save(long userId, long messageId, String rating, String reason) {
        return store.save(userId, messageId, rating, reason);
    }

    public Summary summary() {
        long[] totals = store.totals();
        return new Summary(totals[0], totals[1], totals[2], store.reasons(),
                store.latestNotHelpful(), store.attributions());
    }
}
