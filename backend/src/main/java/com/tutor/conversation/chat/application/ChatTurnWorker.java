package com.tutor.conversation.chat.application;

import com.tutor.identity.auth.AuthContext;
import com.tutor.contract.CancellationToken;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes admitted chat turns and owns their recovery, cancellation, and lease renewal. */
@Component
final class ChatTurnWorker {
    private final ChatTurnJobStore jobs;
    private final ObjectProvider<ChatService> chatService;
    private final ExecutorService executor;
    private final Semaphore slots;
    private final ConcurrentHashMap<String, ActiveRun> active = new ConcurrentHashMap<>();

    private record ActiveRun(ChatTurnService.Claim claim, CancellationToken cancellation) {}

    @Autowired
    ChatTurnWorker(ChatTurnJobStore jobs, ObjectProvider<ChatService> chatService) {
        this(jobs, chatService, Executors.newVirtualThreadPerTaskExecutor(), new Semaphore(4));
    }

    ChatTurnWorker(ChatTurnJobStore jobs, ObjectProvider<ChatService> chatService,
                   ExecutorService executor, Semaphore slots) {
        this.jobs = jobs;
        this.chatService = chatService;
        this.executor = executor;
        this.slots = slots;
    }

    void start(ChatTurnService.Turn turn, ChatTurnEvents events, CancellationToken cancellation) {
        var claim = jobs.claimById(turn.id());
        if (claim.isEmpty()) return;
        run(claim.get(), events, cancellation, slots.tryAcquire());
    }

    @Scheduled(fixedDelayString = "${tutor.chat.turn.poll-ms:1000}")
    void recoverAndDispatch() {
        jobs.expireExhaustedLeases();
        if (!slots.tryAcquire()) return;
        var claim = jobs.claimNext();
        if (claim.isEmpty()) {
            slots.release();
            return;
        }
        run(claim.get(), new NoopEvents(), new CancellationToken(), true);
    }

    @Scheduled(fixedDelayString = "${tutor.chat.turn.lease-renew-ms:30000}")
    void renewLeases() {
        active.forEach((id, run) -> jobs.renew(run.claim()));
    }

    void cancel(String id) {
        ActiveRun run = active.remove(id);
        if (run != null) run.cancellation().cancel();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void run(ChatTurnService.Claim claim, ChatTurnEvents events,
                     CancellationToken cancellation, boolean ownsSlot) {
        ActiveRun run = new ActiveRun(claim, cancellation);
        active.put(claim.id(), run);
        executor.execute(() -> {
            AuthContext.set(claim.userId());
            AtomicBoolean turnFailed = new AtomicBoolean();
            ChatTurnEvents executionEvents = forwardingEvents(events, turnFailed);
            try {
                chatService.getObject().turn(claim.conversationId(), claim.question(), executionEvents,
                        cancellation, claim);
                if (jobs.owns(claim)) {
                    if (turnFailed.get()) jobs.fail(claim, "聊天回合执行失败");
                    else if (cancellation.isCancelled()) jobs.cancelClaim(claim);
                    else jobs.fail(claim, "聊天回合未产生终态");
                }
            } catch (Exception error) {
                jobs.fail(claim, safeError(error));
            } finally {
                AuthContext.clear();
                active.remove(claim.id(), run);
                if (ownsSlot) slots.release();
            }
        });
    }

    private String safeError(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private ChatTurnEvents forwardingEvents(ChatTurnEvents target, AtomicBoolean failed) {
        return new ChatTurnEvents() {
            @Override public void onMeta(long conversationId, String traceId) { target.onMeta(conversationId, traceId); }
            @Override public void onStage(String phase) { target.onStage(phase); }
            @Override public void onExpertDone(String expert, String status, String detail) {
                target.onExpertDone(expert, status, detail);
            }
            @Override public void onCitations(List<com.tutor.contract.Evidence> evidences) { target.onCitations(evidences); }
            @Override public void onToken(String token) { target.onToken(token); }
            @Override public void onClarify(String question) { target.onClarify(question); }
            @Override public void onClarify(String question, List<Map<String, String>> options) {
                target.onClarify(question, options);
            }
            @Override public void onDone(long messageId, String fullText) { target.onDone(messageId, fullText); }
            @Override public void onDone(long messageId, String fullText, String status, List<String> issues) {
                target.onDone(messageId, fullText, status, issues);
            }
            @Override public void onError(String message) {
                failed.set(true);
                target.onError(message);
            }
        };
    }

    private static final class NoopEvents implements ChatTurnEvents {
        @Override public void onMeta(long conversationId, String traceId) {}
        @Override public void onStage(String phase) {}
        @Override public void onCitations(List<com.tutor.contract.Evidence> evidences) {}
        @Override public void onToken(String token) {}
        @Override public void onClarify(String question) {}
        @Override public void onDone(long messageId, String fullText) {}
        @Override public void onError(String message) {}
    }
}
