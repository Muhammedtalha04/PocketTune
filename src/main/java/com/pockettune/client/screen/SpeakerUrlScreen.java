package com.pockettune.client.screen;

import com.pockettune.block.entity.SpeakerBlockEntity;
import com.pockettune.client.gui.GuiTheme;
import com.pockettune.client.gui.panel.AudioSettingsPanel;
import com.pockettune.client.gui.panel.NowPlayingPanel;
import com.pockettune.client.gui.panel.PlaybackControls;
import com.pockettune.client.gui.panel.PlaylistPanel;
import com.pockettune.client.gui.panel.SidebarNavigation;
import com.pockettune.client.gui.widget.PocketTuneSlider;
import com.pockettune.model.SpeakerSettings;
import com.pockettune.model.TrackMetadata;
import com.pockettune.config.PocketTuneClientConfig;
import com.pockettune.config.PocketTuneServerConfig;
import com.pockettune.network.payload.SpeakerControlPayload;
import com.pockettune.network.payload.SpeakerOperationFeedbackPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.pockettune.util.SpeakerInstanceIdentity;

public final class SpeakerUrlScreen extends Screen {
    private static final int OUTER_MARGIN = 8;
    private static final int CONTROL_HEIGHT = 46;
    private static final int NOW_PLAYING_HEIGHT = 116;
    private static final int MIN_CENTER_WIDTH = 320;

    private final BlockPos speakerPos;
    private final UUID speakerInstanceId;
    private final SidebarNavigation sidebar = new SidebarNavigation();
    private final NowPlayingPanel nowPlaying = new NowPlayingPanel();
    private final PlaylistPanel playlist;
    private final AudioSettingsPanel settingsPanel;
    private final PlaybackControls controls;

    private PocketTuneSlider volumeSlider;
    private PocketTuneSlider rangeSlider;
    private PocketTuneSlider bassSlider;
    private PocketTuneSlider midSlider;
    private PocketTuneSlider trebleSlider;
    private PocketTuneSlider quickVolumeSlider;
    private GuiTheme.Bounds playlistBounds = new GuiTheme.Bounds(0, 0, 0, 0);
    private GuiTheme.Bounds compactContentBounds = new GuiTheme.Bounds(0, 0, 0, 0);
    private boolean compact;
    private String notification = "";
    private int notificationTicks;
    private long feedbackSequence;
    private SpeakerOperationFeedbackPayload.State notificationState =
            SpeakerOperationFeedbackPayload.State.SUCCESS;
    private boolean feedbackBaselineInitialized;
    private float fade = 1.0F;

    public SpeakerUrlScreen(BlockPos speakerPos, UUID speakerInstanceId) {
        super(Component.literal("PocketTune Speaker"));
        this.speakerPos = speakerPos.immutable();
        this.speakerInstanceId = speakerInstanceId;
        playlist = new PlaylistPanel(this.speakerPos, this.speakerInstanceId);
        settingsPanel = new AudioSettingsPanel(this.speakerPos, this.speakerInstanceId);
        controls = new PlaybackControls(this.speakerPos, this.speakerInstanceId);
    }

    public boolean displaysSpeaker(BlockPos pos) {
        return speakerPos.equals(pos);
    }

    @Override
    protected void init() {
        SpeakerBlockEntity currentSpeaker = speaker();
        if (!feedbackBaselineInitialized) {
            feedbackSequence = currentSpeaker == null ? 0L : currentSpeaker.getOperationFeedbackSequence();
            feedbackBaselineInitialized = true;
        }
        SpeakerSettings settings = currentSpeaker == null
                ? PocketTuneServerConfig.defaultSpeakerSettings()
                : currentSpeaker.getSettings();
        volumeSlider = addRenderableWidget(slider(0, 0, 100, 0, 100, settings.volumePercent(),
                value -> "Volume: " + Math.round(value) + "%", SpeakerControlPayload.Action.SET_VOLUME));
        rangeSlider = addRenderableWidget(slider(
                0,
                0,
                100,
                SpeakerSettings.MIN_RANGE,
                PocketTuneServerConfig.maximumRangeBlocks(),
                settings.rangeBlocks(),
                value -> "Range: " + Math.round(value) + " blocks", SpeakerControlPayload.Action.SET_RANGE));
        bassSlider = addRenderableWidget(eqSlider("Bass", settings.bassDb(), SpeakerControlPayload.Action.SET_BASS));
        midSlider = addRenderableWidget(eqSlider("Mid", settings.midDb(), SpeakerControlPayload.Action.SET_MID));
        trebleSlider = addRenderableWidget(eqSlider("Treble", settings.trebleDb(), SpeakerControlPayload.Action.SET_TREBLE));
        quickVolumeSlider = addRenderableWidget(slider(0, 0, 120, 0, 100, settings.volumePercent(),
                value -> "🔊 " + Math.round(value) + "%", SpeakerControlPayload.Action.SET_VOLUME));
        layout();
    }

    private PocketTuneSlider eqSlider(String name, double current, SpeakerControlPayload.Action action) {
        return slider(0, 0, 100, -12, 12, current,
                value -> name + ": " + String.format(Locale.ROOT, "%+.1f dB", value), action);
    }

    private PocketTuneSlider slider(
            int x,
            int y,
            int width,
            double minimum,
            double maximum,
            double current,
            java.util.function.DoubleFunction<String> formatter,
            SpeakerControlPayload.Action action
    ) {
        return new PocketTuneSlider(x, y, width, minimum, maximum, current, formatter,
                value -> PacketDistributor.sendToServer(
                        new SpeakerControlPayload(speakerPos, speakerInstanceId, action, value)));
    }

    private void layout() {
        int availableWidth = width - OUTER_MARGIN * 2;
        int availableHeight = height - OUTER_MARGIN * 2;
        int contentHeight = availableHeight - CONTROL_HEIGHT - GuiTheme.GAP;
        compact = width < 760 || height < 420;
        sidebar.setCompactMode(compact);
        if (compact) {
            int leftWidth = Math.min(128, Math.max(104, availableWidth / 4));
            int contentX = OUTER_MARGIN + leftWidth + GuiTheme.GAP;
            int contentWidth = availableWidth - leftWidth - GuiTheme.GAP;
            sidebar.setBounds(new GuiTheme.Bounds(OUTER_MARGIN, OUTER_MARGIN, leftWidth, availableHeight));
            compactContentBounds = new GuiTheme.Bounds(contentX, OUTER_MARGIN, contentWidth, contentHeight);
            nowPlaying.setBounds(compactContentBounds);
            playlistBounds = compactContentBounds;
            playlist.setBounds(compactContentBounds);
            AudioSettingsPanel.Mode mode = sidebar.selected() == SidebarNavigation.Tab.ADVANCED
                    ? AudioSettingsPanel.Mode.EQUALIZER
                    : AudioSettingsPanel.Mode.AUDIO;
            settingsPanel.setMode(mode);
            settingsPanel.setBounds(compactContentBounds);
            int quickWidth = contentWidth < 300 ? 0 : Math.min(118, contentWidth / 3);
            int controlY = OUTER_MARGIN + contentHeight + GuiTheme.GAP;
            controls.setBounds(new GuiTheme.Bounds(
                    contentX, controlY, contentWidth - quickWidth - GuiTheme.GAP, CONTROL_HEIGHT));
            setSettingsWidgetVisibility(mode);
            quickVolumeSlider.visible = quickWidth > 0;
            if (quickWidth > 0) {
                quickVolumeSlider.setX(contentX + contentWidth - quickWidth);
                quickVolumeSlider.setY(controlY + 13);
                quickVolumeSlider.setWidth(quickWidth);
            }
            settingsPanel.layoutSliders(volumeSlider, rangeSlider, bassSlider, midSlider, trebleSlider);
            return;
        }
        int leftWidth = Math.max(132, Math.min(178, availableWidth * 18 / 100));
        int rightWidth = Math.max(190, Math.min(238, availableWidth * 24 / 100));
        int centerWidth = availableWidth - leftWidth - rightWidth - GuiTheme.GAP * 2;
        if (centerWidth < MIN_CENTER_WIDTH) {
            int deficit = MIN_CENTER_WIDTH - centerWidth;
            leftWidth = Math.max(116, leftWidth - deficit / 2);
            rightWidth = Math.max(170, rightWidth - deficit + deficit / 2);
            centerWidth = availableWidth - leftWidth - rightWidth - GuiTheme.GAP * 2;
        }

        int leftX = OUTER_MARGIN;
        int centerX = leftX + leftWidth + GuiTheme.GAP;
        int rightX = centerX + centerWidth + GuiTheme.GAP;
        sidebar.setBounds(new GuiTheme.Bounds(leftX, OUTER_MARGIN, leftWidth, availableHeight));
        nowPlaying.setBounds(new GuiTheme.Bounds(centerX, OUTER_MARGIN, centerWidth, NOW_PLAYING_HEIGHT));
        playlistBounds = new GuiTheme.Bounds(
                centerX,
                OUTER_MARGIN + NOW_PLAYING_HEIGHT + GuiTheme.GAP,
                centerWidth,
                contentHeight - NOW_PLAYING_HEIGHT - GuiTheme.GAP
        );
        playlist.setBounds(playlistBounds);
        settingsPanel.setBounds(new GuiTheme.Bounds(rightX, OUTER_MARGIN, rightWidth, availableHeight));
        settingsPanel.setMode(AudioSettingsPanel.Mode.ALL);

        int controlY = OUTER_MARGIN + contentHeight + GuiTheme.GAP;
        // Geniş düzende ses ayarı sağ paneldeki slider'dadır; alt barda ikinci bir ses slider'ı gösterilmez.
        controls.setBounds(new GuiTheme.Bounds(centerX, controlY, centerWidth, CONTROL_HEIGHT));
        settingsPanel.layoutSliders(volumeSlider, rangeSlider, bassSlider, midSlider, trebleSlider);
        setSettingsWidgetVisibility(AudioSettingsPanel.Mode.ALL);
    }

    private void setSettingsWidgetVisibility(AudioSettingsPanel.Mode mode) {
        boolean settingsVisible = !compact
                || sidebar.selected() == SidebarNavigation.Tab.AUDIO
                || sidebar.selected() == SidebarNavigation.Tab.ADVANCED;
        volumeSlider.visible = settingsVisible && mode != AudioSettingsPanel.Mode.EQUALIZER;
        rangeSlider.visible = settingsVisible && mode != AudioSettingsPanel.Mode.EQUALIZER;
        bassSlider.visible = settingsVisible && mode != AudioSettingsPanel.Mode.AUDIO;
        midSlider.visible = settingsVisible && mode != AudioSettingsPanel.Mode.AUDIO;
        trebleSlider.visible = settingsVisible && mode != AudioSettingsPanel.Mode.AUDIO;
        quickVolumeSlider.visible = compact;
    }

    @Override
    public void tick() {
        super.tick();
        fade = Math.min(1.0F, fade + 0.12F);
        if (notificationTicks > 0) {
            notificationTicks--;
        }
        SpeakerBlockEntity speaker = speaker();
        if (speaker == null) {
            return;
        }
        if (speaker.getOperationFeedbackSequence() != feedbackSequence) {
            feedbackSequence = speaker.getOperationFeedbackSequence();
            notificationState = speaker.getOperationFeedbackState();
            notification = speaker.getOperationFeedbackMessage();
            notificationTicks = notificationState == SpeakerOperationFeedbackPayload.State.PENDING
                    ? -1
                    : PocketTuneClientConfig.NOTIFICATION_DURATION_SECONDS.get() * 20;
        }
        SpeakerSettings settings = speaker.getSettings();
        syncSlider(volumeSlider, settings.volumePercent());
        syncSlider(quickVolumeSlider, settings.volumePercent());
        syncSlider(rangeSlider, settings.rangeBlocks());
        syncSlider(bassSlider, settings.bassDb());
        syncSlider(midSlider, settings.midDb());
        syncSlider(trebleSlider, settings.trebleDb());
    }

    private static void syncSlider(PocketTuneSlider slider, double value) {
        if (!slider.isHoveredOrFocused()) {
            slider.setCurrentValue(value);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, GuiTheme.BACKGROUND);
        SpeakerBlockEntity speaker = speaker();
        List<TrackMetadata> queue = speaker == null ? List.of() : speaker.getPlaylistQueue();
        int index = speaker == null ? 0 : speaker.getPlaylistIndex();
        TrackMetadata track = speaker == null ? null : speaker.getActiveTrack();
        SpeakerSettings settings = speaker == null
                ? PocketTuneServerConfig.defaultSpeakerSettings()
                : speaker.getSettings();
        boolean playing = speaker != null && speaker.isPlaying();
        boolean paused = speaker != null && speaker.isPaused();
        long elapsed = speaker == null ? 0L : speaker.getElapsedMillis();
        long duration = track == null ? 0L : track.durationMillis();

        sidebar.render(graphics, mouseX, mouseY, playing,
                new SidebarNavigation.BlockStatus(
                        minecraft != null && minecraft.level != null
                                && minecraft.level.hasChunk(speakerPos.getX() >> 4, speakerPos.getZ() >> 4)
                                ? "Loaded" : "Not Loaded",
                        speaker == null ? "Stopped" : speaker.getPlaybackStatus(),
                        "Position: " + speakerPos.getX() + ", " + speakerPos.getY() + ", " + speakerPos.getZ()
                ));
        playlist.update(queue, index);
        if (!compact) {
            nowPlaying.render(graphics, track, playing, paused, queue.size() > 1);
            playlist.render(graphics, mouseX, mouseY, settings, this::openAddUrl);
            settingsPanel.render(graphics, mouseX, mouseY, settings);
        } else {
            switch (sidebar.selected()) {
                case NOW_PLAYING -> nowPlaying.render(
                        graphics, track, playing, paused, queue.size() > 1);
                case PLAYLIST -> playlist.render(graphics, mouseX, mouseY, settings, this::openAddUrl);
                case AUDIO, ADVANCED -> settingsPanel.render(graphics, mouseX, mouseY, settings);
                case INFO -> renderInfo(graphics);
            }
        }
        controls.render(graphics, mouseX, mouseY, playing, paused, elapsed, duration);
        super.render(graphics, mouseX, mouseY, partialTick);

        if (fade < 1.0F) {
            int alpha = (int) ((1.0F - fade) * 100.0F);
            graphics.fill(playlistBounds.x(), playlistBounds.y(), playlistBounds.right(), playlistBounds.bottom(), alpha << 24);
        }
        if (PocketTuneClientConfig.SHOW_GUI_NOTIFICATIONS.get()
                && notificationTicks != 0
                && !notification.isBlank()) {
            renderNotification(graphics);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // PocketTune kendi opak panel arka planını çizer; vanilla blur GUI'yi bulanıklaştırmamalıdır.
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0 && sidebar.mouseClicked(mouseX, mouseY) == SidebarNavigation.Interaction.TAB_SELECTED) {
            fade = 0.0F;
            layout();
            return true;
        }
        SpeakerBlockEntity speaker = speaker();
        SpeakerSettings settings = speaker == null
                ? PocketTuneServerConfig.defaultSpeakerSettings()
                : speaker.getSettings();
        boolean playlistVisible = !compact || sidebar.selected() == SidebarNavigation.Tab.PLAYLIST;
        if (playlistVisible && playlist.mouseClicked(mouseX, mouseY, button, settings,
                this::openAddUrl)) {
            return true;
        }
        boolean nowPlayingVisible = !compact || sidebar.selected() == SidebarNavigation.Tab.NOW_PLAYING;
        if (button == 0 && nowPlayingVisible && nowPlaying.mouseClicked(mouseX, mouseY)) {
            if (minecraft != null) {
                minecraft.setScreen(new UrlEntryScreen(this, speakerPos, speakerInstanceId, false));
            }
            return true;
        }
        boolean settingsVisible = !compact || sidebar.selected() == SidebarNavigation.Tab.AUDIO
                || sidebar.selected() == SidebarNavigation.Tab.ADVANCED;
        if (button == 0 && settingsVisible && settingsPanel.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        long duration = speaker == null || speaker.getActiveTrack() == null
                ? 0L : speaker.getActiveTrack().durationMillis();
        return button == 0 && controls.mouseClicked(mouseX, mouseY, duration);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        boolean playlistVisible = !compact || sidebar.selected() == SidebarNavigation.Tab.PLAYLIST;
        if (button == 0 && ((playlistVisible && playlist.mouseDragged(mouseY)) || controls.mouseDragged(mouseX))) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        SpeakerBlockEntity speaker = speaker();
        long duration = speaker == null || speaker.getActiveTrack() == null
                ? 0L : speaker.getActiveTrack().durationMillis();
        boolean playlistVisible = !compact || sidebar.selected() == SidebarNavigation.Tab.PLAYLIST;
        if (button == 0 && ((playlistVisible && playlist.mouseReleased()) || controls.mouseReleased(duration))) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        boolean playlistVisible = !compact || sidebar.selected() == SidebarNavigation.Tab.PLAYLIST;
        return playlistVisible && playlist.mouseScrolled(mouseX, mouseY, scrollY)
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void renderInfo(GuiGraphics graphics) {
        GuiTheme.panel(graphics, compactContentBounds);
        int x = compactContentBounds.x() + 14;
        int y = compactContentBounds.y() + 16;
        graphics.drawString(font, "POCKETTUNE 0.7.1", x, y, GuiTheme.ACCENT, false);
        graphics.drawString(font, "NeoForge 1.21.4 media speaker", x, y + 18, GuiTheme.TEXT, false);
        graphics.drawString(font, "• Secure playlist resolution through yt-dlp", x, y + 38, GuiTheme.MUTED, false);
        graphics.drawString(font, "• Real-time control through mpv IPC", x, y + 52, GuiTheme.MUTED, false);
        graphics.drawString(font, "• Server-authoritative multiplayer state", x, y + 66, GuiTheme.MUTED, false);
        graphics.drawString(font, "ESC closes the interface; music continues", x, y + 90, GuiTheme.TEXT, false);
    }

    private void openAddUrl() {
        if (minecraft != null) {
            minecraft.setScreen(new UrlEntryScreen(this, speakerPos, speakerInstanceId, true));
        }
    }

    private void renderNotification(GuiGraphics graphics) {
        int maximumWidth = Math.max(120, width - 32);
        String visibleMessage = GuiTheme.ellipsize(font, notification, maximumWidth - 32);
        String icon = switch (notificationState) {
            case PENDING -> "…";
            case SUCCESS -> "✓";
            case ERROR -> "!";
        };
        int messageWidth = Math.min(maximumWidth, font.width(visibleMessage) + 32);
        int x = (width - messageWidth) / 2;
        int y = 12;
        int color = switch (notificationState) {
            case PENDING -> GuiTheme.ACCENT;
            case SUCCESS -> GuiTheme.SUCCESS;
            case ERROR -> GuiTheme.DANGER;
        };
        graphics.fill(x + 2, y + 2, x + messageWidth + 2, y + 24, 0x80000000);
        graphics.fill(x, y, x + messageWidth, y + 24, GuiTheme.PANEL_RAISED);
        graphics.fill(x, y, x + 3, y + 24, color);
        graphics.drawString(font, icon, x + 10, y + 8, color, false);
        graphics.drawString(font, visibleMessage, x + 22, y + 8, GuiTheme.TEXT, false);
    }

    private SpeakerBlockEntity speaker() {
        if (minecraft == null || minecraft.level == null) {
            return null;
        }
        if (!(minecraft.level.getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker)) {
            return null;
        }
        return SpeakerInstanceIdentity.matches(speaker.getSpeakerInstanceId(), speakerInstanceId)
                ? speaker
                : null;
    }

    @Override
    public boolean isPauseScreen() {
        // Tek oyunculuda GUI açıkken oyun/elapsed akmaya devam etmeli; ses de duraklamadığı için tutarlıdır.
        return false;
    }
}
