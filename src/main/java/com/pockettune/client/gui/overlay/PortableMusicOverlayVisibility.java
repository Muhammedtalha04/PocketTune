package com.pockettune.client.gui.overlay;

public final class PortableMusicOverlayVisibility {
    private PortableMusicOverlayVisibility() {
    }

    public static boolean shouldRender(
            boolean overlayEnabled,
            boolean hudHidden,
            boolean showWhenHudHidden,
            boolean screenOpen,
            boolean worldLoaded
    ) {
        return overlayEnabled
                && worldLoaded
                && !screenOpen
                && (!hudHidden || showWhenHudHidden);
    }
}
