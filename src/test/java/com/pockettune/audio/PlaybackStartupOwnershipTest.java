package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlaybackStartupOwnershipTest {
    @Test
    void acceptsOnlyTheLiveGenerationInTheCurrentSession() {
        assertTrue(PlaybackStartupOwnership.isCurrent(7L, 7L, 11L, 11L, false));
        assertFalse(PlaybackStartupOwnership.isCurrent(7L, 8L, 11L, 11L, false));
        assertFalse(PlaybackStartupOwnership.isCurrent(7L, 7L, 11L, 12L, false));
        assertFalse(PlaybackStartupOwnership.isCurrent(7L, 7L, 11L, 11L, true));
    }
}
