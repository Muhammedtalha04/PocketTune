package com.pockettune.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PauseMenuSessionStateTest {
    @Test
    void pauseMenuChildrenRemainPausedUntilTheWorldScreenReturns() {
        PauseMenuSessionState state = new PauseMenuSessionState();

        assertTrue(state.update(true, true, true));
        assertTrue(state.update(true, false, true));
        assertTrue(state.update(true, false, true));
        assertFalse(state.update(true, false, false));
    }

    @Test
    void directlyOpenedGameplayScreensNeverStartAPauseSession() {
        PauseMenuSessionState state = new PauseMenuSessionState();

        assertFalse(state.update(true, false, true));
        assertFalse(state.update(true, false, true));
        assertFalse(state.update(true, false, false));
    }

    @Test
    void unloadingTheWorldClearsAnExistingPauseSession() {
        PauseMenuSessionState state = new PauseMenuSessionState();

        assertTrue(state.update(true, true, true));
        assertFalse(state.update(false, false, true));
    }
}
