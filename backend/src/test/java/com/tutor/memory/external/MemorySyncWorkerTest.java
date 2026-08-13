package com.tutor.memory.external;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.*;

class MemorySyncWorkerTest {
    @Test
    void deletesRemoteMemoryAndCompletesClaimedJob() {
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        when(mem0.enabled()).thenReturn(true);
        when(breaker.allowRequest()).thenReturn(true);
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
        var job = new MemorySyncOutbox.Job(3L, 7L, 2L, "delete_user", 2);
        when(outbox.claimNext()).thenReturn(Optional.of(job));
        doThrow(new IllegalStateException("timeout")).when(mem0).deleteAllForUser(7L);

        new MemorySyncWorker(outbox, mem0, breaker).processOne();

        verify(outbox).fail(3L, 2, "timeout");
        verify(breaker).failure();
    }
}
