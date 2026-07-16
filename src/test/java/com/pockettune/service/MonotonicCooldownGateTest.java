package com.pockettune.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MonotonicCooldownGateTest {
    @Test
    void repeatedFeedbackIsSuppressedUntilCooldownExpires() {
        MonotonicCooldownGate<String> gate = new MonotonicCooldownGate<>(100L);

        assertTrue(gate.tryAcquire("player", 1_000L));
        assertFalse(gate.tryAcquire("player", 1_099L));
        assertTrue(gate.tryAcquire("player", 1_100L));
    }

    @Test
    void expiredAndClearedKeysDoNotRemainTracked() {
        MonotonicCooldownGate<String> gate = new MonotonicCooldownGate<>(100L);
        assertTrue(gate.tryAcquire("old-player", 0L));
        assertTrue(gate.tryAcquire("new-player", 100L));
        assertEquals(1, gate.trackedKeyCount());

        gate.clear();

        assertEquals(0, gate.trackedKeyCount());
        assertTrue(gate.tryAcquire("old-player", 101L));
    }
}
