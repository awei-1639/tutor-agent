package com.tutor.conversation.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteMemoryIdTest {

    @Test
    void acceptsOnlyTheRemoteProtocolIdentifierFormat() {
        assertTrue(RemoteMemoryId.isValid("mem_01-AB"));
        assertFalse(RemoteMemoryId.isValid("mem id"));
        assertFalse(RemoteMemoryId.isValid("../mem"));
        assertFalse(RemoteMemoryId.isValid(null));
    }

    @Test
    void exposesAFailFastBoundaryForOutboundRequests() {
        assertDoesNotThrow(() -> RemoteMemoryId.requireValid("mem_01-AB"));
        assertThrows(IllegalArgumentException.class, () -> RemoteMemoryId.requireValid("bad/id"));
    }
}
