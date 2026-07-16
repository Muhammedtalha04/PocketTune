package com.pockettune.client.gui.panel;

import com.pockettune.client.gui.GuiTheme;
import com.pockettune.network.payload.ChangeSpeakerTrackPayload;
import com.pockettune.network.payload.SpeakerControlPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

public final class PlaybackControls {
    private static final int BUTTON_SIZE = 28;

    private final BlockPos speakerPos;
    private final UUID speakerInstanceId;
    private GuiTheme.Bounds bounds = new GuiTheme.Bounds(0, 0, 0, 0);
    private boolean seeking;
    private double seekRatio;

    public PlaybackControls(BlockPos speakerPos, UUID speakerInstanceId) {
        this.speakerPos = speakerPos;
        this.speakerInstanceId = speakerInstanceId;
    }

    public void setBounds(GuiTheme.Bounds bounds) {
        this.bounds = bounds;
    }

    public void render(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            boolean playing,
            boolean paused,
            long elapsedMillis,
            long durationMillis
    ) {
        GuiTheme.panel(graphics, bounds);
        int y = bounds.y() + (bounds.height() - BUTTON_SIZE) / 2;
        int x = bounds.x() + 10;
        String[] labels = {"|◀", paused || !playing ? "▶" : "Ⅱ", "■", "▶|"};
        for (int index = 0; index < labels.length; index++) {
            drawButton(graphics, mouseX, mouseY, x + index * (BUTTON_SIZE + 4), y, labels[index], index == 1 && playing);
        }

        int seekX = x + 4 * (BUTTON_SIZE + 4) + 8;
        int seekWidth = Math.max(40, bounds.right() - seekX - 12);
        int seekY = bounds.y() + bounds.height() / 2 - 3;
        double ratio = seeking ? seekRatio : durationMillis > 0L
                ? Math.max(0.0D, Math.min(1.0D, (double) elapsedMillis / durationMillis))
                : 0.0D;
        graphics.fill(seekX, seekY, seekX + seekWidth, seekY + 6, 0xFF070707);
        graphics.fill(seekX, seekY, seekX + (int) (seekWidth * ratio), seekY + 6, GuiTheme.ACCENT);
        graphics.fill(seekX + (int) (seekWidth * ratio) - 2, seekY - 2,
                seekX + (int) (seekWidth * ratio) + 3, seekY + 8, 0xFFE0E0E0);
        String time = NowPlayingPanel.formatTime((long) (ratio * durationMillis))
                + " / " + NowPlayingPanel.formatTime(durationMillis);
        graphics.drawCenteredString(Minecraft.getInstance().font, time, seekX + seekWidth / 2, seekY - 13, GuiTheme.MUTED);
    }

    public boolean mouseClicked(double mouseX, double mouseY, long durationMillis) {
        int y = bounds.y() + (bounds.height() - BUTTON_SIZE) / 2;
        int x = bounds.x() + 10;
        for (int index = 0; index < 4; index++) {
            int buttonX = x + index * (BUTTON_SIZE + 4);
            if (inside(mouseX, mouseY, buttonX, y, BUTTON_SIZE, BUTTON_SIZE)) {
                switch (index) {
                    case 0 -> PacketDistributor.sendToServer(
                            new ChangeSpeakerTrackPayload(speakerPos, speakerInstanceId, -1));
                    case 1 -> send(SpeakerControlPayload.Action.PLAY_PAUSE, 0.0D);
                    case 2 -> send(SpeakerControlPayload.Action.STOP, 0.0D);
                    case 3 -> PacketDistributor.sendToServer(
                            new ChangeSpeakerTrackPayload(speakerPos, speakerInstanceId, 1));
                    default -> {
                    }
                }
                return true;
            }
        }
        int seekX = x + 4 * (BUTTON_SIZE + 4) + 8;
        int seekWidth = Math.max(40, bounds.right() - seekX - 12);
        int seekY = bounds.y() + bounds.height() / 2 - 7;
        if (durationMillis > 0L && inside(mouseX, mouseY, seekX, seekY, seekWidth, 14)) {
            seeking = true;
            updateSeek(mouseX, seekX, seekWidth);
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mouseX) {
        if (!seeking) {
            return false;
        }
        int x = bounds.x() + 10;
        int seekX = x + 4 * (BUTTON_SIZE + 4) + 8;
        int seekWidth = Math.max(40, bounds.right() - seekX - 12);
        updateSeek(mouseX, seekX, seekWidth);
        return true;
    }

    public boolean mouseReleased(long durationMillis) {
        if (!seeking) {
            return false;
        }
        seeking = false;
        send(SpeakerControlPayload.Action.SEEK, seekRatio * durationMillis);
        return true;
    }

    private void updateSeek(double mouseX, int seekX, int seekWidth) {
        seekRatio = Math.max(0.0D, Math.min(1.0D, (mouseX - seekX) / seekWidth));
    }

    private static void drawButton(
            GuiGraphics graphics, int mouseX, int mouseY, int x, int y, String label, boolean active
    ) {
        boolean hovered = inside(mouseX, mouseY, x, y, BUTTON_SIZE, BUTTON_SIZE);
        graphics.fill(x, y, x + BUTTON_SIZE, y + BUTTON_SIZE,
                active ? GuiTheme.ACCENT_DARK : hovered ? GuiTheme.ROW_HOVER : GuiTheme.ROW);
        graphics.drawCenteredString(Minecraft.getInstance().font, label, x + BUTTON_SIZE / 2, y + 10, GuiTheme.TEXT);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private void send(SpeakerControlPayload.Action action, double value) {
        PacketDistributor.sendToServer(new SpeakerControlPayload(speakerPos, speakerInstanceId, action, value));
    }
}
