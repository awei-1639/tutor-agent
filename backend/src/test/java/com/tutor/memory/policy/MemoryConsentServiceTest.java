package com.tutor.memory.policy;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class MemoryConsentServiceTest {
    private final MemoryConsentStore store = mock(MemoryConsentStore.class);
    private final MemoryConsentService service = new MemoryConsentService(store);

    @Test
    void delegatesConsentReadAndGenerationRead() {
        when(store.enabledFor(7L)).thenReturn(true);
        when(store.currentGeneration(7L)).thenReturn(3L);

        assertThat(service.enabledFor(7L)).isTrue();
        assertThat(service.currentGeneration(7L)).isEqualTo(3L);
        verify(store).enabledFor(7L);
        verify(store).currentGeneration(7L);
    }

    @Test
    void delegatesEnableAndGenerationInvalidation() {
        service.setEnabled(7L, true);
        service.invalidateMemoryGeneration(7L);

        verify(store).setEnabled(7L, true);
        verify(store).incrementGeneration(7L);
    }
}
