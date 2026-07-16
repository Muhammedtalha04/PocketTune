package com.pockettune.client.gui.panel;

import com.pockettune.client.gui.GuiTheme;
import com.pockettune.client.gui.widget.PocketTuneSlider;
import com.pockettune.model.SpeakerSettings;
import com.pockettune.network.payload.SpeakerControlPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

public final class AudioSettingsPanel {
    private final BlockPos speakerPos;
    private final UUID speakerInstanceId;
    private GuiTheme.Bounds bounds = new GuiTheme.Bounds(0, 0, 0, 0);
    private Mode mode = Mode.ALL;

    public AudioSettingsPanel(BlockPos speakerPos, UUID speakerInstanceId) {
        this.speakerPos = speakerPos;
        this.speakerInstanceId = speakerInstanceId;
    }

    public void setBounds(GuiTheme.Bounds bounds) {
        this.bounds = bounds;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public void layoutSliders(
            PocketTuneSlider volume,
            PocketTuneSlider range,
            PocketTuneSlider bass,
            PocketTuneSlider mid,
            PocketTuneSlider treble
    ) {
        int x = bounds.x() + 12;
        int width = bounds.width() - 24;
        if (mode == Mode.EQUALIZER) {
            place(bass, x, bounds.y() + 42, width);
            place(mid, x, bounds.y() + 90, width);
            place(treble, x, bounds.y() + 138, width);
        } else {
            place(volume, x, bounds.y() + 42, width);
            place(range, x, bounds.y() + (mode == Mode.AUDIO ? 94 : 104), width);
            if (mode == Mode.ALL) {
                place(bass, x, bounds.y() + 254, width);
                place(mid, x, bounds.y() + 302, width);
                place(treble, x, bounds.y() + 350, width);
            }
        }
    }

    private static void place(PocketTuneSlider slider, int x, int y, int width) {
        slider.setX(x);
        slider.setY(y);
        slider.setWidth(width);
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, SpeakerSettings settings) {
        GuiTheme.panel(graphics, bounds);
        Font font = Minecraft.getInstance().font;
        int x = bounds.x() + 12;
        int width = bounds.width() - 24;
        if (mode == Mode.EQUALIZER) {
            graphics.drawString(font, "GELİŞMİŞ / EKOLAYZIR", x, bounds.y() + 12, GuiTheme.ACCENT, false);
            graphics.drawString(font, "Bass", x, bounds.y() + 29, GuiTheme.TEXT, false);
            graphics.drawString(font, "Mid", x, bounds.y() + 77, GuiTheme.TEXT, false);
            graphics.drawString(font, "Tiz", x, bounds.y() + 125, GuiTheme.TEXT, false);
            return;
        }
        graphics.drawString(font, "SES AYARLARI", x, bounds.y() + 12, GuiTheme.ACCENT, false);
        graphics.drawString(font, "Ses Seviyesi", x, bounds.y() + 29, GuiTheme.TEXT, false);
        graphics.drawString(font, "MENZİL AYARLARI", x, bounds.y() + 76, GuiTheme.ACCENT, false);
        graphics.drawString(font, "Yayılma Mesafesi", x, bounds.y() + (mode == Mode.AUDIO ? 81 : 91), GuiTheme.TEXT, false);

        int fadeY = bounds.y() + (mode == Mode.AUDIO ? 120 : 135);
        drawToggle(graphics, mouseX, mouseY, x, fadeY, width,
                "Azalma: " + settings.fadeType().displayName(), true);
        drawToggle(graphics, mouseX, mouseY, x, fadeY + 25, width,
                "Duvar Engellemesi: " + (settings.wallOcclusion() ? "Açık" : "Kapalı"), settings.wallOcclusion());

        // Karıştır/Tekrar kontrolleri yalnız playlist alt barındadır; burada tekrarlanmaz.
        if (mode == Mode.ALL) {
            graphics.drawString(font, "EKOLAYZIR", x, bounds.y() + 205, GuiTheme.ACCENT, false);
            graphics.drawString(font, "Bass", x, bounds.y() + 238, GuiTheme.TEXT, false);
            graphics.drawString(font, "Mid", x, bounds.y() + 286, GuiTheme.TEXT, false);
            graphics.drawString(font, "Tiz", x, bounds.y() + 334, GuiTheme.TEXT, false);
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY) {
        if (mode == Mode.EQUALIZER) {
            return false;
        }
        int x = bounds.x() + 12;
        int width = bounds.width() - 24;
        int fadeY = bounds.y() + (mode == Mode.AUDIO ? 120 : 135);
        if (inside(mouseX, mouseY, x, fadeY, width, 20)) {
            send(SpeakerControlPayload.Action.CYCLE_FADE);
            return true;
        }
        if (inside(mouseX, mouseY, x, fadeY + 25, width, 20)) {
            send(SpeakerControlPayload.Action.TOGGLE_OCCLUSION);
            return true;
        }
        return false;
    }

    private static void drawToggle(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            int x,
            int y,
            int width,
            String label,
            boolean enabled
    ) {
        boolean hovered = inside(mouseX, mouseY, x, y, width, 20);
        graphics.fill(x, y, x + width, y + 20,
                enabled ? GuiTheme.ACCENT_DARK : hovered ? GuiTheme.ROW_HOVER : GuiTheme.ROW);
        graphics.drawCenteredString(Minecraft.getInstance().font, label, x + width / 2, y + 6, GuiTheme.TEXT);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private void send(SpeakerControlPayload.Action action) {
        PacketDistributor.sendToServer(
                new SpeakerControlPayload(speakerPos, speakerInstanceId, action, 0.0D));
    }

    public enum Mode {
        ALL,
        AUDIO,
        EQUALIZER
    }
}
