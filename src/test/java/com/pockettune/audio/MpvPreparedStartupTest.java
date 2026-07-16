package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MpvPreparedStartupTest {
    @Test
    void preparedLaunchIsAlwaysPausedAndMutedUntilOwnerPublishesSettings() {
        List<String> command = MpvController.buildLaunchCommand(
                "mpv",
                "https://media.example/audio",
                "test-endpoint",
                84.5D,
                false,
                true,
                true
        );

        assertEquals("mpv", command.getFirst());
        assertTrue(command.contains("--no-config"));
        assertTrue(command.contains("--pause=yes"));
        assertTrue(command.contains("--volume=0.0"));
        assertTrue(command.indexOf("--pause=yes") < command.indexOf("--"));
        assertEquals("https://media.example/audio", command.getLast());
    }

    @Test
    void legacyLaunchKeepsItsImmediateVolumeContract() {
        List<String> command = MpvController.buildLaunchCommand(
                "mpv",
                "https://media.example/audio",
                "test-endpoint",
                84.5D,
                false,
                false,
                false
        );

        assertFalse(command.contains("--pause=yes"));
        assertTrue(command.contains("--volume=84.5"));
    }

    @Test
    void seekVerificationRejectsAnUncommittedOrInvalidPosition() {
        assertTrue(MpvController.isSeekPositionVerified(61.0D, 61.5D));
        assertTrue(MpvController.isSeekPositionVerified(0.0D, 0.0D));
        assertFalse(MpvController.isSeekPositionVerified(61.0D, 62.0D));
        assertFalse(MpvController.isSeekPositionVerified(61.0D, Double.NaN));
    }
}
