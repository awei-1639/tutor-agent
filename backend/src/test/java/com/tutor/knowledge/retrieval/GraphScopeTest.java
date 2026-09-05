package com.tutor.knowledge.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphScopeTest {
    @Test
    void userScopeIncludesPublicDataAndKeepsOwner() {
        GraphScope scope = GraphScope.forUser(42L);

        assertThat(scope.userId()).isEqualTo(42L);
        assertThat(scope.includePublic()).isTrue();
        assertThat(scope.isPublicOnly()).isFalse();
    }

    @Test
    void negativeUserIdIsRejected() {
        assertThatThrownBy(() -> new GraphScope(-1L, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
