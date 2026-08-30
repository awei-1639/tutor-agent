package com.tutor.memory.policy;

import com.tutor.auth.AuthContext;
import com.tutor.memory.local.EpisodeStore;
import com.tutor.memory.application.LongTermMemoryService;
import com.tutor.memory.external.MemorySyncOutbox;
import com.tutor.memory.local.ConversationStore;
import com.tutor.profile.ProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MemoryControllerTest {
    @AfterEach void clearAuth() { AuthContext.clear(); }

    @Test
    void deletesOnlyCurrentUsersMemory() throws Exception {
        EpisodeStore store = mock(EpisodeStore.class);
        LongTermMemoryService memory = mock(LongTermMemoryService.class);
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        when(memory.forgetOne(42L, 9L)).thenReturn(true);
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(store, memory, outbox)).build();

        mvc.perform(delete("/memories/9")).andExpect(status().isOk());

        verify(memory).forgetOne(42L, 9L);
    }

    @Test
    void retriesOnlyForAuthenticatedCurrentUser() throws Exception {
        EpisodeStore store = mock(EpisodeStore.class);
        LongTermMemoryService memory = mock(LongTermMemoryService.class);
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        when(outbox.requeueFailedForUser(42L)).thenReturn(1);
        when(outbox.latestDeletionStatus(42L))
                .thenReturn(new MemorySyncOutbox.DeletionStatus("pending", 0, null));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(store, memory, outbox)).build();

        mvc.perform(post("/memories/remote-deletion/retry"))
                .andExpect(status().isOk());

        verify(outbox).requeueFailedForUser(42L);
        verify(outbox).latestDeletionStatus(42L);
        verify(outbox, never()).requeueFailedForUser(9L);
    }

    @Test
    void exposesOnlyCurrentUsersSingleMemoryDeletionStatus() throws Exception {
        EpisodeStore store = mock(EpisodeStore.class);
        LongTermMemoryService memory = mock(LongTermMemoryService.class);
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        when(outbox.latestDeletionStatus(42L, 9L))
                .thenReturn(java.util.Optional.of(new MemorySyncOutbox.DeletionStatus("retryable", 2, "timeout")));
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(store, memory, outbox)).build();

        mvc.perform(get("/memories/9/remote-deletion"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body)
                            .contains("retryable")
                            .contains("云端删除正在处理中");
                });

        verify(outbox).latestDeletionStatus(42L, 9L);
    }

    @Test
    void doesNotReturnSingleMemoryStatusForUnknownTask() throws Exception {
        EpisodeStore store = mock(EpisodeStore.class);
        LongTermMemoryService memory = mock(LongTermMemoryService.class);
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        when(outbox.latestDeletionStatus(42L, 9L)).thenReturn(java.util.Optional.empty());
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(store, memory, outbox)).build();

        mvc.perform(get("/memories/9/remote-deletion"))
                .andExpect(status().isNotFound());
    }
    private MemoryController controller(EpisodeStore store, LongTermMemoryService memory, MemorySyncOutbox outbox) {
        MemoryDeletionRateLimiter limiter = mock(MemoryDeletionRateLimiter.class);
        when(limiter.tryAcquire(anyLong())).thenReturn(true);
        return new MemoryController(store, memory, outbox, mock(ProfileService.class), mock(ConversationStore.class), mock(com.tutor.memory.local.FactStore.class), limiter);
    }
}
