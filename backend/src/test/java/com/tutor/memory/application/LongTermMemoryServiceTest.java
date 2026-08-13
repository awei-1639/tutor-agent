package com.tutor.memory.application;

import com.tutor.memory.application.LongTermMemoryService;
import com.tutor.memory.external.Mem0CircuitBreaker;
import com.tutor.memory.external.Mem0Client;
import com.tutor.memory.local.EpisodeRecall;
import com.tutor.memory.local.EpisodeStore;
import com.tutor.memory.policy.MemoryConsentService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.tutor.memory.external.MemorySyncOutbox;

class LongTermMemoryServiceTest {
    @Test
    void mergesLocalAndRemoteMemoriesWithoutDuplicateSummaries() {
        EpisodeRecall local = mock(EpisodeRecall.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        MemoryConsentService consent = mock(MemoryConsentService.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        TransactionTemplate transactions = transactionTemplate();
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        when(local.recall(7L, "query", "trace")).thenReturn(List.of(
                new EpisodeStore.Episode(1, 7, 1L, "学习 RAG", List.of(), List.of())));
        when(mem0.enabled()).thenReturn(true);
        when(consent.enabledFor(7L)).thenReturn(true);
        when(breaker.allowRequest()).thenReturn(true);
        when(mem0.search(7L, "query", "trace")).thenReturn(List.of(
                new EpisodeStore.Episode(0, 7, null, "学习 RAG", List.of(), List.of()),
                new EpisodeStore.Episode(0, 7, null, "准备面试", List.of(), List.of())));

        LongTermMemoryService service = new LongTermMemoryService(local, mem0, consent, breaker, transactions, outbox);
        LongTermMemoryService.RecallResult result = service.recall(7L, "query", "trace");

        assertThat(result.degraded()).isFalse();
        assertThat(result.episodes()).extracting(EpisodeStore.Episode::summary)
                .containsExactly("学习 RAG", "准备面试");
    }

    @Test
    void remoteFailureFallsBackToLocalMemory() {
        EpisodeRecall local = mock(EpisodeRecall.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        MemoryConsentService consent = mock(MemoryConsentService.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        TransactionTemplate transactions = transactionTemplate();
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        List<EpisodeStore.Episode> localEpisodes = List.of(
                new EpisodeStore.Episode(1, 7, 1L, "本地记忆", List.of(), List.of()));
        when(local.recall(7L, "query", "trace")).thenReturn(localEpisodes);
        when(mem0.enabled()).thenReturn(true);
        when(consent.enabledFor(7L)).thenReturn(true);
        when(breaker.allowRequest()).thenReturn(true);
        when(mem0.search(7L, "query", "trace")).thenThrow(new IllegalStateException("timeout"));

        LongTermMemoryService.RecallResult result = new LongTermMemoryService(local, mem0, consent, breaker, transactions, outbox)
                .recall(7L, "query", "trace");

        assertThat(result.degraded()).isTrue();
        assertThat(result.episodes()).isEqualTo(localEpisodes);
    }

    @Test
    void doesNotSendUnreviewedConversationToRemoteMemory() {
        EpisodeRecall local = mock(EpisodeRecall.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        MemoryConsentService consent = mock(MemoryConsentService.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        TransactionTemplate transactions = transactionTemplate();
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        when(mem0.enabled()).thenReturn(true);
        when(consent.enabledFor(7L)).thenReturn(true);
        when(breaker.allowRequest()).thenReturn(true);
        new LongTermMemoryService(local, mem0, consent, breaker, transactions, outbox).remember(7L, "question", "answer", "trace");

        verifyNoInteractions(mem0);
    }

    private static TransactionTemplate transactionTemplate() {
        return mock(TransactionTemplate.class);
    }
}
