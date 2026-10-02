package com.tutor.coaching.plan;

import com.tutor.conversation.memory.application.LongTermMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 打卡回流：确定性事实写入长期记忆，失败不阻塞打卡主流程。 */
class PlanServiceCheckinFactTest {
    private final PlanStore store = mock(PlanStore.class);
    private final LongTermMemoryService memory = mock(LongTermMemoryService.class);
    private final PlanService service = new PlanService(store, mock(com.tutor.platform.llm.structured.StructuredOutputService.class));

    {
        service.setLongTermMemory(memory);
    }

    @Test
    void checkinRefluxesTaskContentIntoLongTermMemory() {
        when(store.taskExists(11L, 7L)).thenReturn(true);
        when(store.addCheckin(11L, 7L, "done", null))
                .thenReturn(new PlanModels.Checkin(1L, 11L, "done", null));
        when(store.taskById(11L, 7L)).thenReturn(new PlanModels.PlanTask(
                11L, 3L, java.time.LocalDate.now(), "Redis 持久化练习", "practice", 45, null));

        PlanModels.Checkin checkin = service.checkin(11L, 7L, "done", null);

        assertThat(checkin.status()).isEqualTo("done");
        verify(memory).recordPlanCheckin(7L, "Redis 持久化练习", "practice", "done");
    }

    @Test
    void memoryFailureDoesNotBreakCheckin() {
        when(store.taskExists(11L, 7L)).thenReturn(true);
        when(store.addCheckin(11L, 7L, "done", null))
                .thenReturn(new PlanModels.Checkin(1L, 11L, "done", null));
        when(store.taskById(11L, 7L)).thenReturn(new PlanModels.PlanTask(
                11L, 3L, java.time.LocalDate.now(), "复盘计划", "review", 30, null));
        doThrow(new RuntimeException("memory down")).when(memory)
                .recordPlanCheckin(anyLong(), anyString(), anyString(), anyString());

        PlanModels.Checkin checkin = service.checkin(11L, 7L, "done", null);

        assertThat(checkin.id()).isEqualTo(1L);
    }

    @Test
    void unknownTaskStillReturns404WithoutMemoryCall() {
        when(store.taskExists(99L, 7L)).thenReturn(false);

        assertThatThrownBy(() -> service.checkin(99L, 7L, "done", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verify(memory, never()).recordPlanCheckin(anyLong(), anyString(), anyString(), anyString());
        verify(store, never()).taskById(anyLong(), anyLong());
        verify(store, never()).addCheckin(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void memoryNotWiredStillChecksIn() {
        PlanService bare = new PlanService(store, mock(com.tutor.platform.llm.structured.StructuredOutputService.class));
        when(store.taskExists(11L, 7L)).thenReturn(true);
        when(store.addCheckin(11L, 7L, "skipped", null))
                .thenReturn(new PlanModels.Checkin(2L, 11L, "skipped", null));

        PlanModels.Checkin checkin = bare.checkin(11L, 7L, "skipped", null);

        assertThat(checkin.id()).isEqualTo(2L);
    }
}
