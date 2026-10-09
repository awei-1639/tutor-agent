package com.tutor.conversation.chat.application;

import com.tutor.contract.CancellationToken;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 静默死亡兜底: 回合结束前端必须收到终态 (error/done/clarify 之一), 不允许挂死。 */
class ChatTurnWorkerTest {

    private final ChatTurnJobStore jobs = mock(ChatTurnJobStore.class);
    private final ChatService chatService = mock(ChatService.class);

    @SuppressWarnings("unchecked")
    private ChatTurnWorker workerWithDirectExecutor() {
        ObjectProvider<ChatService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(chatService);
        ExecutorService direct = new java.util.concurrent.AbstractExecutorService() {
            @Override public void execute(Runnable command) { command.run(); }
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
        };
        return new ChatTurnWorker(jobs, provider, direct, new Semaphore(4));
    }

    private ChatTurnService.Claim claim() {
        return new ChatTurnService.Claim("t1", 7L, 100L, "问题", "trace-1", 1, UUID.randomUUID());
    }

    private ChatTurnService.Turn turn() {
        return new ChatTurnService.Turn("t1", 100L, "req-1", "问题", "trace-1",
                "running", 1, null, null, Instant.now(), null);
    }

    private record Errors(List<String> messages) implements ChatTurnEvents {
        static Errors recording() { return new Errors(new ArrayList<>()); }

        @Override public void onMeta(long conversationId, String traceId) {}
        @Override public void onStage(String phase) {}
        @Override public void onCitations(List<com.tutor.contract.Evidence> evidences) {}
        @Override public void onToken(String token) {}
        @Override public void onClarify(String question) {}
        @Override public void onDone(long messageId, String fullText) {}
        @Override public void onError(String message) { messages.add(message); }
    }

    @Test
    void silentDeathWithoutDonePushesErrorToTheClient() {
        var claim = claim();
        when(jobs.claimById("t1")).thenReturn(Optional.of(claim));
        when(jobs.owns(any())).thenReturn(false);
        // turn 正常返回但没发任何终态事件 —— 复现上游流静默死亡
        var worker = workerWithDirectExecutor();
        var events = Errors.recording();

        worker.start(turn(), events, new CancellationToken());

        assertThat(events.messages()).containsExactly("回答生成中断，请重试");
    }

    @Test
    void emptyBubbleWithDoneDoesNotPushDuplicateError() {
        // 已发 done 的空泡: 客户端已收到终态, 补发会对完成的 emitter 二次 send
        var claim = claim();
        when(jobs.claimById("t1")).thenReturn(Optional.of(claim));
        when(jobs.owns(any())).thenReturn(false);
        org.mockito.Mockito.doAnswer(inv -> {
            ChatTurnEvents ev = inv.getArgument(2);
            ev.onDone(42L, "");
            return null;
        }).when(chatService).turn(anyLong(), anyString(), any(ChatTurnEvents.class),
                any(CancellationToken.class), any(ChatTurnService.Claim.class));
        var worker = workerWithDirectExecutor();
        var events = Errors.recording();

        worker.start(turn(), events, new CancellationToken());

        assertThat(events.messages()).isEmpty();
    }

    @Test
    void escapedExceptionWithoutPriorErrorPushesErrorToTheClient() {
        var claim = claim();
        when(jobs.claimById("t1")).thenReturn(Optional.of(claim));
        when(jobs.owns(any())).thenReturn(false);
        Mockito.doThrow(new IllegalStateException("llm blew up"))
                .when(chatService).turn(anyLong(), anyString(), any(ChatTurnEvents.class),
                        any(CancellationToken.class), any(ChatTurnService.Claim.class));
        var worker = workerWithDirectExecutor();
        var events = Errors.recording();

        worker.start(turn(), events, new CancellationToken());

        assertThat(events.messages()).containsExactly("回答生成中断，请重试");
    }
}
