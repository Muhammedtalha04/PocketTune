package com.pockettune.client.gui.overlay;

/**
 * Responsive layout in Minecraft GUI-scaled coordinates. It deliberately never uses physical pixels,
 * so GUI Scale, window resizing and ultrawide aspect ratios are handled by the vanilla transform.
 */
public final class PortableMusicOverlayLayout {
    private static final int EFFECT_ICON_TOP = 1;
    private static final int DEMO_BANNER_HEIGHT = 15;
    private static final int NEGATIVE_EFFECT_ROW_OFFSET = 26;
    private static final int EFFECT_ICON_SIZE = 24;
    private static final int EFFECT_OVERLAY_GAP = 4;

    private PortableMusicOverlayLayout() {
    }

    public static Layout calculate(int guiWidth, int guiHeight) {
        return calculate(guiWidth, guiHeight, 0);
    }

    public static Layout calculate(int guiWidth, int guiHeight, int topInset) {
        int safeWidth = Math.max(1, guiWidth);
        int safeHeight = Math.max(1, guiHeight);
        Variant variant = chooseVariant(safeWidth, safeHeight);
        int margin = variant == Variant.MINIMAL ? 4 : 8;
        int availableWidth = Math.max(1, safeWidth - margin * 2);
        int requestedPanelY = Math.max(margin, Math.max(0, topInset));
        int panelY = Math.min(requestedPanelY, Math.max(0, safeHeight - 1));
        int availableHeight = Math.max(1, safeHeight - panelY - margin);
        int desiredWidth = switch (variant) {
            case STANDARD -> 228;
            case COMPACT -> 202;
            case MINIMAL -> 176;
        };
        int desiredHeight = switch (variant) {
            case STANDARD -> 64;
            case COMPACT -> 54;
            case MINIMAL -> 46;
        };
        int panelWidth = Math.min(desiredWidth, availableWidth);
        int panelHeight = Math.min(desiredHeight, availableHeight);
        int panelX = Math.max(0, safeWidth - margin - panelWidth);
        panelY = Math.min(panelY, Math.max(0, safeHeight - panelHeight));
        Rect panel = new Rect(panelX, panelY, panelWidth, panelHeight);

        int padding = variant == Variant.MINIMAL ? 5 : 7;
        int shortestPanelSide = Math.min(panelWidth, panelHeight);
        padding = Math.min(padding, Math.max(0, (shortestPanelSide - 1) / 2));
        int desiredCover = switch (variant) {
            case STANDARD -> 50;
            case COMPACT -> 42;
            case MINIMAL -> 34;
        };
        int coverSize = Math.max(1, Math.min(desiredCover, Math.min(
                Math.max(1, panelHeight - padding * 2),
                Math.max(1, panelWidth / 3)
        )));
        Rect cover = new Rect(panel.x() + padding, panel.y() + padding, coverSize, coverSize);

        int contentX = Math.min(panel.right(), cover.right() + (variant == Variant.MINIMAL ? 6 : 8));
        int contentRight = Math.max(contentX, panel.right() - padding);
        int contentWidth = Math.max(0, contentRight - contentX);
        int iconSize = Math.min(13, Math.min(
                Math.max(0, contentWidth),
                Math.max(0, panel.bottom() - (panel.y() + padding))
        ));
        Rect statusIcon = new Rect(
                Math.max(contentX, contentRight - iconSize),
                panel.y() + padding,
                iconSize,
                iconSize
        );
        int titleRight = Math.max(contentX, statusIcon.x() - 4);
        int titleY = Math.min(panel.bottom(), panel.y() + padding);
        Rect title = new Rect(contentX, titleY, Math.max(0, titleRight - contentX),
                Math.min(10, Math.max(0, panel.bottom() - titleY)));
        int artistY = Math.min(panel.bottom(), panel.y() + padding + 14);
        Rect artist = new Rect(contentX, artistY, contentWidth,
                Math.min(10, Math.max(0, panel.bottom() - artistY)));

        boolean showTime = variant != Variant.MINIMAL && panelHeight >= 50 && contentWidth >= 72;
        Rect time = showTime
                ? new Rect(contentX, Math.min(panel.bottom(), panel.y() + padding + 29), contentWidth, 9)
                : new Rect(contentX, panel.bottom(), 0, 0);
        int progressY = Math.max(panel.y(), panel.bottom() - (variant == Variant.STANDARD ? 7 : 6));
        Rect progress = new Rect(contentX, progressY, contentWidth,
                Math.min(2, Math.max(0, panel.bottom() - progressY)));
        return new Layout(variant, panel, cover, title, artist, statusIcon, time, progress, showTime);
    }

    /**
     * Mirrors vanilla's two status-effect rows and returns the first safe Y coordinate below them.
     * Hidden HUD layers call this with both rows disabled, so an F1-visible overlay keeps its normal margin.
     */
    public static int topInsetForEffectRows(
            boolean beneficialEffectVisible,
            boolean harmfulEffectVisible,
            boolean demoMode
    ) {
        if (!beneficialEffectVisible && !harmfulEffectVisible) {
            return 0;
        }
        int rowTop = EFFECT_ICON_TOP + (demoMode ? DEMO_BANNER_HEIGHT : 0);
        int bottom = beneficialEffectVisible ? rowTop + EFFECT_ICON_SIZE : 0;
        if (harmfulEffectVisible) {
            bottom = Math.max(bottom, rowTop + NEGATIVE_EFFECT_ROW_OFFSET + EFFECT_ICON_SIZE);
        }
        return bottom + EFFECT_OVERLAY_GAP;
    }

    public static TimeRowAllocation allocateTimeRow(
            int rowWidth,
            int timeTextWidth,
            int statusTextWidth,
            int gap,
            int minimumStatusWidth
    ) {
        int safeRowWidth = Math.max(0, rowWidth);
        int renderedTimeWidth = Math.min(safeRowWidth, Math.max(0, timeTextWidth));
        int timeOffset = safeRowWidth - renderedTimeWidth;
        int availableStatusWidth = Math.max(0, timeOffset - Math.max(0, gap));
        int requiredStatusWidth = Math.min(Math.max(0, statusTextWidth), Math.max(0, minimumStatusWidth));
        boolean showStatus = statusTextWidth > 0 && availableStatusWidth >= requiredStatusWidth;
        int renderedStatusWidth = showStatus
                ? Math.min(Math.max(0, statusTextWidth), availableStatusWidth)
                : 0;
        return new TimeRowAllocation(renderedStatusWidth, timeOffset, renderedTimeWidth, showStatus);
    }

    public static int progressFillWidth(int trackWidth, long elapsedMillis, long durationMillis) {
        if (trackWidth <= 0 || durationMillis <= 0L) {
            return 0;
        }
        double ratio = Math.max(0.0D, Math.min(1.0D, elapsedMillis / (double) durationMillis));
        return (int) Math.round(trackWidth * ratio);
    }

    private static Variant chooseVariant(int guiWidth, int guiHeight) {
        if (guiWidth >= 520 && guiHeight >= 280) {
            return Variant.STANDARD;
        }
        if (guiWidth >= 360 && guiHeight >= 200) {
            return Variant.COMPACT;
        }
        return Variant.MINIMAL;
    }

    public enum Variant {
        STANDARD,
        COMPACT,
        MINIMAL
    }

    public record Rect(int x, int y, int width, int height) {
        public Rect {
            width = Math.max(0, width);
            height = Math.max(0, height);
        }

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean contains(Rect child) {
            return child.x() >= x
                    && child.y() >= y
                    && child.right() <= right()
                    && child.bottom() <= bottom();
        }
    }

    public record Layout(
            Variant variant,
            Rect panel,
            Rect cover,
            Rect title,
            Rect artist,
            Rect statusIcon,
            Rect time,
            Rect progress,
            boolean showTime
    ) {
    }

    public record TimeRowAllocation(
            int statusWidth,
            int timeOffset,
            int timeWidth,
            boolean showStatus
    ) {
    }
}
