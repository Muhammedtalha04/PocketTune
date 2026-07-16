package com.pockettune.client.audio;

/**
 * Remembers whether the current screen chain originated from Minecraft's real ESC pause menu.
 */
public final class PauseMenuSessionState {
    private boolean active;

    public boolean update(boolean levelLoaded, boolean realPauseMenuOpen, boolean anyScreenOpen) {
        if (!levelLoaded) {
            active = false;
        } else if (realPauseMenuOpen) {
            active = true;
        } else if (!anyScreenOpen) {
            active = false;
        }
        return active;
    }

    public void reset() {
        active = false;
    }
}
