package com.pockettune.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalErrorAnnouncementGateTest {
    @Test
    void announcesOneTerminalFailureOncePerTrackGeneration() {
        TerminalErrorAnnouncementGate gate = new TerminalErrorAnnouncementGate();

        assertTrue(gate.claim("video-a", 10L));
        assertFalse(gate.claim("video-a", 10L));
        assertTrue(gate.claim("video-a", 11L));
        assertFalse(gate.claim("video-a", 11L));
        assertTrue(gate.claim("video-b", 11L));
    }

    @Test
    void healthyPlaybackAllowsARealLaterTerminalFailureToBeAnnounced() {
        TerminalErrorAnnouncementGate gate = new TerminalErrorAnnouncementGate();
        assertTrue(gate.claim("video-a", 10L));

        gate.markHealthy("video-a", 10L);

        assertTrue(gate.claim("video-a", 10L));
        assertFalse(gate.claim("video-a", 10L));
    }

    @Test
    void clearStartsANewPortableSession() {
        TerminalErrorAnnouncementGate gate = new TerminalErrorAnnouncementGate();
        assertTrue(gate.claim("video-a", 10L));

        gate.clear();

        assertTrue(gate.claim("video-a", 10L));
    }
}
