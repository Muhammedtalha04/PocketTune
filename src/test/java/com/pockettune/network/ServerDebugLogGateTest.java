package com.pockettune.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerDebugLogGateTest {
    @Test
    void requiresBothExplicitRequestAndServerPermission() {
        ServerDebugLogGate<String> gate = new ServerDebugLogGate<>(100L);

        assertFalse(gate.tryAuthorize("player", false, true, 1_000L));
        assertFalse(gate.tryAuthorize("player", true, false, 1_000L));
        assertTrue(gate.tryAuthorize("player", true, true, 1_000L));
    }

    @Test
    void rateLimitsEachPlayerIndependently() {
        ServerDebugLogGate<String> gate = new ServerDebugLogGate<>(100L);

        assertTrue(gate.tryAuthorize("first", true, true, 1_000L));
        assertFalse(gate.tryAuthorize("first", true, true, 1_099L));
        assertTrue(gate.tryAuthorize("second", true, true, 1_099L));
        assertTrue(gate.tryAuthorize("first", true, true, 1_100L));
    }

    @Test
    void deniedAttemptDoesNotConsumeCooldown() {
        ServerDebugLogGate<String> gate = new ServerDebugLogGate<>(100L);

        assertFalse(gate.tryAuthorize("player", true, false, 1_000L));
        assertTrue(gate.tryAuthorize("player", true, true, 1_001L));
    }
}
