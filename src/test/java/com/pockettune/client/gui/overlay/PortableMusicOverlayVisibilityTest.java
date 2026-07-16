package com.pockettune.client.gui.overlay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PortableMusicOverlayVisibilityTest {
    @Test
    void normalGameplayShowsEnabledOverlay() {
        assertTrue(PortableMusicOverlayVisibility.shouldRender(true, false, false, false, true));
    }

    @Test
    void f1CanKeepOverlayVisibleWhenConfigured() {
        assertTrue(PortableMusicOverlayVisibility.shouldRender(true, true, true, false, true));
    }

    @Test
    void f1HidesOverlayWhenUserDisablesTheOverride() {
        assertFalse(PortableMusicOverlayVisibility.shouldRender(true, true, false, false, true));
    }

    @Test
    void disabledOverlayAndOpenScreensStayHidden() {
        assertFalse(PortableMusicOverlayVisibility.shouldRender(false, false, true, false, true));
        assertFalse(PortableMusicOverlayVisibility.shouldRender(true, false, true, true, true));
    }

    @Test
    void overlayNeverRendersWithoutAnActiveWorld() {
        assertFalse(PortableMusicOverlayVisibility.shouldRender(true, false, true, false, false));
    }

    @Test
    void f1OverrideNeverBypassesMasterScreenOrWorldGuards() {
        assertFalse(PortableMusicOverlayVisibility.shouldRender(false, true, true, false, true));
        assertFalse(PortableMusicOverlayVisibility.shouldRender(true, true, true, true, true));
        assertFalse(PortableMusicOverlayVisibility.shouldRender(true, true, true, false, false));
    }
}
