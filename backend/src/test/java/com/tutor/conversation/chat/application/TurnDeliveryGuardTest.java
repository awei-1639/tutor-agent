package com.tutor.conversation.chat.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 交付不变量的判定契约 (2026-10-06): 静默死亡与空泡不允许再次潜伏。 */
class TurnDeliveryGuardTest {
    private final TurnDeliveryGuard guard = new TurnDeliveryGuard();

    @Test
    void doneWithoutAnyTokensOrClarifyIsTheEmptyBubbleClass() {
        guard.onDone();
        assertTrue(guard.verdict(false).isPresent());
    }

    @Test
    void doneWithTokensIsCompliant() {
        guard.onToken();
        guard.onDone();
        assertFalse(guard.verdict(false).isPresent());
    }

    @Test
    void clarifyTurnsWithoutTokensAreCompliant() {
        guard.onClarify();
        guard.onDone();
        assertFalse(guard.verdict(false).isPresent());
    }

    @Test
    void reasoningAloneCountsAsDelivery() {
        guard.onReasoning();
        guard.onDone();
        assertFalse(guard.verdict(false).isPresent());
    }

    @Test
    void streamEndedWithoutDoneIsSilentDeath() {
        guard.onToken();
        assertTrue(guard.verdict(false).orElse("").contains("silent death"));
    }

    @Test
    void explicitErrorAndCancellationAreNeverSilentDeaths() {
        guard.onError();
        assertFalse(guard.verdict(false).isPresent());
        assertFalse(guard.verdict(true).isPresent());
    }
}
