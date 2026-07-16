package com.pockettune.client.screen;

import com.pockettune.audio.ExternalProcessException;
import com.pockettune.audio.PlaybackFailureMessages;
import com.pockettune.audio.YtDlpResolver;
import com.pockettune.client.gui.GuiTheme;
import com.pockettune.network.payload.AddSpeakerUrlPayload;
import com.pockettune.network.payload.SetSpeakerUrlPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

public final class UrlEntryScreen extends Screen {
    private static final int PANEL_WIDTH = 420;

    private final Screen parent;
    private final BlockPos speakerPos;
    private final UUID speakerInstanceId;
    private final boolean append;
    private EditBox urlInput;
    private String error = "";

    public UrlEntryScreen(Screen parent, BlockPos speakerPos, UUID speakerInstanceId, boolean append) {
        super(Component.literal(append ? "Add to Playlist" : "Change URL"));
        this.parent = parent;
        this.speakerPos = speakerPos;
        this.speakerInstanceId = speakerInstanceId;
        this.append = append;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 40);
        int left = (width - panelWidth) / 2;
        int top = height / 2 - 55;
        urlInput = new EditBox(font, left + 12, top + 34, panelWidth - 24, 20,
                Component.literal("YouTube video/playlist URL"));
        urlInput.setMaxLength(SetSpeakerUrlPayload.MAX_URL_LENGTH);
        addRenderableWidget(urlInput);
        addRenderableWidget(Button.builder(Component.literal(append ? "Add to Queue" : "Play / Replace"), button -> submit())
                .bounds(left + 12, top + 66, (panelWidth - 30) / 2, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(left + 18 + (panelWidth - 30) / 2, top + 66, (panelWidth - 30) / 2, 20)
                .build());
        setInitialFocus(urlInput);
    }

    private void submit() {
        try {
            String url = YtDlpResolver.validateYoutubeUrl(urlInput.getValue());
            if (append) {
                PacketDistributor.sendToServer(new AddSpeakerUrlPayload(speakerPos, speakerInstanceId, url));
            } else {
                PacketDistributor.sendToServer(new SetSpeakerUrlPayload(speakerPos, speakerInstanceId, url));
            }
            onClose();
        } catch (ExternalProcessException exception) {
            error = PlaybackFailureMessages.forUrlInput(exception);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xD0000000);
        int panelWidth = Math.min(PANEL_WIDTH, width - 40);
        int left = (width - panelWidth) / 2;
        int top = height / 2 - 55;
        GuiTheme.panel(graphics, new GuiTheme.Bounds(left, top, panelWidth, 104));
        graphics.drawCenteredString(font, title, width / 2, top + 12, GuiTheme.ACCENT);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!error.isBlank()) {
            graphics.drawCenteredString(font, error, width / 2, top + 90, GuiTheme.DANGER);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Parent PocketTune ekranının net görünümünü korumak için vanilla blur devre dışıdır.
    }

    @Override
    public boolean isPauseScreen() {
        // Ana PocketTune ekranıyla aynı davranış: oyun ve ses akmaya devam eder.
        return false;
    }
}
