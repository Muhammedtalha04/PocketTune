package com.pockettune.client.gui.panel;

import com.pockettune.client.gui.GuiTheme;
import com.pockettune.client.gui.ThumbnailCache;
import com.pockettune.model.SpeakerSettings;
import com.pockettune.model.TrackMetadata;
import com.pockettune.network.payload.SpeakerControlPayload;
import com.pockettune.network.payload.SpeakerQueueActionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

import java.util.List;

public final class PlaylistPanel {
    private static final int HEADER_HEIGHT = 32;
    private static final int FOOTER_HEIGHT = 38;
    private static final int ROW_HEIGHT = 44;
    private static final int CONTEXT_WIDTH = 126;
    private static final int CONTEXT_ITEM_HEIGHT = 18;

    private final BlockPos speakerPos;
    private final UUID speakerInstanceId;
    private GuiTheme.Bounds bounds = new GuiTheme.Bounds(0, 0, 0, 0);
    private List<TrackMetadata> tracks = List.of();
    private int activeIndex;
    private int scrollRows;
    private int dragIndex = -1;
    private int dragDropSlot = -1;
    private int contextIndex = -1;
    private int contextX;
    private int contextY;

    public PlaylistPanel(BlockPos speakerPos, UUID speakerInstanceId) {
        this.speakerPos = speakerPos;
        this.speakerInstanceId = speakerInstanceId;
    }

    public void setBounds(GuiTheme.Bounds bounds) {
        this.bounds = bounds;
        clampScroll();
    }

    public void update(List<TrackMetadata> tracks, int activeIndex) {
        this.tracks = tracks;
        this.activeIndex = activeIndex;
        // Server senkronizasyonu listeyi küçültebilir; menü açıkken satır kaybolursa menü kapatılır.
        if (contextIndex >= tracks.size()) {
            contextIndex = -1;
        }
        if (dragIndex >= tracks.size()) {
            dragIndex = -1;
            dragDropSlot = -1;
        }
        clampScroll();
    }

    public void render(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            SpeakerSettings settings,
            Runnable addUrl
    ) {
        GuiTheme.panel(graphics, bounds);
        Font font = Minecraft.getInstance().font;
        graphics.drawString(font, "PLAYLIST (" + (tracks.isEmpty() ? 0 : activeIndex + 1) + "/" + tracks.size() + ")",
                bounds.x() + 12, bounds.y() + 11, GuiTheme.ACCENT, false);
        graphics.fill(bounds.x() + 10, bounds.y() + HEADER_HEIGHT - 2,
                bounds.right() - 10, bounds.y() + HEADER_HEIGHT - 1, GuiTheme.BORDER);

        int viewportTop = bounds.y() + HEADER_HEIGHT;
        int viewportBottom = bounds.bottom() - FOOTER_HEIGHT;
        int visibleRows = Math.max(1, (viewportBottom - viewportTop) / ROW_HEIGHT);
        for (int visible = 0; visible < visibleRows; visible++) {
            int index = scrollRows + visible;
            if (index >= tracks.size()) {
                break;
            }
            int rowY = viewportTop + visible * ROW_HEIGHT;
            renderRow(graphics, mouseX, mouseY, index, rowY);
        }

        if (tracks.isEmpty()) {
            int centerX = bounds.x() + bounds.width() / 2;
            graphics.drawCenteredString(font, "The queue is empty", centerX, viewportTop + 22, GuiTheme.TEXT);
            graphics.drawCenteredString(font, "Use + Add to add a track or playlist",
                    centerX, viewportTop + 38, GuiTheme.MUTED);
        }

        if (dragIndex >= 0 && dragIndex < tracks.size()) {
            renderDragPreview(graphics, mouseY, tracks.get(dragIndex));
            renderDropIndicator(graphics);
        }

        renderFooter(graphics, mouseX, mouseY, settings, addUrl);
        if (contextIndex >= 0) {
            renderContextMenu(graphics, mouseX, mouseY);
        }
    }

    private void renderRow(GuiGraphics graphics, int mouseX, int mouseY, int index, int rowY) {
        TrackMetadata track = tracks.get(index);
        boolean hovered = mouseX >= bounds.x() + 8 && mouseX < bounds.right() - 8
                && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT - 2;
        int color = index == activeIndex ? GuiTheme.ROW_ACTIVE : hovered ? GuiTheme.ROW_HOVER : GuiTheme.ROW;
        boolean dragged = index == dragIndex;
        if (dragged) {
            color = 0xE0162119;
        }
        graphics.fill(bounds.x() + 8, rowY, bounds.right() - 8, rowY + ROW_HEIGHT - 2, color);
        if (index == activeIndex) {
            graphics.fill(bounds.x() + 8, rowY, bounds.x() + 11, rowY + ROW_HEIGHT - 2, GuiTheme.ACCENT);
        }

        int thumbX = bounds.x() + 34;
        ThumbnailCache.instance().get(track).render(graphics, thumbX, rowY + 4, 48, 30);
        Font font = Minecraft.getInstance().font;
        graphics.drawString(font, index == activeIndex ? "▶" : Integer.toString(index + 1),
                bounds.x() + 18, rowY + 15, index == activeIndex ? GuiTheme.ACCENT : GuiTheme.MUTED, false);
        int textX = thumbX + 55;
        int durationWidth = font.width(NowPlayingPanel.formatTime(track.durationMillis()));
        int available = bounds.right() - textX - durationWidth - 30;
        graphics.drawString(font, GuiTheme.ellipsize(font, track.title(), available), textX, rowY + 8, GuiTheme.TEXT, false);
        graphics.drawString(font, GuiTheme.ellipsize(font, track.artist(), available), textX, rowY + 22, GuiTheme.MUTED, false);
        String duration = track.durationMillis() > 0L ? NowPlayingPanel.formatTime(track.durationMillis()) : "--:--";
        graphics.drawString(font, duration, bounds.right() - durationWidth - 18, rowY + 15, GuiTheme.TEXT, false);
        graphics.drawString(font, "⋮", bounds.right() - 13, rowY + 15, GuiTheme.MUTED, false);
        if (dragged) {
            graphics.fill(bounds.x() + 8, rowY, bounds.right() - 8, rowY + ROW_HEIGHT - 2, 0x70000000);
            graphics.fill(bounds.x() + 8, rowY, bounds.right() - 8, rowY + 2, GuiTheme.ACCENT);
            graphics.fill(bounds.x() + 8, rowY + ROW_HEIGHT - 4,
                    bounds.right() - 8, rowY + ROW_HEIGHT - 2, GuiTheme.ACCENT);
            graphics.fill(bounds.x() + 8, rowY, bounds.x() + 10, rowY + ROW_HEIGHT - 2, GuiTheme.ACCENT);
            graphics.fill(bounds.right() - 10, rowY, bounds.right() - 8,
                    rowY + ROW_HEIGHT - 2, GuiTheme.ACCENT);
            graphics.drawString(font, "HELD", bounds.right() - font.width("HELD") - 18,
                    rowY + 5, GuiTheme.ACCENT, false);
        }
    }

    private void renderDragPreview(GuiGraphics graphics, int mouseY, TrackMetadata track) {
        int viewportTop = bounds.y() + HEADER_HEIGHT;
        int viewportBottom = bounds.bottom() - FOOTER_HEIGHT;
        int previewY = Math.max(viewportTop + 2, Math.min(mouseY - ROW_HEIGHT / 2, viewportBottom - ROW_HEIGHT));
        int left = bounds.x() + 18;
        int right = bounds.right() - 18;
        graphics.fill(left + 3, previewY + 3, right + 3, previewY + ROW_HEIGHT - 1, 0x90000000);
        graphics.fill(left, previewY, right, previewY + ROW_HEIGHT - 4, GuiTheme.BORDER_DARK);
        graphics.fill(left + 2, previewY + 2, right - 2, previewY + ROW_HEIGHT - 6, 0xF02B3B22);
        graphics.fill(left + 2, previewY + 2, left + 6, previewY + ROW_HEIGHT - 6, GuiTheme.ACCENT);
        ThumbnailCache.instance().get(track).render(graphics, left + 12, previewY + 5, 44, 28);
        Font font = Minecraft.getInstance().font;
        int textX = left + 64;
        graphics.drawString(font, GuiTheme.ellipsize(font, track.title(), right - textX - 16),
                textX, previewY + 8, GuiTheme.TEXT, false);
        graphics.drawString(font, "Dragging • release the left button to drop",
                textX, previewY + 22, GuiTheme.ACCENT, false);
    }

    private void renderDropIndicator(GuiGraphics graphics) {
        if (dragDropSlot < 0) {
            return;
        }
        int viewportTop = bounds.y() + HEADER_HEIGHT;
        int viewportBottom = bounds.bottom() - FOOTER_HEIGHT;
        int lineY = viewportTop + (dragDropSlot - scrollRows) * ROW_HEIGHT;
        lineY = Math.max(viewportTop, Math.min(viewportBottom - 2, lineY));
        int left = bounds.x() + 8;
        int right = bounds.right() - 8;
        graphics.fill(left, lineY - 2, right, lineY + 2, 0xA0000000);
        graphics.fill(left, lineY - 1, right, lineY + 1, GuiTheme.ACCENT);
        graphics.fill(left, lineY - 4, left + 4, lineY + 4, GuiTheme.ACCENT);
        graphics.fill(right - 4, lineY - 4, right, lineY + 4, GuiTheme.ACCENT);
        Font font = Minecraft.getInstance().font;
        String label = "Drop here • position " + Math.min(tracks.size(), dragDropSlot + 1);
        int labelWidth = font.width(label) + 10;
        int labelX = Math.max(left + 6, right - labelWidth - 6);
        int labelY = lineY <= viewportTop + 10 ? lineY + 4 : lineY - 13;
        graphics.fill(labelX, labelY, labelX + labelWidth, labelY + 11, 0xF0121813);
        graphics.drawString(font, label, labelX + 5, labelY + 2, GuiTheme.ACCENT, false);
    }

    private void renderFooter(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            SpeakerSettings settings,
            Runnable addUrl
    ) {
        String[] labels = {"+ Add Track / Playlist", "Shuffle", "Repeat"};
        int footerY = bounds.bottom() - FOOTER_HEIGHT + 7;
        int buttonGap = 6;
        int buttonWidth = Math.max(1, (bounds.width() - 20 - (labels.length - 1) * buttonGap) / labels.length);
        Font font = Minecraft.getInstance().font;
        for (int index = 0; index < labels.length; index++) {
            int x = bounds.x() + 10 + index * (buttonWidth + buttonGap);
            int width = index == labels.length - 1 ? bounds.right() - 10 - x : buttonWidth;
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= footerY && mouseY < footerY + 24;
            int color = index == 0 ? GuiTheme.ACCENT_DARK : hovered ? GuiTheme.ROW_HOVER : GuiTheme.ROW;
            if (index == 1 && settings.shuffle() || index == 2 && settings.repeatMode() != SpeakerSettings.RepeatMode.OFF) {
                color = GuiTheme.ACCENT_DARK;
            }
            graphics.fill(x, footerY, x + width, footerY + 24, GuiTheme.BORDER_DARK);
            graphics.fill(x + 1, footerY + 1, x + width - 1, footerY + 23, color);
            if (hovered) {
                graphics.fill(x + 1, footerY + 1, x + width - 1, footerY + 2, GuiTheme.ACCENT);
            }
            String label = index == 2 ? "Repeat: " + settings.repeatMode().displayName() : labels[index];
            label = GuiTheme.ellipsize(font, label, width - 10);
            graphics.drawCenteredString(font, label, x + width / 2, footerY + 8, GuiTheme.TEXT);
        }
    }

    public boolean mouseClicked(
            double mouseX,
            double mouseY,
            int button,
            SpeakerSettings settings,
            Runnable addUrl
    ) {
        if (contextIndex >= 0) {
            if (button == 0 && handleContextClick(mouseX, mouseY)) {
                return true;
            }
            contextIndex = -1;
        }
        if (!bounds.contains(mouseX, mouseY)) {
            return false;
        }

        int row = rowAt(mouseY);
        if (row >= 0 && row < tracks.size()) {
            if (button == 1) {
                contextIndex = row;
                contextX = Math.min((int) mouseX, bounds.right() - CONTEXT_WIDTH - 4);
                contextY = Math.min((int) mouseY, bounds.bottom() - CONTEXT_ITEM_HEIGHT * ContextAction.values().length - 4);
                return true;
            }
            if (button == 0) {
                dragIndex = row;
                dragDropSlot = insertionSlotAt(mouseY);
                return true;
            }
        }

        int footerY = bounds.bottom() - FOOTER_HEIGHT + 7;
        if (button == 0 && mouseY >= footerY && mouseY < footerY + 24) {
            int buttonGap = 6;
            int buttonWidth = Math.max(1, (bounds.width() - 20 - buttonGap * 2) / 3);
            for (int slot = 0; slot < 3; slot++) {
                int x = bounds.x() + 10 + slot * (buttonWidth + buttonGap);
                int width = slot == 2 ? bounds.right() - 10 - x : buttonWidth;
                if (mouseX < x || mouseX >= x + width) {
                    continue;
                }
                switch (slot) {
                    case 0 -> addUrl.run();
                    case 1 -> sendControl(SpeakerControlPayload.Action.TOGGLE_SHUFFLE, 0.0D);
                    case 2 -> sendControl(SpeakerControlPayload.Action.CYCLE_REPEAT, 0.0D);
                    default -> {
                    }
                }
                return true;
            }
        }
        return false;
    }

    public boolean mouseDragged(double mouseY) {
        if (dragIndex < 0) {
            return false;
        }
        dragDropSlot = insertionSlotAt(mouseY);
        return true;
    }

    public boolean mouseReleased() {
        if (dragIndex < 0) {
            return false;
        }
        if (dragDropSlot >= 0) {
            int destination = dragDropSlot > dragIndex ? dragDropSlot - 1 : dragDropSlot;
            destination = Math.max(0, Math.min(tracks.size() - 1, destination));
            if (destination != dragIndex) {
                sendQueue(SpeakerQueueActionPayload.Action.MOVE, dragIndex, destination);
            }
        }
        dragIndex = -1;
        dragDropSlot = -1;
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!bounds.contains(mouseX, mouseY) || tracks.isEmpty()) {
            return false;
        }
        scrollRows -= (int) Math.signum(scrollY);
        clampScroll();
        return true;
    }

    private int rowAt(double mouseY) {
        int viewportTop = bounds.y() + HEADER_HEIGHT;
        int viewportBottom = bounds.bottom() - FOOTER_HEIGHT;
        if (mouseY < viewportTop || mouseY >= viewportBottom) {
            return -1;
        }
        return scrollRows + ((int) mouseY - viewportTop) / ROW_HEIGHT;
    }

    private int insertionSlotAt(double mouseY) {
        int viewportTop = bounds.y() + HEADER_HEIGHT;
        int viewportBottom = bounds.bottom() - FOOTER_HEIGHT;
        if (mouseY <= viewportTop) {
            return Math.max(0, Math.min(tracks.size(), scrollRows));
        }
        if (mouseY >= viewportBottom) {
            return Math.max(0, Math.min(tracks.size(), scrollRows + visibleRowCount()));
        }
        int relative = (int) mouseY - viewportTop;
        int rowOffset = relative / ROW_HEIGHT;
        int withinRow = relative % ROW_HEIGHT;
        int slot = scrollRows + rowOffset + (withinRow >= ROW_HEIGHT / 2 ? 1 : 0);
        return Math.max(0, Math.min(tracks.size(), slot));
    }

    private void clampScroll() {
        int visibleRows = visibleRowCount();
        scrollRows = Math.max(0, Math.min(scrollRows, Math.max(0, tracks.size() - visibleRows)));
    }

    private int visibleRowCount() {
        return Math.max(1, (bounds.height() - HEADER_HEIGHT - FOOTER_HEIGHT) / ROW_HEIGHT);
    }

    private void renderContextMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        int height = CONTEXT_ITEM_HEIGHT * ContextAction.values().length;
        graphics.fill(contextX - 1, contextY - 1, contextX + CONTEXT_WIDTH + 1, contextY + height + 1, GuiTheme.BORDER_DARK);
        for (int index = 0; index < ContextAction.values().length; index++) {
            int y = contextY + index * CONTEXT_ITEM_HEIGHT;
            boolean hovered = mouseX >= contextX && mouseX < contextX + CONTEXT_WIDTH
                    && mouseY >= y && mouseY < y + CONTEXT_ITEM_HEIGHT;
            graphics.fill(contextX, y, contextX + CONTEXT_WIDTH, y + CONTEXT_ITEM_HEIGHT,
                    hovered ? GuiTheme.ROW_HOVER : GuiTheme.PANEL_DARK);
            graphics.drawString(font, ContextAction.values()[index].label, contextX + 6, y + 5, GuiTheme.TEXT, false);
        }
    }

    private boolean handleContextClick(double mouseX, double mouseY) {
        int height = CONTEXT_ITEM_HEIGHT * ContextAction.values().length;
        if (mouseX < contextX || mouseX >= contextX + CONTEXT_WIDTH || mouseY < contextY || mouseY >= contextY + height) {
            return false;
        }
        ContextAction action = ContextAction.values()[((int) mouseY - contextY) / CONTEXT_ITEM_HEIGHT];
        int index = contextIndex;
        contextIndex = -1;
        if (index < 0 || index >= tracks.size()) {
            return true;
        }
        switch (action) {
            case PLAY_NOW -> sendQueue(SpeakerQueueActionPayload.Action.PLAY_NOW, index, index);
            case PLAY_NEXT -> sendQueue(SpeakerQueueActionPayload.Action.PLAY_NEXT, index, index);
            case COPY_URL -> Minecraft.getInstance().keyboardHandler.setClipboard(tracks.get(index).videoUrl());
            case REMOVE -> sendQueue(SpeakerQueueActionPayload.Action.REMOVE, index, index);
            case MOVE_TOP -> sendQueue(SpeakerQueueActionPayload.Action.MOVE_TOP, index, 0);
            case MOVE_BOTTOM -> sendQueue(SpeakerQueueActionPayload.Action.MOVE_BOTTOM, index, tracks.size() - 1);
        }
        return true;
    }

    private void sendQueue(SpeakerQueueActionPayload.Action action, int from, int to) {
        PacketDistributor.sendToServer(
                new SpeakerQueueActionPayload(speakerPos, speakerInstanceId, action, from, to));
    }

    private void sendControl(SpeakerControlPayload.Action action, double value) {
        PacketDistributor.sendToServer(new SpeakerControlPayload(speakerPos, speakerInstanceId, action, value));
    }

    private enum ContextAction {
        PLAY_NOW("Play Now"),
        PLAY_NEXT("Play Next"),
        COPY_URL("Copy URL"),
        REMOVE("Remove"),
        MOVE_TOP("Move to Top"),
        MOVE_BOTTOM("Move to Bottom");

        private final String label;

        ContextAction(String label) {
            this.label = label;
        }
    }
}
