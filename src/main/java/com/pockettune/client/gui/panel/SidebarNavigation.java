package com.pockettune.client.gui.panel;

import com.pockettune.client.gui.GuiTheme;
import com.pockettune.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

public final class SidebarNavigation {
    private static final int TAB_HEIGHT = 22;

    private GuiTheme.Bounds bounds = new GuiTheme.Bounds(0, 0, 0, 0);
    private Tab selected = Tab.NOW_PLAYING;
    private boolean compactMode;

    public void setBounds(GuiTheme.Bounds bounds) {
        this.bounds = bounds;
    }

    public void setCompactMode(boolean compactMode) {
        this.compactMode = compactMode;
    }

    public Tab selected() {
        return selected;
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, boolean active, BlockStatus status) {
        GuiTheme.panel(graphics, bounds);
        int centerX = bounds.x() + bounds.width() / 2;
        graphics.pose().pushPose();
        graphics.pose().translate(centerX - (compactMode ? 16 : 24), bounds.y() + (compactMode ? 8 : 14), 0);
        graphics.pose().scale(compactMode ? 2.0F : 3.0F, compactMode ? 2.0F : 3.0F, 1.0F);
        graphics.renderItem(new ItemStack(ModItems.SPEAKER.get()), 0, 0);
        graphics.pose().popPose();

        graphics.drawCenteredString(Minecraft.getInstance().font, "PocketTune", centerX,
                bounds.y() + (compactMode ? 43 : 68), GuiTheme.ACCENT);
        if (!compactMode) {
            graphics.drawCenteredString(Minecraft.getInstance().font, "HOPARLÖR", centerX,
                    bounds.y() + 80, GuiTheme.MUTED);
        }

        if (compactMode) {
            renderTabs(graphics, mouseX, mouseY);
            graphics.drawCenteredString(Minecraft.getInstance().font,
                    active ? "● " + status.playbackState() : "● Durdu",
                    centerX, bounds.bottom() - 16, active ? GuiTheme.ACCENT : GuiTheme.DANGER);
            return;
        }

        // Geniş düzende hızlı eylem tuşları kaldırıldı; tüm işlevler ilgili panellerde tek yerden sunulur.
        int statusY = bounds.y() + 100;
        GuiTheme.section(graphics, bounds.x() + 8, statusY, bounds.width() - 16, 72);
        graphics.drawString(Minecraft.getInstance().font, active ? "● Hoparlör Aktif" : "● Hoparlör Durdu",
                bounds.x() + 15, statusY + 8, active ? GuiTheme.ACCENT : GuiTheme.DANGER, false);
        graphics.drawString(Minecraft.getInstance().font, "Chunk: " + status.chunkState(),
                bounds.x() + 15, statusY + 24, GuiTheme.MUTED, false);
        graphics.drawString(Minecraft.getInstance().font, "Durum: " + status.playbackState(),
                bounds.x() + 15, statusY + 40, GuiTheme.MUTED, false);
        graphics.drawString(Minecraft.getInstance().font,
                GuiTheme.ellipsize(Minecraft.getInstance().font, status.position(), bounds.width() - 30),
                bounds.x() + 15, statusY + 56, GuiTheme.MUTED, false);
    }

    private void renderTabs(GuiGraphics graphics, int mouseX, int mouseY) {
        int tabY = bounds.y() + 60;
        for (Tab tab : Tab.values()) {
            boolean hovered = inside(mouseX, mouseY, tabY, TAB_HEIGHT);
            int color = tab == selected ? GuiTheme.ROW_ACTIVE : hovered ? GuiTheme.ROW_HOVER : GuiTheme.ROW;
            graphics.fill(bounds.x() + 8, tabY, bounds.right() - 8, tabY + TAB_HEIGHT, color);
            if (tab == selected) {
                graphics.fill(bounds.x() + 8, tabY, bounds.x() + 11, tabY + TAB_HEIGHT, GuiTheme.ACCENT);
            }
            graphics.drawString(Minecraft.getInstance().font, tab.label, bounds.x() + 17,
                    tabY + 7, GuiTheme.TEXT, false);
            tabY += TAB_HEIGHT + 2;
        }
    }

    public Interaction mouseClicked(double mouseX, double mouseY) {
        if (compactMode) {
            int tabY = bounds.y() + 60;
            for (Tab tab : Tab.values()) {
                if (inside(mouseX, mouseY, tabY, TAB_HEIGHT)) {
                    selected = tab;
                    return Interaction.TAB_SELECTED;
                }
                tabY += TAB_HEIGHT + 2;
            }
        }
        return Interaction.NONE;
    }

    private boolean inside(double mouseX, double mouseY, int y, int height) {
        return mouseX >= bounds.x() + 8 && mouseX < bounds.right() - 8
                && mouseY >= y && mouseY < y + height;
    }

    public enum Tab {
        NOW_PLAYING("Şu An Çalıyor"),
        PLAYLIST("Playlist"),
        AUDIO("Ses Ayarları"),
        ADVANCED("Gelişmiş"),
        INFO("Bilgi");

        private final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    public enum Interaction {
        NONE,
        TAB_SELECTED
    }

    public record BlockStatus(String chunkState, String playbackState, String position) {
    }
}
