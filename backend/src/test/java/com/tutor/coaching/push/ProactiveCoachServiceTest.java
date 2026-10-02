package com.tutor.coaching.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.coaching.plan.PlanService;
import com.tutor.conversation.memory.application.LongTermMemoryService;
import com.tutor.platform.scheduling.ScheduledTaskLock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 主动教练：有具体信号才触达，每用户每日至多一条，单用户异常不中断批量。 */
class ProactiveCoachServiceTest {
    private final ScheduledTaskLock taskLock = mock(ScheduledTaskLock.class);
    private final NotificationStore notifications = mock(NotificationStore.class);
    private final PlanService plans = mock(PlanService.class);
    private final LongTermMemoryService memory = mock(LongTermMemoryService.class);
    private final ProactiveCoachService service = new ProactiveCoachService(
            taskLock, notifications, plans, new ObjectMapper());

    {
        service.setLongTermMemory(memory);
    }

    @Test
    void openItemSignalProducesDailyCoachNotification() {
        when(notifications.coachSentToday(7L)).thenReturn(false);
        when(memory.recentOpenItems(7L, 1)).thenReturn(List.of("复盘 Redis 持久化"));

        boolean sent = service.runForUser(7L);

        assertThat(sent).isTrue();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(notifications).add(eq(7L), eq("coach_daily"), payload.capture());
        assertThat(payload.getValue()).contains("今天，接着往前走").contains("复盘 Redis 持久化").contains("signals");
    }

    @Test
    void alreadyNotifiedTodayStaysSilent() {
        when(notifications.coachSentToday(7L)).thenReturn(true);
        when(memory.recentOpenItems(7L, 1)).thenReturn(List.of("复盘 Redis 持久化"));

        assertThat(service.runForUser(7L)).isFalse();
        verify(notifications, never()).add(anyLong(), anyString(), any());
    }

    @Test
    void noSignalsStaysSilent() {
        when(notifications.coachSentToday(7L)).thenReturn(false);
        when(memory.recentOpenItems(7L, 1)).thenReturn(List.of());
        when(plans.shouldReplan(7L)).thenReturn(false);
        when(plans.lastCheckinAt(7L)).thenReturn(Instant.now());
        when(plans.hasRecentTasks(7L)).thenReturn(true);

        assertThat(service.runForUser(7L)).isFalse();
        verify(notifications, never()).add(anyLong(), anyString(), any());
    }

    @Test
    void streakBreakSignalFiresOnlyWithRecentTasks() {
        when(notifications.coachSentToday(7L)).thenReturn(false);
        when(memory.recentOpenItems(7L, 1)).thenReturn(List.of());
        when(plans.shouldReplan(7L)).thenReturn(false);
        when(plans.hasRecentTasks(7L)).thenReturn(true);
        when(plans.lastCheckinAt(7L)).thenReturn(Instant.now().minus(java.time.Duration.ofDays(5)));

        assertThat(service.runForUser(7L)).isTrue();
        verify(notifications).add(eq(7L), eq("coach_daily"), any());
    }

    @Test
    void noRecentTasksSuppressesStreakSignal() {
        when(notifications.coachSentToday(7L)).thenReturn(false);
        when(memory.recentOpenItems(7L, 1)).thenReturn(List.of());
        when(plans.shouldReplan(7L)).thenReturn(false);
        when(plans.hasRecentTasks(7L)).thenReturn(false);
        when(plans.lastCheckinAt(7L)).thenReturn(Instant.now().minus(java.time.Duration.ofDays(30)));

        assertThat(service.runForUser(7L)).isFalse();
        verify(notifications, never()).add(anyLong(), anyString(), any());
    }

    @Test
    void batchContinuesWhenOneUserFails() {
        when(notifications.allUserIds()).thenReturn(List.of(1L, 2L));
        when(notifications.coachSentToday(anyLong())).thenReturn(false);
        when(memory.recentOpenItems(anyLong(), anyInt())).thenReturn(List.of("未决事项"));
        doThrow(new RuntimeException("boom")).when(notifications).add(eq(1L), anyString(), any());

        service.runForAllUsers();

        verify(notifications).add(eq(2L), eq("coach_daily"), any());
    }

    @Test
    void scheduledRunDelegatesThroughLeaderLock() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        }).when(taskLock).runIfLeader(eq("proactive-coach"), eq(3600L), any(Runnable.class));
        when(notifications.allUserIds()).thenReturn(List.of());

        service.scheduledRun();

        verify(notifications).allUserIds();
    }
}
