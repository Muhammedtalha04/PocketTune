package com.pockettune.client.gui.overlay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PortableMusicOverlayLayoutTest {
    @Test
    void layoutStaysInsideEveryGuiScaleAndAspectRatioViewport() {
        int[][] viewports = {
                {1920, 1080},
                {960, 540},
                {640, 360},
                {480, 270},
                {427, 240},
                {320, 180},
                {1720, 720},
                {860, 360},
                {240, 135}
        };

        for (int[] viewport : viewports) {
            PortableMusicOverlayLayout.Layout layout =
                    PortableMusicOverlayLayout.calculate(viewport[0], viewport[1]);
            PortableMusicOverlayLayout.Rect panel = layout.panel();
            int expectedMargin = layout.variant() == PortableMusicOverlayLayout.Variant.MINIMAL ? 4 : 8;

            assertEquals(viewport[0] - expectedMargin, panel.right());
            assertTrue(panel.x() >= 0);
            assertTrue(panel.y() >= 0);
            assertTrue(panel.right() <= viewport[0]);
            assertTrue(panel.bottom() <= viewport[1]);
            assertTrue(panel.contains(layout.cover()));
            assertTrue(panel.contains(layout.title()));
            assertTrue(panel.contains(layout.artist()));
            assertTrue(panel.contains(layout.statusIcon()));
            assertTrue(panel.contains(layout.progress()));
            assertEquals(layout.cover().width(), layout.cover().height());
        }
    }

    @Test
    void variantsChangeDeterministicallyAtResponsiveBreakpoints() {
        assertEquals(PortableMusicOverlayLayout.Variant.STANDARD,
                PortableMusicOverlayLayout.calculate(520, 280).variant());
        assertEquals(PortableMusicOverlayLayout.Variant.COMPACT,
                PortableMusicOverlayLayout.calculate(520, 279).variant());
        assertEquals(PortableMusicOverlayLayout.Variant.COMPACT,
                PortableMusicOverlayLayout.calculate(519, 280).variant());
        assertEquals(PortableMusicOverlayLayout.Variant.COMPACT,
                PortableMusicOverlayLayout.calculate(519, 279).variant());
        assertEquals(PortableMusicOverlayLayout.Variant.COMPACT,
                PortableMusicOverlayLayout.calculate(360, 200).variant());
        assertEquals(PortableMusicOverlayLayout.Variant.MINIMAL,
                PortableMusicOverlayLayout.calculate(360, 199).variant());
        assertEquals(PortableMusicOverlayLayout.Variant.MINIMAL,
                PortableMusicOverlayLayout.calculate(359, 200).variant());
        assertEquals(PortableMusicOverlayLayout.Variant.MINIMAL,
                PortableMusicOverlayLayout.calculate(359, 199).variant());
    }

    @Test
    void statusEffectRowsProduceTheSameSafeInsetsAsVanilla() {
        assertEquals(0, PortableMusicOverlayLayout.topInsetForEffectRows(false, false, false));
        assertEquals(29, PortableMusicOverlayLayout.topInsetForEffectRows(true, false, false));
        assertEquals(55, PortableMusicOverlayLayout.topInsetForEffectRows(false, true, false));
        assertEquals(55, PortableMusicOverlayLayout.topInsetForEffectRows(true, true, false));
        assertEquals(44, PortableMusicOverlayLayout.topInsetForEffectRows(true, false, true));
        assertEquals(70, PortableMusicOverlayLayout.topInsetForEffectRows(false, true, true));
    }

    @Test
    void requestedTopInsetMovesOverlayBelowEffectsWithoutBreakingContainment() {
        PortableMusicOverlayLayout.Layout layout = PortableMusicOverlayLayout.calculate(640, 360, 55);

        assertEquals(55, layout.panel().y());
        assertLayoutContained(layout, 640, 360);
    }

    @Test
    void extremeTopInsetShrinksOverlayInsteadOfLeavingTheViewport() {
        PortableMusicOverlayLayout.Layout layout = PortableMusicOverlayLayout.calculate(240, 60, 55);

        assertTrue(layout.panel().y() >= 55);
        assertLayoutContained(layout, 240, 60);
    }

    @Test
    void progressWidthClampsUnknownNegativeAndOverflowValues() {
        assertEquals(0, PortableMusicOverlayLayout.progressFillWidth(100, 50_000L, 0L));
        assertEquals(0, PortableMusicOverlayLayout.progressFillWidth(100, -5_000L, 60_000L));
        assertEquals(50, PortableMusicOverlayLayout.progressFillWidth(100, 30_000L, 60_000L));
        assertEquals(100, PortableMusicOverlayLayout.progressFillWidth(100, 90_000L, 60_000L));
        assertEquals(0, PortableMusicOverlayLayout.progressFillWidth(0, 30_000L, 60_000L));
    }

    @Test
    void minimalLayoutRetainsCoverMetadataStatusAndProgressWithoutTimeCollision() {
        PortableMusicOverlayLayout.Layout layout = PortableMusicOverlayLayout.calculate(320, 180);

        assertEquals(PortableMusicOverlayLayout.Variant.MINIMAL, layout.variant());
        assertFalse(layout.showTime());
        assertTrue(layout.cover().width() > 0);
        assertTrue(layout.title().width() > 0);
        assertTrue(layout.artist().width() > 0);
        assertTrue(layout.statusIcon().width() > 0);
        assertTrue(layout.progress().width() > 0);
        assertTrue(layout.title().right() <= layout.statusIcon().x());
    }

    @Test
    void timeAndStatusAllocationNeverOverlap() {
        PortableMusicOverlayLayout.TimeRowAllocation roomy =
                PortableMusicOverlayLayout.allocateTimeRow(120, 60, 50, 6, 6);
        assertTrue(roomy.showStatus());
        assertEquals(50, roomy.statusWidth());
        assertEquals(60, roomy.timeOffset());
        assertTrue(roomy.statusWidth() + 6 <= roomy.timeOffset());
        assertTrue(roomy.timeOffset() + roomy.timeWidth() <= 120);

        PortableMusicOverlayLayout.TimeRowAllocation narrow =
                PortableMusicOverlayLayout.allocateTimeRow(70, 60, 50, 6, 6);
        assertFalse(narrow.showStatus());
        assertEquals(0, narrow.statusWidth());
        assertTrue(narrow.timeOffset() + narrow.timeWidth() <= 70);

        PortableMusicOverlayLayout.TimeRowAllocation oversizedTime =
                PortableMusicOverlayLayout.allocateTimeRow(50, 80, 50, 6, 6);
        assertFalse(oversizedTime.showStatus());
        assertEquals(0, oversizedTime.timeOffset());
        assertEquals(50, oversizedTime.timeWidth());
    }

    private static void assertLayoutContained(
            PortableMusicOverlayLayout.Layout layout,
            int viewportWidth,
            int viewportHeight
    ) {
        PortableMusicOverlayLayout.Rect panel = layout.panel();
        assertTrue(panel.x() >= 0);
        assertTrue(panel.y() >= 0);
        assertTrue(panel.right() <= viewportWidth);
        assertTrue(panel.bottom() <= viewportHeight);
        assertTrue(panel.contains(layout.cover()));
        assertTrue(panel.contains(layout.title()));
        assertTrue(panel.contains(layout.artist()));
        assertTrue(panel.contains(layout.statusIcon()));
        assertTrue(panel.contains(layout.time()));
        assertTrue(panel.contains(layout.progress()));
    }
}
