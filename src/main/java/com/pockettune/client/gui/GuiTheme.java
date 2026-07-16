package com.pockettune.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public final class GuiTheme {
    public static final int BACKGROUND = 0xF00B0E0C;
    public static final int PANEL = 0xF0171B18;
    public static final int PANEL_DARK = 0xF0101311;
    public static final int PANEL_RAISED = 0xF0222723;
    public static final int BORDER = 0xFF3A443D;
    public static final int BORDER_DARK = 0xFF050505;
    public static final int ACCENT = 0xFF72C900;
    public static final int ACCENT_DARK = 0xFF315D00;
    public static final int TEXT = 0xFFE8E8E8;
    public static final int MUTED = 0xFFA8A8A8;
    public static final int DANGER = 0xFFD94A32;
    public static final int SUCCESS = 0xFF68C987;
    public static final int ROW = 0xD01D221E;
    public static final int ROW_HOVER = 0xE02A322C;
    public static final int ROW_ACTIVE = 0xE0223B0A;
    public static final int GAP = 8;
    public static final int PADDING = 12;

    private GuiTheme() {
    }

    public static void panel(GuiGraphics graphics, Bounds bounds) {
        graphics.fill(bounds.x() + 2, bounds.y() + 2, bounds.right() + 2, bounds.bottom() + 2, 0x70000000);
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), BORDER_DARK);
        graphics.fill(bounds.x() + 1, bounds.y() + 1, bounds.right() - 1, bounds.bottom() - 1, BORDER);
        graphics.fill(bounds.x() + 2, bounds.y() + 2, bounds.right() - 2, bounds.bottom() - 2, PANEL);
        graphics.fill(bounds.x() + 2, bounds.y() + 2, bounds.right() - 2, bounds.y() + 3, 0xFF566159);
    }

    public static void section(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, BORDER_DARK);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL_DARK);
    }

    public static String ellipsize(Font font, String value, int width) {
        if (font.width(value) <= width) {
            return value;
        }
        return font.plainSubstrByWidth(value, Math.max(0, width - font.width("…"))) + "…";
    }

    public record Bounds(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < right() && mouseY >= y && mouseY < bottom();
        }
    }
}
