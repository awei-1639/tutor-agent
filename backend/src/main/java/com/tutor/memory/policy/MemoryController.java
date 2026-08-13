package com.tutor.memory.policy;

import com.tutor.auth.AuthContext;
import com.tutor.memory.local.EpisodeStore;
import com.tutor.memory.application.LongTermMemoryService;
import com.tutor.memory.external.MemorySyncOutbox;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Local cross-session memory management; remote records lack stable per-item IDs today. */
@RestController
@RequestMapping("/memories")
public class MemoryController {
    private final EpisodeStore episodes;
    private final LongTermMemoryService memory;
    private final MemorySyncOutbox outbox;

    public MemoryController(EpisodeStore episodes, LongTermMemoryService memory, MemorySyncOutbox outbox) {
        this.episodes = episodes;
        this.memory = memory;
        this.outbox = outbox;
    }

    @GetMapping
    public List<EpisodeStore.ManagedEpisode> list(@RequestParam(defaultValue = "50") int limit) {
        return episodes.activeByUser(currentUserId(), Math.clamp(limit, 1, 100));
    }

    @DeleteMapping("/{id}")
    public void deleteOne(@PathVariable long id) {
        if (!episodes.deleteByIdForUser(id, currentUserId())) {
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

    @GetMapping("/remote-deletion")
    public RemoteDeletionStatus remoteDeletion() {
        MemorySyncOutbox.DeletionStatus status = outbox.latestDeletionStatus(currentUserId());
        String message = switch (status.status()) {
            case "completed" -> "云端记忆已删除";
            case "failed" -> "云端删除未完成，系统将保留失败状态供后续处理";
            case "pending", "processing", "retryable" -> "云端删除正在处理中";
            default -> "没有待处理的云端删除任务";
        };
        return new RemoteDeletionStatus(status.status(), status.attemptCount(), message);
    }

    private static long currentUserId() {
        Long userId = AuthContext.currentUserId();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        return userId;
    }
}
