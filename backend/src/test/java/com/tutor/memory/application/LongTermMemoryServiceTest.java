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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionStatus;
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
        when(outbox.remoteReadAllowed(7L)).thenReturn(true);
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
    void ordersUniqueMemoriesByGlobalRelevanceBeforeApplyingLimit() {
        EpisodeRecall local = mock(EpisodeRecall.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        MemoryConsentService consent = mock(MemoryConsentService.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        when(local.recall(7L, "query", "trace")).thenReturn(List.of(
                new EpisodeStore.Episode(1, 7, 1L, "本地低相关记忆", List.of(), List.of(), 0.20D)));
        when(mem0.enabled()).thenReturn(true);
        when(outbox.remoteReadAllowed(7L)).thenReturn(true);
        when(consent.enabledFor(7L)).thenReturn(true);
        when(breaker.allowRequest()).thenReturn(true);
        when(mem0.search(7L, "query", "trace")).thenReturn(List.of(
                new EpisodeStore.Episode(0, 7, null, "远程高相关记忆", List.of(), List.of(), 0.90D)));

        LongTermMemoryService.RecallResult result = new LongTermMemoryService(
                local, mem0, consent, breaker, transactionTemplate(), outbox)
                .recall(7L, "query", "trace");

        assertThat(result.episodes()).extracting(EpisodeStore.Episode::summary)
                .containsExactly("远程高相关记忆", "本地低相关记忆");
    }

    @Test
    void dropsRemoteCopyWhenLocalAuthorityHasBeenDeleted() {
        EpisodeRecall local = mock(EpisodeRecall.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        MemoryConsentService consent = mock(MemoryConsentService.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        when(local.recall(7L, "query", "trace")).thenReturn(List.of());
        when(local.isActiveById(42L, 7L)).thenReturn(false);
        when(mem0.enabled()).thenReturn(true);
        when(outbox.remoteReadAllowed(7L)).thenReturn(true);
        when(consent.enabledFor(7L)).thenReturn(true);
        when(breaker.allowRequest()).thenReturn(true);
        when(mem0.search(7L, "query", "trace")).thenReturn(List.of(
                new EpisodeStore.Episode(42L, 7L, null, "已删除的旧记忆", List.of(), List.of(), 0.99D)));

        LongTermMemoryService.RecallResult result = new LongTermMemoryService(
                local, mem0, consent, breaker, transactionTemplate(), outbox)
                .recall(7L, "query", "trace");

        assertThat(result.episodes()).isEmpty();
        verify(local).isActiveById(42L, 7L);
    }

    @Test
    void doesNotReadRemoteMemoryWhileReauthorizationCleanupIsPending() {
        EpisodeRecall local = mock(EpisodeRecall.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        MemoryConsentService consent = mock(MemoryConsentService.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        List<EpisodeStore.Episode> localEpisodes = List.of(
                new EpisodeStore.Episode(1L, 7L, 1L, "本地记忆", List.of(), List.of()));
        when(local.recall(7L, "query", "trace")).thenReturn(localEpisodes);
        when(mem0.enabled()).thenReturn(true);
        when(consent.enabledFor(7L)).thenReturn(true);
        when(outbox.remoteReadAllowed(7L)).thenReturn(false);

        LongTermMemoryService.RecallResult result = new LongTermMemoryService(
                local, mem0, consent, breaker, transactionTemplate(), outbox)
                .recall(7L, "query", "trace");

        assertThat(result.episodes()).isEqualTo(localEpisodes);
        verifyNoInteractions(breaker);
        verify(mem0, never()).search(anyLong(), anyString(), anyString());
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
        when(outbox.remoteReadAllowed(7L)).thenReturn(true);
        when(consent.enabledFor(7L)).thenReturn(true);
        when(breaker.allowRequest()).thenReturn(true);
        when(mem0.search(7L, "query", "trace")).thenThrow(new IllegalStateException("timeout"));

        LongTermMemoryService.RecallResult result = new LongTermMemoryService(local, mem0, consent, breaker, transactions, outbox)
                .recall(7L, "query", "trace");

        assertThat(result.degraded()).isTrue();
        assertThat(result.episodes()).isEqualTo(localEpisodes);
    }

    @Test
    void localFailureDoesNotBlockConversation() {
        EpisodeRecall local = mock(EpisodeRecall.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        MemoryConsentService consent = mock(MemoryConsentService.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        when(local.recall(7L, "query", "trace")).thenThrow(new IllegalStateException("database unavailable"));
        when(mem0.enabled()).thenReturn(false);

        LongTermMemoryService.RecallResult result = new LongTermMemoryService(
                local, mem0, consent, breaker, transactionTemplate(), outbox)
                .recall(7L, "query", "trace");

        assertThat(result.episodes()).isEmpty();
        assertThat(result.degraded()).isTrue();
        verifyNoInteractions(consent, breaker);
    }

    @Test
    void consentFailureFallsBackToLocalMemory() {
        EpisodeRecall local = mock(EpisodeRecall.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        MemoryConsentService consent = mock(MemoryConsentService.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        List<EpisodeStore.Episode> localEpisodes = List.of(
                new EpisodeStore.Episode(1, 7, 1L, "本地记忆", List.of(), List.of()));
        when(local.recall(7L, "query", "trace")).thenReturn(localEpisodes);
        when(mem0.enabled()).thenReturn(true);
        when(outbox.remoteReadAllowed(7L)).thenReturn(true);
        when(consent.enabledFor(7L)).thenThrow(new IllegalStateException("consent store unavailable"));

        LongTermMemoryService.RecallResult result = new LongTermMemoryService(
                local, mem0, consent, breaker, transactionTemplate(), outbox)
                .recall(7L, "query", "trace");

        assertThat(result.episodes()).isEqualTo(localEpisodes);
        assertThat(result.degraded()).isTrue();
        verifyNoInteractions(breaker);
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
        when(outbox.remoteReadAllowed(7L)).thenReturn(true);
        when(consent.enabledFor(7L)).thenReturn(true);
        when(breaker.allowRequest()).thenReturn(true);
        new LongTermMemoryService(local, mem0, consent, breaker, transactions, outbox).remember(7L, "question", "answer", "trace");

        verifyNoInteractions(mem0);
    }

    @Test
    void queuesDiscoveryDeleteWhenRemoteUuidHasNotBeenObserved() {
        EpisodeRecall local = mock(EpisodeRecall.class);
        Mem0Client mem0 = mock(Mem0Client.class);
        MemoryConsentService consent = mock(MemoryConsentService.class);
        Mem0CircuitBreaker breaker = mock(Mem0CircuitBreaker.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(mem0.enabled()).thenReturn(true);
        when(local.remoteMemoryIdById(7L, 42L)).thenReturn(java.util.Optional.empty());
        when(local.deleteByIdForUser(42L, 7L)).thenReturn(true);

        LongTermMemoryService service = new LongTermMemoryService(
                local, mem0, consent, breaker, transactions, outbox);

        assertThat(service.forgetOne(7L, 42L)).isTrue();
        verify(outbox).enqueueDeleteMemory(7L, 42L, null);
    }

    private static TransactionTemplate transactionTemplate() {
        return mock(TransactionTemplate.class);
    }
}
