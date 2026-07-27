package com.tutor.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Intent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRouterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesValidIntent() {
        assertThat(IntentRouter.parseIntent("{\"intent\":\"resume\"}", mapper)).isEqualTo(Intent.RESUME);
        assertThat(IntentRouter.parseIntent("{\"intent\":\"OUT_OF_SCOPE\"}", mapper)).isEqualTo(Intent.OUT_OF_SCOPE);
    }

    @Test
    void unknownValueFallsBackToMixed() {
        assertThat(IntentRouter.parseIntent("{\"intent\":\"banana\"}", mapper)).isEqualTo(Intent.MIXED);
    }

    @Test
    void malformedJsonFallsBackToMixed() {
        assertThat(IntentRouter.parseIntent("not json", mapper)).isEqualTo(Intent.MIXED);
    }
}
