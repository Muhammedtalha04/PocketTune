package com.pockettune.client.debug;

import com.mojang.logging.LogUtils;
import com.pockettune.PocketTune;
import com.pockettune.config.PocketTuneClientConfig;
import com.pockettune.network.payload.BlockInteractionTracePayload;
import com.pockettune.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = PocketTune.MOD_ID, value = Dist.CLIENT)
public final class BlockInteractionDebugTracker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final UUID NO_REQUEST = new UUID(0L, 0L);
    private static final int SYNC_TIMEOUT_TICKS = 40;
    private static final Deque<TraceEntry> HISTORY = new ArrayDeque<>();
    private static final Map<UUID, PendingSyncCheck> PENDING_SYNC_CHECKS = new HashMap<>();

    private BlockInteractionDebugTracker() {
    }

    public static boolean enabled() {
        return PocketTuneClientConfig.BLOCK_INTERACTION_DEBUG_ENABLED.get();
    }

    public static void recordLocal(
            UUID requestId,
            BlockPos pos,
            BlockInteractionTracePayload.Stage stage,
            boolean successful,
            String detail
    ) {
        record(requestId, pos, stage, successful, detail);
    }

    public static void recordLifecycle(
            BlockPos pos,
            BlockInteractionTracePayload.Stage stage,
            String detail
    ) {
        record(NO_REQUEST, pos, stage, true, detail);
    }

    public static void acceptServerTrace(BlockInteractionTracePayload payload) {
        record(payload.requestId(), payload.pos(), payload.stage(), payload.successful(), payload.detail());
    }

    public static void expectBlockState(
            UUID requestId,
            BlockPos pos,
            boolean serverSpeakerPresent
    ) {
        if (!enabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        long gameTime = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        PENDING_SYNC_CHECKS.put(
                requestId,
                new PendingSyncCheck(pos.immutable(), serverSpeakerPresent, gameTime + SYNC_TIMEOUT_TICKS)
        );
        evaluateSyncCheck(requestId, PENDING_SYNC_CHECKS.get(requestId), gameTime);
    }

    public static void clear() {
        HISTORY.clear();
        PENDING_SYNC_CHECKS.clear();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!enabled()) {
            PENDING_SYNC_CHECKS.clear();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            PENDING_SYNC_CHECKS.clear();
            return;
        }
        long gameTime = minecraft.level.getGameTime();
        for (var iterator = PENDING_SYNC_CHECKS.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<UUID, PendingSyncCheck> entry = iterator.next();
            if (evaluateSyncCheck(entry.getKey(), entry.getValue(), gameTime)) {
                iterator.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!enabled() || !PocketTuneClientConfig.SHOW_BLOCK_INTERACTION_HUD.get() || HISTORY.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        renderHud(event.getGuiGraphics());
    }

    private static boolean evaluateSyncCheck(UUID requestId, PendingSyncCheck check, long gameTime) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return true;
        }
        boolean clientSpeakerPresent = minecraft.level.getBlockState(check.pos()).is(ModBlocks.SPEAKER);
        if (clientSpeakerPresent == check.serverSpeakerPresent()) {
            record(
                    requestId,
                    check.pos(),
                    BlockInteractionTracePayload.Stage.CLIENT_SYNC_MATCH,
                    true,
                    "server=" + stateName(check.serverSpeakerPresent())
                            + ", client=" + stateName(clientSpeakerPresent)
            );
            return true;
        }
        if (gameTime >= check.deadlineGameTime()) {
            record(
                    requestId,
                    check.pos(),
                    BlockInteractionTracePayload.Stage.CLIENT_SYNC_TIMEOUT,
                    false,
                    "server=" + stateName(check.serverSpeakerPresent())
                            + ", client=" + stateName(clientSpeakerPresent)
            );
            return true;
        }
        return false;
    }

    private static void record(
            UUID requestId,
            BlockPos pos,
            BlockInteractionTracePayload.Stage stage,
            boolean successful,
            String detail
    ) {
        if (!enabled()) {
            return;
        }
        String safeDetail = detail == null ? "" : detail.strip();
        TraceEntry entry = new TraceEntry(
                requestId == null ? NO_REQUEST : requestId,
                pos.immutable(),
                stage,
                successful,
                safeDetail
        );
        HISTORY.addLast(entry);
        int limit = PocketTuneClientConfig.BLOCK_INTERACTION_HISTORY_SIZE.get();
        while (HISTORY.size() > limit) {
            HISTORY.removeFirst();
        }
        if (PocketTuneClientConfig.LOG_BLOCK_INTERACTION_EVENTS.get()) {
            LOGGER.info(
                    "[PocketTune block-debug][{}][{}][{}] {}",
                    shortRequest(entry.requestId()),
                    entry.pos().toShortString(),
                    entry.stage().sideLabel(),
                    entry.stage().displayName() + ": " + entry.detail()
            );
        }
    }

    private static void renderHud(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        List<TraceEntry> entries = new ArrayList<>(HISTORY);
        int width = Math.min(470, graphics.guiWidth() - 20);
        int lineHeight = 11;
        int height = 29 + entries.size() * lineHeight;
        int x = 10;
        int y = 10;
        graphics.fill(x + 2, y + 2, x + width + 2, y + height + 2, 0x80000000);
        graphics.fill(x, y, x + width, y + height, 0xE0101512);
        graphics.fill(x, y, x + 3, y + height, 0xFF72C900);
        graphics.drawString(font, "POCKETTUNE BLOCK DEBUG", x + 9, y + 6, 0xFF72C900, false);
        TraceEntry latest = entries.getLast();
        String summary = "Request " + shortRequest(latest.requestId()) + " • " + latest.pos().toShortString();
        graphics.drawString(font, ellipsize(font, summary, width - 18), x + 9, y + 17, 0xFFB6C0B8, false);

        int lineY = y + 29;
        for (TraceEntry entry : entries) {
            String marker = entry.successful() ? "✓" : "!";
            String line = marker + " [" + entry.stage().sideLabel() + "] "
                    + entry.stage().displayName()
                    + (entry.detail().isBlank() ? "" : " • " + entry.detail());
            graphics.drawString(
                    font,
                    ellipsize(font, line, width - 18),
                    x + 9,
                    lineY,
                    entry.successful() ? 0xFFE2E8E3 : 0xFFFF6A5E,
                    false
            );
            lineY += lineHeight;
        }
    }

    private static String ellipsize(Font font, String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }

    private static String shortRequest(UUID requestId) {
        return NO_REQUEST.equals(requestId) ? "lifecycle" : requestId.toString().substring(0, 8);
    }

    private static String stateName(boolean speakerPresent) {
        return speakerPresent ? "speaker" : "air/other";
    }

    private record TraceEntry(
            UUID requestId,
            BlockPos pos,
            BlockInteractionTracePayload.Stage stage,
            boolean successful,
            String detail
    ) {
    }

    private record PendingSyncCheck(BlockPos pos, boolean serverSpeakerPresent, long deadlineGameTime) {
    }
}
