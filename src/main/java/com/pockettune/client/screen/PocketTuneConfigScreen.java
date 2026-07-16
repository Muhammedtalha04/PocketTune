package com.pockettune.client.screen;

import com.pockettune.client.gui.GuiTheme;
import com.pockettune.client.gui.ThumbnailCache;
import com.pockettune.client.debug.BlockInteractionDebugTracker;
import com.pockettune.config.PocketTuneClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class PocketTuneConfigScreen extends Screen {
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_GAP = 5;
    private static final int MIN_ROW_GAP = 3;
    private static final int GENERAL_BUTTON_COUNT = 12;

    private final Screen parent;
    private final List<Button> generalButtons = new ArrayList<>();
    private final List<Button> testButtons = new ArrayList<>();
    private final List<Button> blockDebugButtons = new ArrayList<>();
    private Page page = Page.GENERAL;
    private int settingsStartY = 68;
    private int settingsColumns = 2;
    private int settingsColumnGap = 8;
    private int settingsRowStep = BUTTON_HEIGHT + ROW_GAP;

    public PocketTuneConfigScreen(Screen parent) {
        super(Component.literal("PocketTune Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        generalButtons.clear();
        testButtons.clear();
        blockDebugButtons.clear();
        int panelWidth = Math.max(1, Math.min(720, width - 24));
        int panelX = (width - panelWidth) / 2;
        configureSettingsGrid(panelWidth);
        int tabGap = 6;
        int tabWidth = (panelWidth - tabGap * 2) / 3;
        addRenderableWidget(Button.builder(Component.literal("General & Audio"), button -> selectPage(Page.GENERAL))
                .bounds(panelX, 30, tabWidth, BUTTON_HEIGHT)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Test Mode"), button -> selectPage(Page.TEST))
                .bounds(panelX + tabWidth + tabGap, 30, tabWidth, BUTTON_HEIGHT)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Block Debug"), button -> selectPage(Page.BLOCK_DEBUG))
                .bounds(panelX + (tabWidth + tabGap) * 2, 30,
                        panelWidth - (tabWidth + tabGap) * 2, BUTTON_HEIGHT)
                .build());

        addGeneralButtons(panelX, panelWidth);
        addTestButtons(panelX, panelWidth);
        addBlockDebugButtons(panelX, panelWidth);
        int saveButtonWidth = Math.min(180, Math.max(1, width - 24));
        addRenderableWidget(Button.builder(Component.literal("Save and Go Back"), button -> onClose())
                .bounds((width - saveButtonWidth) / 2, height - 28, saveButtonWidth, BUTTON_HEIGHT)
                .build());
        updateVisibility();
    }

    private void configureSettingsGrid(int panelWidth) {
        settingsStartY = height < 210 ? 54 : 68;
        int settingsBottom = Math.max(settingsStartY + BUTTON_HEIGHT, height - 36);
        int availableHeight = Math.max(BUTTON_HEIGHT, settingsBottom - settingsStartY);
        int maximumRows = Math.max(1,
                (availableHeight + MIN_ROW_GAP) / (BUTTON_HEIGHT + MIN_ROW_GAP));
        settingsColumns = Math.max(1,
                (GENERAL_BUTTON_COUNT + maximumRows - 1) / maximumRows);
        settingsColumnGap = Math.min(8,
                Math.max(2, panelWidth / Math.max(12, settingsColumns * 12)));

        int rows = (GENERAL_BUTTON_COUNT + settingsColumns - 1) / settingsColumns;
        int spareHeight = Math.max(0, availableHeight - rows * BUTTON_HEIGHT);
        int rowGap = rows <= 1 ? 0 : Math.min(ROW_GAP, spareHeight / (rows - 1));
        settingsRowStep = BUTTON_HEIGHT + rowGap;
    }

    private void addGeneralButtons(int panelX, int panelWidth) {
        addToggle(generalButtons, 0, panelX, panelWidth, "GUI Notifications",
                PocketTuneClientConfig.SHOW_GUI_NOTIFICATIONS);
        addCycle(generalButtons, 1, panelX, panelWidth,
                () -> "Notification Duration: " + PocketTuneClientConfig.NOTIFICATION_DURATION_SECONDS.get() + " s",
                () -> cycle(PocketTuneClientConfig.NOTIFICATION_DURATION_SECONDS, 3, 5, 8, 12, 15));
        addToggle(generalButtons, 2, panelX, panelWidth, "Pause in ESC Menu",
                PocketTuneClientConfig.PAUSE_AUDIO_ON_PAUSE_SCREEN);
        addCycle(generalButtons, 3, panelX, panelWidth,
                () -> "Cover Quality: " + PocketTuneClientConfig.THUMBNAIL_QUALITY.get().displayName(),
                () -> {
                    PocketTuneClientConfig.THUMBNAIL_QUALITY.set(
                            PocketTuneClientConfig.THUMBNAIL_QUALITY.get().next());
                    ThumbnailCache.instance().clear();
                });
        addCycle(generalButtons, 4, panelX, panelWidth,
                () -> "Cover Cache: " + PocketTuneClientConfig.THUMBNAIL_CACHE_ENTRIES.get(),
                () -> {
                    cycle(PocketTuneClientConfig.THUMBNAIL_CACHE_ENTRIES, 32, 64, 96, 128, 256);
                    ThumbnailCache.instance().clear();
                });
        addCycle(generalButtons, 5, panelX, panelWidth,
                () -> "Full Volume Distance: " + format(PocketTuneClientConfig.FULL_VOLUME_DISTANCE.get()) + " blocks",
                () -> cycle(PocketTuneClientConfig.FULL_VOLUME_DISTANCE, 2, 4, 6, 8, 12, 16));
        addCycle(generalButtons, 6, panelX, panelWidth,
                () -> "Wall Reduction: " + PocketTuneClientConfig.OCCLUSION_MAX_REDUCTION_PERCENT.get() + "%",
                () -> cycle(PocketTuneClientConfig.OCCLUSION_MAX_REDUCTION_PERCENT, 25, 50, 65, 75, 90, 100));
        addCycle(generalButtons, 7, panelX, panelWidth,
                () -> "Occlusion Response: " + PocketTuneClientConfig.OCCLUSION_SMOOTHING_PERCENT.get() + "%",
                () -> cycle(PocketTuneClientConfig.OCCLUSION_SMOOTHING_PERCENT, 10, 20, 35, 50, 75, 100));
        addCycle(generalButtons, 8, panelX, panelWidth,
                () -> "Out-of-Range Grace: " + PocketTuneClientConfig.OUT_OF_RANGE_GRACE_SECONDS.get() + " s",
                () -> cycle(PocketTuneClientConfig.OUT_OF_RANGE_GRACE_SECONDS, 0, 5, 10, 15, 20, 30));
        addCycle(generalButtons, 9, panelX, panelWidth,
                () -> "Maximum Concurrent Audio: "
                        + PocketTuneClientConfig.MAX_CONCURRENT_MPV_CONTROLLERS.get(),
                () -> cycle(PocketTuneClientConfig.MAX_CONCURRENT_MPV_CONTROLLERS,
                        1, 2, 4, 6, 8, 12, 16, 24, 32));
        addToggle(generalButtons, 10, panelX, panelWidth, "Music Overlay",
                PocketTuneClientConfig.SHOW_PORTABLE_HUD);
        addToggle(generalButtons, 11, panelX, panelWidth, "Show Overlay with F1",
                PocketTuneClientConfig.SHOW_PORTABLE_OVERLAY_WHEN_HUD_HIDDEN);
    }

    private void addTestButtons(int panelX, int panelWidth) {
        addToggle(testButtons, 0, panelX, panelWidth, "Test Mode", PocketTuneClientConfig.TEST_MODE_ENABLED);
        addToggle(testButtons, 1, panelX, panelWidth, "Range Boundary", PocketTuneClientConfig.VISUALIZE_RANGE);
        addToggle(testButtons, 2, panelX, panelWidth, "Attenuation Zones",
                PocketTuneClientConfig.VISUALIZE_ATTENUATION);
        addToggle(testButtons, 3, panelX, panelWidth, "Occlusion Rays",
                PocketTuneClientConfig.VISUALIZE_OCCLUSION);
        addToggle(testButtons, 4, panelX, panelWidth, "Measurement HUD", PocketTuneClientConfig.SHOW_TEST_HUD);
        addCycle(testButtons, 5, panelX, panelWidth,
                () -> "Refresh: " + PocketTuneClientConfig.VISUALIZATION_INTERVAL_TICKS.get() + " ticks",
                () -> cycle(PocketTuneClientConfig.VISUALIZATION_INTERVAL_TICKS, 2, 5, 10, 20, 40));
        addCycle(testButtons, 6, panelX, panelWidth,
                () -> "Ring Quality: " + PocketTuneClientConfig.VISUALIZATION_SEGMENTS.get(),
                () -> cycle(PocketTuneClientConfig.VISUALIZATION_SEGMENTS, 16, 24, 32, 48, 64));
        addCycle(testButtons, 7, panelX, panelWidth,
                () -> "Maximum Distance: " + PocketTuneClientConfig.MAX_VISUALIZATION_DISTANCE.get() + " blocks",
                () -> cycle(PocketTuneClientConfig.MAX_VISUALIZATION_DISTANCE, 64, 96, 128, 160, 192, 256));
        addCycle(testButtons, 8, panelX, panelWidth,
                () -> "Maximum Speakers: " + PocketTuneClientConfig.MAX_VISUALIZED_SPEAKERS.get(),
                () -> cycle(PocketTuneClientConfig.MAX_VISUALIZED_SPEAKERS, 1, 2, 4, 8, 16));
    }

    private void addBlockDebugButtons(int panelX, int panelWidth) {
        addToggle(blockDebugButtons, 0, panelX, panelWidth, "Block Interaction Debug",
                PocketTuneClientConfig.BLOCK_INTERACTION_DEBUG_ENABLED);
        addToggle(blockDebugButtons, 1, panelX, panelWidth, "On-Screen Debug HUD",
                PocketTuneClientConfig.SHOW_BLOCK_INTERACTION_HUD);
        addToggle(blockDebugButtons, 2, panelX, panelWidth, "Write to Log File",
                PocketTuneClientConfig.LOG_BLOCK_INTERACTION_EVENTS);
        addCycle(blockDebugButtons, 3, panelX, panelWidth,
                () -> "History Size: " + PocketTuneClientConfig.BLOCK_INTERACTION_HISTORY_SIZE.get(),
                () -> cycle(PocketTuneClientConfig.BLOCK_INTERACTION_HISTORY_SIZE, 6, 8, 12, 16, 20, 24));
        addCycle(blockDebugButtons, 4, panelX, panelWidth,
                () -> "Clear Debug History",
                BlockInteractionDebugTracker::clear);
    }

    private void addToggle(
            List<Button> pageButtons,
            int index,
            int panelX,
            int panelWidth,
            String label,
            ModConfigSpec.BooleanValue value
    ) {
        addCycle(pageButtons, index, panelX, panelWidth,
                () -> label + ": " + (value.get() ? "On" : "Off"),
                () -> value.set(!value.get()));
    }

    private void addCycle(
            List<Button> pageButtons,
            int index,
            int panelX,
            int panelWidth,
            Supplier<String> message,
            Runnable action
    ) {
        int totalGapWidth = settingsColumnGap * Math.max(0, settingsColumns - 1);
        int buttonWidth = Math.max(1, (panelWidth - totalGapWidth) / settingsColumns);
        int column = index % settingsColumns;
        int row = index / settingsColumns;
        int x = panelX + column * (buttonWidth + settingsColumnGap);
        int resolvedWidth = column == settingsColumns - 1
                ? Math.max(1, panelX + panelWidth - x)
                : buttonWidth;
        int y = settingsStartY + row * settingsRowStep;
        Button button = Button.builder(Component.literal(message.get()), pressed -> {
                    action.run();
                    pressed.setMessage(Component.literal(message.get()));
                })
                .bounds(x, y, resolvedWidth, BUTTON_HEIGHT)
                .build();
        pageButtons.add(addRenderableWidget(button));
    }

    private void selectPage(Page selected) {
        page = selected;
        updateVisibility();
    }

    private void updateVisibility() {
        generalButtons.forEach(button -> button.visible = page == Page.GENERAL);
        testButtons.forEach(button -> button.visible = page == Page.TEST);
        blockDebugButtons.forEach(button -> button.visible = page == Page.BLOCK_DEBUG);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, GuiTheme.BACKGROUND);
        int panelWidth = Math.max(1, Math.min(744, width - 12));
        int panelX = (width - panelWidth) / 2;
        GuiTheme.panel(graphics, new GuiTheme.Bounds(panelX, 8, panelWidth, Math.max(1, height - 44)));
        graphics.drawCenteredString(font, title, width / 2, 14, GuiTheme.ACCENT);
        String description = switch (page) {
            case GENERAL -> "Interface, cover cache and local spatial-audio behavior";
            case TEST -> "Development visuals run only while Test Mode is enabled";
            case BLOCK_DEBUG -> "Track events, packets, callbacks, BlockEntities and client/server state";
        };
        if (height >= 210) {
            graphics.drawCenteredString(font, description, width / 2, 56, GuiTheme.MUTED);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        PocketTuneClientConfig.SPEC.save();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    private static void cycle(ModConfigSpec.IntValue value, int... choices) {
        int current = value.get();
        for (int index = 0; index < choices.length; index++) {
            if (choices[index] == current) {
                value.set(choices[(index + 1) % choices.length]);
                return;
            }
        }
        value.set(choices[0]);
    }

    private static void cycle(ModConfigSpec.DoubleValue value, int... choices) {
        double current = value.get();
        for (int index = 0; index < choices.length; index++) {
            if (Math.abs(choices[index] - current) < 0.001D) {
                value.set((double) choices[(index + 1) % choices.length]);
                return;
            }
        }
        value.set((double) choices[0]);
    }

    private static String format(double value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Double.toString(value);
    }

    private enum Page {
        GENERAL,
        TEST,
        BLOCK_DEBUG
    }
}
