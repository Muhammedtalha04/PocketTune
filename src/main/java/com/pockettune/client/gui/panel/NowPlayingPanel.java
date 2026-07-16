package com.pockettune.client.gui.panel;

import com.pockettune.client.gui.GuiTheme;
import com.pockettune.client.gui.ThumbnailCache;
import com.pockettune.model.TrackMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Locale;

public final class NowPlayingPanel {
    private GuiTheme.Bounds bounds = new GuiTheme.Bounds(0, 0, 0, 0);

    public void setBounds(GuiTheme.Bounds bounds) {
        this.bounds = bounds;
    }

    public void render(
            GuiGraphics graphics,
            TrackMetadata track,
            boolean playing,
            boolean paused,
            boolean playlistSource
    ) {
        GuiTheme.panel(graphics, bounds);
        Font font = Minecraft.getInstance().font;
        int buttonWidth = 86;
        graphics.fill(bounds.right() - buttonWidth - 8, bounds.y() + 8, bounds.right() - 8, bounds.y() + 26, GuiTheme.ROW);
        graphics.drawCenteredString(font, "Change URL", bounds.right() - buttonWidth / 2 - 8,
                bounds.y() + 13, GuiTheme.TEXT);
        if (track == null) {
            graphics.drawCenteredString(font, "Add a YouTube URL to start playing",
                    bounds.x() + bounds.width() / 2, bounds.y() + bounds.height() / 2 - 4, GuiTheme.MUTED);
            return;
        }

        int coverSize = Math.min(86, bounds.height() - 32);
        int coverX = bounds.x() + GuiTheme.PADDING;
        int coverY = bounds.y() + GuiTheme.PADDING;
        ThumbnailCache.instance().get(track).render(graphics, coverX, coverY, coverSize, coverSize);
        graphics.renderOutline(coverX - 1, coverY - 1, coverSize + 2, coverSize + 2, GuiTheme.BORDER);

        int textX = coverX + coverSize + 12;
        int textWidth = bounds.right() - textX - GuiTheme.PADDING;
        graphics.drawString(font, paused ? "PAUSED" : playing ? "NOW PLAYING" : "STOPPED",
                textX, coverY + 1, paused ? 0xFFFFC857 : GuiTheme.ACCENT, false);
        graphics.drawString(font, GuiTheme.ellipsize(font, track.title(), textWidth),
                textX, coverY + 18, GuiTheme.TEXT, false);
        graphics.drawString(font, GuiTheme.ellipsize(font, track.artist(), textWidth),
                textX, coverY + 32, GuiTheme.MUTED, false);
        graphics.drawString(font, "YouTube • " + (playlistSource ? "Playlist" : "Video"),
                textX, coverY + 48, GuiTheme.MUTED, false);
        // İlerleme/süre yalnız alt bardaki interaktif seek çubuğunda gösterilir; burada tekrarlanmaz.
        if (track.durationMillis() > 0L) {
            graphics.drawString(font, "Duration: " + formatTime(track.durationMillis()),
                    textX, coverY + 64, GuiTheme.MUTED, false);
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY) {
        int buttonWidth = 86;
        return mouseX >= bounds.right() - buttonWidth - 8 && mouseX < bounds.right() - 8
                && mouseY >= bounds.y() + 8 && mouseY < bounds.y() + 26;
    }

    public static String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis / 1_000L);
        return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }
}
