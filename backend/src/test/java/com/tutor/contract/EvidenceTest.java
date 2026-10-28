package com.tutor.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceTest {

    @Test
    void nullOrBlankSourceKindFallsBackToReference() {
        assertThat(new Evidence("n1", "skill", "text", 0.9, null, null, null, null, null).sourceKind())
                .isEqualTo(Evidence.KIND_REFERENCE);
        assertThat(new Evidence("n1", "skill", "text", 0.9, null, null, null, null, "  ").sourceKind())
                .isEqualTo(Evidence.KIND_REFERENCE);
    }

    @Test
    void compatConstructorDefaultsToReference() {
        Evidence legacy = new Evidence("n1", "skill", "text", 0.9, null, null, null, null);
        assertThat(legacy.sourceKind()).isEqualTo(Evidence.KIND_REFERENCE);
        assertThat(legacy.selfMaterial()).isFalse();
    }

    @Test
    void selfMaterialRecognizesSelfKindCaseInsensitively() {
        assertThat(new Evidence("n1", "skill", "text", 0.9, null, null, null, null, "self").selfMaterial())
                .isTrue();
        assertThat(new Evidence("n1", "skill", "text", 0.9, null, null, null, null, "SELF").selfMaterial())
                .isTrue();
        assertThat(new Evidence("n1", "skill", "text", 0.9, null, null, null, null, "reference").selfMaterial())
                .isFalse();
    }
}
