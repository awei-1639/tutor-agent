package com.tutor.memory.policy;

import com.tutor.auth.AuthContext;
import com.tutor.memory.local.EpisodeStore;
import com.tutor.memory.application.LongTermMemoryService;
import com.tutor.memory.external.MemorySyncOutbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MemoryControllerTest {
    @AfterEach void clearAuth() { AuthContext.clear(); }

    @Test
    void deletesOnlyCurrentUsersMemory() throws Exception {
        EpisodeStore store = mock(EpisodeStore.class);
        LongTermMemoryService memory = mock(LongTermMemoryService.class);
        MemorySyncOutbox outbox = mock(MemorySyncOutbox.class);
        when(store.deleteByIdForUser(9L, 42L)).thenReturn(true);
        AuthContext.set(42L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MemoryController(store, memory, outbox)).build();

        mvc.perform(delete("/memories/9")).andExpect(status().isOk());

        verify(store).deleteByIdForUser(9L, 42L);
    }
}
