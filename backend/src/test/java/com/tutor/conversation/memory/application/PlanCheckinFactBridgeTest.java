package com.tutor.conversation.memory.application;

import com.tutor.conversation.memory.external.Mem0CircuitBreaker;
import com.tutor.conversation.memory.external.Mem0Client;
import com.tutor.conversation.memory.local.EpisodeRecall;
import com.tutor.conversation.memory.local.FactReconciler;
import com.tutor.conversation.memory.policy.MemoryConsentService;
import com.tutor.platform.llm.structured.FactExtractOutput;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 打卡事实回流：确定性事件走既有消解管线，generation 取当前值。 */
class PlanCheckinFactBridgeTest {

    private LongTermMemoryService service(FactReconciler reconciler, MemoryConsentService consent) {
        LongTermMemoryService service = new LongTermMemoryService(
                mock(EpisodeRecall.class), mock(Mem0Client.class), consent,
                mock(Mem0CircuitBreaker.class), transactionTemplate(),
                mock(com.tutor.conversation.memory.external.MemorySyncOutbox.class),
                mock(com.tutor.conversation.memory.local.FactStore.class), 30);
        service.setFactReconciler(reconciler);
        return service;
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    @Test
    void checkinFactGoesThroughReconcilerWithCurrentGeneration() {
        FactReconciler reconciler = mock(FactReconciler.class);
        MemoryConsentService consent = mock(MemoryConsentService.class);
        when(consent.currentGeneration(7L)).thenReturn(42L);
        when(reconciler.reconcile(anyLong(), anyLong(), any(), any())).thenReturn(FactReconciler.ReconcileResult.EMPTY);

        service(reconciler, consent).recordPlanCheckin(7L, "Redis 持久化练习", "practice", "done");

        ArgumentCaptor<List<FactExtractOutput.ExtractedFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciler).reconcile(eq(7L), eq(42L), isNull(), captor.capture());
        FactExtractOutput.ExtractedFact fact = captor.getValue().getFirst();
        assertThat(fact.text()).contains("完成了今日学习任务").contains("Redis 持久化练习");
        assertThat(fact.category()).isEqualTo("skill");
        assertThat(fact.confidence()).isEqualTo(0.9D);
    }

    @Test
    void skippedCheckinFallsIntoBackgroundCategory() {
        FactReconciler reconciler = mock(FactReconciler.class);
        when(reconciler.reconcile(anyLong(), anyLong(), any(), any())).thenReturn(FactReconciler.ReconcileResult.EMPTY);

        service(reconciler, mock(MemoryConsentService.class)).recordPlanCheckin(7L, "复盘计划", "review", "skipped");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FactExtractOutput.ExtractedFact>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciler).reconcile(anyLong(), anyLong(), any(), captor.capture());
        assertThat(captor.getValue().getFirst().category()).isEqualTo("background");
    }

    @Test
    void missingReconcilerDisablesRefluxInsteadOfFailing() {
        FactReconciler reconciler = mock(FactReconciler.class);
        LongTermMemoryService service = service(reconciler, mock(MemoryConsentService.class));
        service.setFactReconciler(null);

        service.recordPlanCheckin(7L, "任意任务", "practice", "done");

        verifyNoInteractions(reconciler);
    }
}
