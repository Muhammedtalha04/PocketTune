package com.pockettune.client.gui.overlay;

import com.pockettune.client.audio.PortableSpeakerPlaybackManager;
import com.pockettune.client.gui.GuiTheme;
import com.pockettune.client.gui.ThumbnailCache;
import com.pockettune.config.PocketTuneClientConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.client.extensions.common.IClientMobEffectExtensions;

import java.util.Locale;

public final class PortableMusicOverlay {
    private static final int PANEL_BACKGROUND = 0xE6121814;
    private static final int PANEL_BORDER = 0xD05A665D;
    private static final int PANEL_HIGHLIGHT = 0x806D7A70;
    private static final int PROGRESS_TRACK = 0xFF303A33;

    private PortableMusicOverlay() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!PortableMusicOverlayVisibility.shouldRender(
                PocketTuneClientConfig.SHOW_PORTABLE_HUD.get(),
                minecraft.options.hideGui,
                PocketTuneClientConfig.SHOW_PORTABLE_OVERLAY_WHEN_HUD_HIDDEN.get(),
                minecraft.screen != null,
                minecraft.level != null && minecraft.player != null
        )) {
            return;
        }
        PortableSpeakerPlaybackManager.OverlaySnapshot snapshot =
                PortableSpeakerPlaybackManager.currentOverlaySnapshot().orElse(null);
        if (snapshot == null) {
            return;
        }

        PortableMusicOverlayLayout.Layout layout = PortableMusicOverlayLayout.calculate(
                graphics.guiWidth(),
                graphics.guiHeight(),
                statusEffectTopInset(minecraft)
        );
        renderPanel(graphics, minecraft.font, layout, snapshot);
    }

    private static void renderPanel(
            GuiGraphics graphics,
            Font font,
            PortableMusicOverlayLayout.Layout layout,
            PortableSpeakerPlaybackManager.OverlaySnapshot snapshot
    ) {
        PortableMusicOverlayLayout.Rect panel = layout.panel();
        fillChamfered(graphics, panel.x() + 2, panel.y() + 3, panel.width(), panel.height(), 0x78000000);
        fillChamfered(graphics, panel.x(), panel.y(), panel.width(), panel.height(), PANEL_BORDER);
        fillChamfered(graphics, panel.x() + 1, panel.y() + 1,
                Math.max(0, panel.width() - 2), Math.max(0, panel.height() - 2), PANEL_BACKGROUND);
        graphics.fill(panel.x() + 3, panel.y() + 2, panel.right() - 3, panel.y() + 3, PANEL_HIGHLIGHT);

        PortableMusicOverlayLayout.Rect cover = layout.cover();
        graphics.fill(cover.x() + 1, cover.y() + 2, cover.right() + 2, cover.bottom() + 2, 0x70000000);
        ThumbnailCache.Entry thumbnail = ThumbnailCache.instance().get(snapshot.track());
        thumbnail.renderCover(graphics, cover.x(), cover.y(), cover.width());
        graphics.renderOutline(cover.x() - 1, cover.y() - 1,
                cover.width() + 2, cover.height() + 2, 0xFF465149);
        if (thumbnail.location() == null) {
            graphics.drawCenteredString(font, "♪", cover.x() + cover.width() / 2,
                    cover.y() + Math.max(1, (cover.height() - font.lineHeight) / 2), GuiTheme.MUTED);
        }

        StatusStyle status = statusStyle(snapshot.playbackState());
        PortableMusicOverlayLayout.Rect icon = layout.statusIcon();
        if (icon.width() > 0 && icon.height() > 0) {
            fillChamfered(graphics, icon.x(), icon.y(), icon.width(), icon.height(), status.background());
            graphics.drawCenteredString(font, status.icon(), icon.x() + icon.width() / 2,
                    icon.y() + Math.max(1, (icon.height() - font.lineHeight) / 2), status.color());
        }

        PortableMusicOverlayLayout.Rect title = layout.title();
        graphics.drawString(font, ellipsize(font, snapshot.track().title(), title.width()),
                title.x(), title.y(), GuiTheme.TEXT, false);
        PortableMusicOverlayLayout.Rect artist = layout.artist();
        graphics.drawString(font, ellipsize(font, snapshot.track().artist(), artist.width()),
                artist.x(), artist.y(), GuiTheme.MUTED, false);

        if (layout.showTime()) {
            PortableMusicOverlayLayout.Rect time = layout.time();
            String statusText = status.label();
            String timeText = formatTime(snapshot.elapsedMillis()) + " / " + formatDuration(snapshot.durationMillis());
            String visibleTimeText = ellipsize(font, timeText, time.width());
            int visibleTimeWidth = font.width(visibleTimeText);
            PortableMusicOverlayLayout.TimeRowAllocation allocation =
                    PortableMusicOverlayLayout.allocateTimeRow(
                            time.width(),
                            visibleTimeWidth,
                            font.width(statusText),
                            6,
                            font.width("…")
                    );
            if (allocation.showStatus()) {
                graphics.drawString(font, ellipsize(font, statusText, allocation.statusWidth()),
                        time.x(), time.y(), status.color(), false);
            }
            graphics.drawString(font, visibleTimeText,
                    time.x() + allocation.timeOffset(), time.y(), 0xFFCBD2CD, false);
        }

        PortableMusicOverlayLayout.Rect progress = layout.progress();
        graphics.fill(progress.x(), progress.y(), progress.right(), progress.bottom(), PROGRESS_TRACK);
        if (snapshot.durationMillis() > 0L) {
            int fillWidth = PortableMusicOverlayLayout.progressFillWidth(
                    progress.width(), snapshot.elapsedMillis(), snapshot.durationMillis());
            graphics.fill(progress.x(), progress.y(), progress.x() + fillWidth, progress.bottom(), status.color());
        } else if (progress.width() > 4) {
            int segmentWidth = Math.max(4, progress.width() / 4);
            int travel = Math.max(1, progress.width() - segmentWidth);
            int offset = (int) ((snapshot.elapsedMillis() / 50L) % (travel * 2L));
            if (offset > travel) {
                offset = travel * 2 - offset;
            }
            graphics.fill(progress.x() + offset, progress.y(),
                    progress.x() + offset + segmentWidth, progress.bottom(), status.color());
        }
    }

    private static int statusEffectTopInset(Minecraft minecraft) {
        if (minecraft.options.hideGui || minecraft.player == null) {
            return 0;
        }
        boolean beneficialEffectVisible = false;
        boolean harmfulEffectVisible = false;
        for (MobEffectInstance effect : minecraft.player.getActiveEffects()) {
            if (!effect.showIcon()
                    || !IClientMobEffectExtensions.of(effect).isVisibleInGui(effect)) {
                continue;
            }
            if (effect.getEffect().value().isBeneficial()) {
                beneficialEffectVisible = true;
            } else {
                harmfulEffectVisible = true;
            }
            if (beneficialEffectVisible && harmfulEffectVisible) {
                break;
            }
        }
        return PortableMusicOverlayLayout.topInsetForEffectRows(
                beneficialEffectVisible,
                harmfulEffectVisible,
                minecraft.isDemo()
        );
    }

    private static StatusStyle statusStyle(PortableSpeakerPlaybackManager.OverlayPlaybackState state) {
        return switch (state) {
            case PLAYING -> new StatusStyle("▶", "Çalıyor", GuiTheme.ACCENT, 0xB0263D16);
            case PAUSED -> new StatusStyle("Ⅱ", "Duraklatıldı", 0xFFFFC857, 0xB0443515);
            case BUFFERING -> new StatusStyle("…", "Hazırlanıyor", 0xFF75C9E8, 0xB0183540);
            case ERROR -> new StatusStyle("!", "Oynatma hatası", 0xFFFF6A5E, 0xB0451C18);
        };
    }

    private static void fillChamfered(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int height,
            int color
    ) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (width <= 4 || height <= 4) {
            graphics.fill(x, y, x + width, y + height, color);
            return;
        }
        graphics.fill(x + 2, y, x + width - 2, y + height, color);
        graphics.fill(x, y + 2, x + width, y + height - 2, color);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, color);
    }

    private static String ellipsize(Font font, String value, int width) {
        if (width <= 0) {
            return "";
        }
        if (font.width(value) <= width) {
            return value;
        }
        String suffix = "…";
        return font.plainSubstrByWidth(value, Math.max(0, width - font.width(suffix))) + suffix;
    }

    private static String formatDuration(long millis) {
        return millis <= 0L ? "--:--" : formatTime(millis);
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1_000L;
        return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    private record StatusStyle(String icon, String label, int color, int background) {
    }
}
