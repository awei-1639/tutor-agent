package com.tutor.chat;

import com.tutor.memory.ConversationStore;
import org.springframework.web.bind.annotation.*;

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
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "1") long userId) {
        return store.listConversations(userId);
    }

    @GetMapping("/{id}/messages")
    public List<ConversationStore.Msg> messages(@PathVariable long id, @RequestParam(defaultValue = "200") int limit) {
        return store.recentMessages(id, limit);
    }
}