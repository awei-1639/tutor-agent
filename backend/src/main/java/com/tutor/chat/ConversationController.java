package com.tutor.chat;

import com.tutor.auth.AuthContext;
import com.tutor.memory.ConversationStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 会话历史端点 (Phase 3 补充): 前端刷新后恢复对话。
 * - GET /conversations: 用户会话列表 (按 last_active_at desc)
 * - GET /conversations/{id}/messages: 会话内消息历史
 */
@RestController
@RequestMapping("/conversations")
public class ConversationController {
    private final ConversationStore store;

    public ConversationController(ConversationStore store) {
        this.store = store;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return store.listConversations(currentUserId());
    }

    @GetMapping("/{id}/messages")
    public List<ConversationStore.Msg> messages(@PathVariable long id, @RequestParam(defaultValue = "200") int limit) {
        long userId = currentUserId();
        if (!store.belongsToUser(id, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        return store.recentMessagesForUser(id, userId, Math.clamp(limit, 1, 200));
    }

    private long currentUserId() {
        Long userId = AuthContext.currentUserId();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        return userId;
    }
}
