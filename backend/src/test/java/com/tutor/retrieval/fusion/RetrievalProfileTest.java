package com.tutor.retrieval.fusion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetrievalProfileTest {

    @Test
    void acceptsTheBaselineProfile() {
        RetrievalProfile profile = new RetrievalProfile("baseline", 20, 12, 10, 8, 4,
                6, 40, 10, 0.85, 0.30, 0.40, 1.0, 0.15);

        assertEquals("baseline", profile.version());
        assertEquals(20, profile.vectorTopN());
        assertEquals(0.85, profile.graphAlpha());
    }

    @Test
    void rejectsValuesOutsideTheReviewedSafetyRange() {
        assertThrows(IllegalArgumentException.class, () -> new RetrievalProfile("baseline", 101, 12, 10, 8, 4,
                6, 40, 10, 0.85, 0.30, 0.40, 1.0, 0.15));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalProfile("baseline", 20, 12, 10, 8, 4,
                6, 40, 10, 1.01, 0.30, 0.40, 1.0, 0.15));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalProfile("baseline", 20, 12, 10, 8, 4,
                6, 40, 10, 0.85, 0.30, 0.40, 1.01, 0.15));
    }
}
