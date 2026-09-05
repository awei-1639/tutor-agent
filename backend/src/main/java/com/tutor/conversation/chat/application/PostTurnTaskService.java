package com.tutor.conversation.chat.application;

import com.tutor.conversation.memory.application.LongTermMemoryService;
import com.tutor.conversation.memory.local.EpisodeSummarizer;
import com.tutor.conversation.memory.local.SummaryFolder;
import com.tutor.conversation.memory.policy.MemoryConsentService;
import com.tutor.identity.profile.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 在回答持久化后执行画像、摘要和长期记忆等非关键路径任务。 */
@Component
final class PostTurnTaskService {
    private static final Logger log = LoggerFactory.getLogger(PostTurnTaskService.class);
    private final ProfileService profiles;
    private final SummaryFolder summaries;
    private final EpisodeSummarizer episodes;
    private final LongTermMemoryService memories;
    private final MemoryConsentService consent;

    PostTurnTaskService(ProfileService profiles, SummaryFolder summaries, EpisodeSummarizer episodes,
                        LongTermMemoryService memories, MemoryConsentService consent) {
        this.profiles = profiles;
        this.summaries = summaries;
        this.episodes = episodes;
        this.memories = memories;
        this.consent = consent;
    }

    void run(long conversationId, long userId, String question, String answer, String traceId, long generation) {
        runSafely("profile", () -> profiles.updateFromMessage(userId, question, traceId, generation), traceId);
        runSafely("summary", () -> summaries.maybeFold(conversationId, userId, generation, traceId), traceId);
        runSafely("episode", () -> episodes.maybeSummarize(conversationId, userId, traceId, generation), traceId);
        runSafely("memory", () -> memories.remember(userId, question, answer, traceId), traceId);
    }

    private void runSafely(String task, Runnable action, String traceId) {
        try { action.run(); }
        catch (RuntimeException error) { log.warn("后置任务失败 task={} trace={}", task, traceId, error); }
    }
}
