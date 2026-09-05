package com.tutor.conversation.memory.policy;

import com.tutor.identity.auth.AuthContext;
import com.tutor.conversation.memory.local.EpisodeStore;
import com.tutor.conversation.memory.application.LongTermMemoryService;
import com.tutor.conversation.memory.external.MemorySyncOutbox;
import com.tutor.conversation.memory.local.ConversationStore;
import com.tutor.conversation.memory.local.FactStore;
import com.tutor.identity.profile.ProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 记忆范围管理。DELETE /memories 仅删除跨会话 Episode/Mem0；画像与会话删除是明确的独立操作。
 */
@RestController
@RequestMapping("/memories")
public class MemoryController {
    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);
    private final EpisodeStore episodes;
    private final LongTermMemoryService memory;
    private final MemorySyncOutbox outbox;
    private final ProfileService profiles;
    private final ConversationStore conversations;
    private final FactStore facts;
    private final MemoryDeletionRateLimiter deletionRateLimiter;

    public MemoryController(EpisodeStore episodes, LongTermMemoryService memory, MemorySyncOutbox outbox,
                            ProfileService profiles, ConversationStore conversations, FactStore facts,
                            MemoryDeletionRateLimiter deletionRateLimiter) {
        this.episodes = episodes;
        this.memory = memory;
        this.outbox = outbox;
        this.profiles = profiles;
        this.conversations = conversations;
        this.facts = facts;
        this.deletionRateLimiter = deletionRateLimiter;
    }

    @GetMapping
    public List<EpisodeStore.ManagedEpisode> list(@RequestParam(defaultValue = "50") int limit) {
        return episodes.activeByUser(currentUserId(), Math.clamp(limit, 1, 100));
    }

    /** 新会话开场主动提醒：最近未完成事项。仅读取，不改变任何记忆状态。 */
    @GetMapping("/open-items")
    public List<String> openItems(@RequestParam(defaultValue = "3") int limit) {
        return episodes.openItemsByUser(currentUserId(), Math.clamp(limit, 1, 10));
    }

    /** 用户长期事实清单；与 Episode 清单分列，删除语义各自独立。 */
    @GetMapping("/facts")
    public List<FactStore.UserFact> listFacts(@RequestParam(defaultValue = "100") int limit) {
        return facts.activeByUser(currentUserId(), Math.clamp(limit, 1, 200));
    }

    @DeleteMapping("/facts/{id}")
    public void deleteFact(@PathVariable long id) {
        if (!facts.deleteByIdForUser(id, currentUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "事实不存在");
        }
    }

    @DeleteMapping("/{id}")
    public void deleteOne(@PathVariable long id) {
        if (!memory.forgetOne(currentUserId(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆不存在");
        }
    }

    public record ClearResult(String localStatus, String remoteStatus) {}
    public record RemoteDeletionStatus(String status, int attemptCount, String message) {}

    @DeleteMapping
    public ClearResult clearAll() {
        LongTermMemoryService.ForgetResult result = memory.forget(currentUserId());
        return new ClearResult("completed", result.remoteDeletionPending() ? "pending" : "not_applicable");
    }

    @DeleteMapping("/profile")
    public void clearProfile() {
        profiles.deleteByUser(currentUserId());
    }

    @DeleteMapping("/conversations/{conversationId}")
    public void deleteConversation(@PathVariable long conversationId) {
        if (!conversations.deleteConversationForUser(conversationId, currentUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
    }

    @GetMapping("/remote-deletion")
    public RemoteDeletionStatus remoteDeletion() {
        MemorySyncOutbox.DeletionStatus status = outbox.latestDeletionStatus(currentUserId());
        return toRemoteDeletionStatus(status, null);
    }

    /** 查询当前用户某条记忆的远程删除状态，不泄露其他用户的任务。 */
    @GetMapping("/{id}/remote-deletion")
    public RemoteDeletionStatus remoteDeletionForMemory(@PathVariable long id) {
        if (id <= 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "删除任务不存在");
        MemorySyncOutbox.DeletionStatus status = outbox.latestDeletionStatus(currentUserId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "删除任务不存在"));
        return toRemoteDeletionStatus(status, null);
    }

    /** 允许用户在自动重试耗尽后重新触发当前代次的云端同步。 */
    @PostMapping("/remote-deletion/retry")
    public RemoteDeletionStatus retryRemoteDeletion() {
        long userId = currentUserId();
        if (!deletionRateLimiter.tryAcquire(userId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "删除重试过于频繁");
        }
        int requeued = outbox.requeueFailedForUser(userId);
        log.info("远程记忆删除重试 user={} requeued={}", userId, requeued);
        return toRemoteDeletionStatus(outbox.latestDeletionStatus(userId), requeued);
    }

    private static RemoteDeletionStatus toRemoteDeletionStatus(MemorySyncOutbox.DeletionStatus status,
                                                                Integer requeued) {
        String message;
        if (requeued != null && requeued > 0) {
            message = "失败的云端同步任务已重新排队";
        } else {
            message = switch (status.status()) {
                case "completed" -> "云端记忆已删除";
                case "failed" -> "云端删除未完成，可稍后再次重试";
                case "pending", "processing", "retryable" -> "云端删除正在处理中";
                default -> "没有待处理的云端删除任务";
            };
        }
        return new RemoteDeletionStatus(status.status(), status.attemptCount(), message);
    }

    private static long currentUserId() {
        Long userId = AuthContext.currentUserId();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        return userId;
    }
}
