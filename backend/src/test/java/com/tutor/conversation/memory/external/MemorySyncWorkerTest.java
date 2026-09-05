package com.tutor.conversation.memory.external;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MemorySyncWorkerTest {
    @Test
    void deletesRemoteMemoryAndCompletesClaimedJob() {
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        when(mem0.enabled()).thenReturn(true);
        when(breaker.allowRequest()).thenReturn(true);
        when(outbox.isGenerationCurrent(7L, 2L)).thenReturn(true);
        var job = new MemorySyncOutbox.Job(3L, 7L, 2L, "delete_user", 1);
        when(outbox.claimNext()).thenReturn(Optional.of(job));

        new MemorySyncWorker(outbox, mem0, breaker).processOne();

        verify(mem0).deleteAllForUser(7L);
        verify(outbox).complete(3L);
        verify(breaker).success();
    }

    @Test
    void makesRemoteFailureRetryableWithoutEscapingScheduler() {
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        when(mem0.enabled()).thenReturn(true);
        when(breaker.allowRequest()).thenReturn(true);
        when(outbox.isGenerationCurrent(7L, 2L)).thenReturn(true);
        var job = new MemorySyncOutbox.Job(3L, 7L, 2L, "delete_user", 2);
        when(outbox.claimNext()).thenReturn(Optional.of(job));
        doThrow(new IllegalStateException("timeout")).when(mem0).deleteAllForUser(7L);

        new MemorySyncWorker(outbox, mem0, breaker).processOne();

        verify(outbox).fail(3L, 2, "timeout");
        verify(breaker).failure();
    }

    @Test
    void upsertsOnlyCurrentConsentedAdmittedMemory() {
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        when(mem0.enabled()).thenReturn(true);
        when(breaker.allowRequest()).thenReturn(true);
        when(outbox.isUpsertAllowed(7L, 2L, 11L)).thenReturn(true);
        var job = new MemorySyncOutbox.Job(4L, 7L, 2L, "upsert_memory", 11L,
                "用户正在准备 Java 面试", List.of("Java"), List.of("完成项目"), 1);
        when(outbox.claimNext()).thenReturn(Optional.of(job));

        new MemorySyncWorker(outbox, mem0, breaker).processOne();

        verify(mem0).addAdmittedMemory(7L, 11L, 2L, "用户正在准备 Java 面试", List.of("Java"),
                List.of("完成项目"), "memory-sync-4");
        verify(outbox).complete(4L);
        verify(breaker).success();
    }

    @Test
    void skipsStaleUpsertAfterConsentGenerationChanges() {
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        when(mem0.enabled()).thenReturn(true);
        when(breaker.allowRequest()).thenReturn(true);
        when(outbox.isUpsertAllowed(7L, 2L, 12L)).thenReturn(false);
        var job = new MemorySyncOutbox.Job(5L, 7L, 2L, "upsert_memory", 12L,
                "旧记忆", List.of(), List.of(), 1);
        when(outbox.claimNext()).thenReturn(Optional.of(job));

        new MemorySyncWorker(outbox, mem0, breaker).processOne();

        verify(mem0, never()).addAdmittedMemory(anyLong(), anyLong(), anyLong(), anyString(),
                anyList(), anyList(), anyString());
        verify(outbox).complete(5L);
        verify(breaker).allowRequest();
        verify(breaker, never()).success();
        verify(breaker, never()).failure();
    }

    @Test
    void completesClaimWithLeaseToken() {
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        UUID token = UUID.randomUUID();
        when(mem0.enabled()).thenReturn(true);
        when(breaker.allowRequest()).thenReturn(true);
        when(outbox.isGenerationCurrent(7L, 2L)).thenReturn(true);
        var job = new MemorySyncOutbox.Job(6L, 7L, 2L, "delete_user", null, null,
                List.of(), List.of(), 1, token);
        when(outbox.claimNext()).thenReturn(Optional.of(job));

        new MemorySyncWorker(outbox, mem0, breaker).processOne();

        verify(outbox).complete(6L, token);
        verify(outbox, never()).complete(6L);
    }

    @Test
    void deletesSingleRemoteMemoryThroughOutbox() {
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        UUID token = UUID.randomUUID();
        when(mem0.enabled()).thenReturn(true);
        when(breaker.allowRequest()).thenReturn(true);
        when(outbox.isGenerationCurrent(7L, 2L)).thenReturn(true);
        var job = new MemorySyncOutbox.Job(7L, 7L, 2L, "delete_memory", 11L,
                "remote-uuid", null, List.of(), List.of(), 1, token);
        when(outbox.claimNext()).thenReturn(Optional.of(job));

        new MemorySyncWorker(outbox, mem0, breaker).processOne();

        verify(mem0).deleteMemory("remote-uuid");
        verify(outbox).complete(7L, token);
    }

    @Test
    void retriesSingleDeleteWhenRemoteUuidStillNeedsDiscovery() {
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        UUID token = UUID.randomUUID();
        when(mem0.enabled()).thenReturn(true);
        when(breaker.allowRequest()).thenReturn(true);
        when(outbox.isGenerationCurrent(7L, 2L)).thenReturn(true);
        when(mem0.deleteMemoryForLocalId(7L, 11L)).thenReturn(false);
        var job = new MemorySyncOutbox.Job(8L, 7L, 2L, "delete_memory", 11L,
                null, null, List.of(), List.of(), 2, token);
        when(outbox.claimNext()).thenReturn(Optional.of(job));

        new MemorySyncWorker(outbox, mem0, breaker).processOne();

        verify(mem0).deleteMemoryForLocalId(7L, 11L);
        verify(outbox).fail(8L, token, 2, "remote memory id not found yet");
        verify(outbox, never()).complete(anyLong(), any(UUID.class));
    }
}
