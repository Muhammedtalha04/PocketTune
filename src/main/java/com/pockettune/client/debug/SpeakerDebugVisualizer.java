package com.pockettune.client.debug;

import com.pockettune.PocketTune;
import com.pockettune.audio.SpatialAudioMath;
import com.pockettune.block.entity.SpeakerBlockEntity;
import com.pockettune.config.PocketTuneClientConfig;
import com.pockettune.model.SpeakerSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@EventBusSubscriber(modid = PocketTune.MOD_ID, value = Dist.CLIENT)
public final class SpeakerDebugVisualizer {
    private static final int RANGE_COLOR = 0x69E36F;
    private static final int FULL_VOLUME_COLOR = 0x53D8FB;
    private static final int CLEAR_RAY_COLOR = 0x5BE37D;
    private static final int BLOCKED_RAY_COLOR = 0xFF4E45;
    private static final int RAY_BEFORE_HIT_COLOR = 0xFFD45B;
    private static List<Snapshot> snapshots = List.of();
    private static int refreshCountdown;

    private SpeakerDebugVisualizer() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!PocketTuneClientConfig.TEST_MODE_ENABLED.get()
                || minecraft.level == null
                || minecraft.player == null) {
            snapshots = List.of();
            refreshCountdown = 0;
            return;
        }
        if (refreshCountdown-- > 0) {
            return;
        }
        refreshCountdown = PocketTuneClientConfig.VISUALIZATION_INTERVAL_TICKS.get() - 1;
        snapshots = collectSnapshots(minecraft.level, minecraft.player.position());
        for (Snapshot snapshot : snapshots) {
            renderParticles(minecraft.level, snapshot);
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!PocketTuneClientConfig.TEST_MODE_ENABLED.get()
                || !PocketTuneClientConfig.SHOW_TEST_HUD.get()
                || snapshots.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        renderHud(event.getGuiGraphics(), snapshots.getFirst());
    }

    private static List<Snapshot> collectSnapshots(ClientLevel level, Vec3 listenerPosition) {
        int maximumDistance = PocketTuneClientConfig.MAX_VISUALIZATION_DISTANCE.get();
        double maximumDistanceSquared = (double) maximumDistance * maximumDistance;
        int centerChunkX = ((int) Math.floor(listenerPosition.x)) >> 4;
        int centerChunkZ = ((int) Math.floor(listenerPosition.z)) >> 4;
        int chunkRadius = (maximumDistance + 15) / 16;
        List<SpeakerCandidate> candidates = new ArrayList<>();

        for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
            for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (var blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof SpeakerBlockEntity speaker) {
                        double distanceSquared = listenerPosition.distanceToSqr(Vec3.atCenterOf(speaker.getBlockPos()));
                        if (distanceSquared <= maximumDistanceSquared) {
                            candidates.add(new SpeakerCandidate(speaker, distanceSquared));
                        }
                    }
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(SpeakerCandidate::distanceSquared));
        int limit = Math.min(candidates.size(), PocketTuneClientConfig.MAX_VISUALIZED_SPEAKERS.get());
        List<Snapshot> result = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            SpeakerBlockEntity speaker = candidates.get(index).speaker();
            double distance = Math.sqrt(candidates.get(index).distanceSquared());
            SpeakerSettings settings = speaker.getSettings();
            SpatialAudioMath.OcclusionSample occlusion = SpatialAudioMath.sampleOcclusion(
                    level,
                    speaker.getBlockPos(),
                    Minecraft.getInstance().player,
                    settings,
                    PocketTuneClientConfig.OCCLUSION_MAX_REDUCTION_PERCENT.get() / 100.0D
            );
            double distanceVolume = SpatialAudioMath.volumeForDistance(
                    distance,
                    settings,
                    PocketTuneClientConfig.FULL_VOLUME_DISTANCE.get()
            );
            result.add(new Snapshot(
                    speaker.getBlockPos().immutable(),
                    settings,
                    distance,
                    distanceVolume,
                    distanceVolume * occlusion.multiplier(),
                    occlusion,
                    speaker.isPlaying(),
                    speaker.isPaused()
            ));
        }
        return List.copyOf(result);
    }

    private static void renderParticles(ClientLevel level, Snapshot snapshot) {
        Vec3 center = Vec3.atCenterOf(snapshot.pos());
        int segments = PocketTuneClientConfig.VISUALIZATION_SEGMENTS.get();
        if (PocketTuneClientConfig.VISUALIZE_RANGE.get()) {
            renderThreeAxisRings(level, center, snapshot.settings().rangeBlocks(), RANGE_COLOR,
                    adaptiveSegments(snapshot.settings().rangeBlocks(), segments), 1.15F);
            spawn(level, center, RANGE_COLOR, 1.35F);
        }
        if (PocketTuneClientConfig.VISUALIZE_ATTENUATION.get()) {
            double fullDistance = Math.min(
                    snapshot.settings().rangeBlocks(),
                    PocketTuneClientConfig.FULL_VOLUME_DISTANCE.get()
            );
            renderTwoAxisRings(level, center, fullDistance, FULL_VOLUME_COLOR,
                    adaptiveSegments(fullDistance, segments), 0.95F);
            double remaining = snapshot.settings().rangeBlocks() - fullDistance;
            for (int zone = 1; zone <= 3; zone++) {
                double radius = fullDistance + remaining * zone / 4.0D;
                double volume = SpatialAudioMath.volumeForDistance(
                        radius,
                        snapshot.settings(),
                        PocketTuneClientConfig.FULL_VOLUME_DISTANCE.get()
                );
                double ratio = snapshot.settings().volumePercent() <= 0.0D
                        ? 0.0D
                        : volume / snapshot.settings().volumePercent();
                renderHorizontalRing(level, center, radius, attenuationColor(ratio),
                        adaptiveSegments(radius, segments), 0.9F);
            }
        }
        if (PocketTuneClientConfig.VISUALIZE_OCCLUSION.get() && snapshot.settings().wallOcclusion()) {
            for (SpatialAudioMath.RaySample ray : snapshot.occlusion().rays()) {
                if (ray.blocked()) {
                    renderLine(level, ray.start(), ray.hitLocation(), RAY_BEFORE_HIT_COLOR, 8);
                    renderLine(level, ray.hitLocation(), ray.target(), BLOCKED_RAY_COLOR, 6);
                    spawn(level, ray.hitLocation(), BLOCKED_RAY_COLOR, 0.9F);
                } else {
                    renderLine(level, ray.start(), ray.target(), CLEAR_RAY_COLOR, 10);
                }
            }
        }
    }

    private static void renderThreeAxisRings(
            ClientLevel level,
            Vec3 center,
            double radius,
            int color,
            int segments,
            float scale
    ) {
        for (int index = 0; index < segments; index++) {
            double angle = Math.PI * 2.0D * index / segments;
            double first = Math.cos(angle) * radius;
            double second = Math.sin(angle) * radius;
            spawn(level, center.add(first, 0.0D, second), color, scale);
            spawn(level, center.add(first, second, 0.0D), color, scale);
            spawn(level, center.add(0.0D, first, second), color, scale);
        }
    }

    private static void renderTwoAxisRings(
            ClientLevel level,
            Vec3 center,
            double radius,
            int color,
            int segments,
            float scale
    ) {
        if (radius <= 0.0D) {
            return;
        }
        for (int index = 0; index < segments; index++) {
            double angle = Math.PI * 2.0D * index / segments;
            double first = Math.cos(angle) * radius;
            double second = Math.sin(angle) * radius;
            spawn(level, center.add(first, 0.12D, second), color, scale);
            spawn(level, center.add(first, second, 0.0D), color, scale);
        }
    }

    private static void renderHorizontalRing(
            ClientLevel level,
            Vec3 center,
            double radius,
            int color,
            int segments,
            float scale
    ) {
        if (radius <= 0.0D) {
            return;
        }
        for (int index = 0; index < segments; index++) {
            double angle = Math.PI * 2.0D * index / segments;
            spawn(level, center.add(Math.cos(angle) * radius, 0.15D, Math.sin(angle) * radius), color, scale);
        }
    }

    private static int adaptiveSegments(double radius, int configuredMinimum) {
        int circumferenceSegments = (int) Math.ceil(Math.PI * 2.0D * Math.max(1.0D, radius) / 3.0D);
        return Math.max(configuredMinimum, Math.min(256, circumferenceSegments));
    }

    private static void renderLine(ClientLevel level, Vec3 start, Vec3 end, int color, int points) {
        for (int index = 0; index <= points; index++) {
            double progress = (double) index / points;
            spawn(level, start.lerp(end, progress), color, 0.45F);
        }
    }

    private static void spawn(ClientLevel level, Vec3 position, int color, float scale) {
        level.addAlwaysVisibleParticle(
                new DustParticleOptions(color, scale),
                position.x,
                position.y,
                position.z,
                0.0D,
                0.0D,
                0.0D
        );
    }

    private static int attenuationColor(double ratio) {
        double clamped = Math.max(0.0D, Math.min(1.0D, ratio));
        int red = (int) Math.round(255.0D * (1.0D - clamped));
        int green = (int) Math.round(220.0D * clamped + 45.0D);
        return red << 16 | Math.min(255, green) << 8 | 0x35;
    }

    private static void renderHud(GuiGraphics graphics, Snapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        int width = 236;
        int x = graphics.guiWidth() - width - 10;
        int y = 10;
        int height = 83;
        graphics.fill(x, y, x + width, y + height, 0xD0101512);
        graphics.fill(x, y, x + 3, y + height, 0xFF72C900);
        graphics.drawString(minecraft.font, "POCKETTUNE TEST MODE", x + 10, y + 8, 0xFF72C900, false);
        graphics.drawString(minecraft.font,
                "Speaker: " + snapshot.pos().getX() + ", " + snapshot.pos().getY() + ", " + snapshot.pos().getZ(),
                x + 10, y + 21, 0xFFE8E8E8, false);
        graphics.drawString(minecraft.font,
                "Distance: " + decimal(snapshot.distance()) + " / " + snapshot.settings().rangeBlocks() + " blocks",
                x + 10, y + 33, 0xFFE8E8E8, false);
        graphics.drawString(minecraft.font,
                "Attenuation: " + snapshot.settings().fadeType().displayName()
                        + "  |  Distance volume: " + decimal(snapshot.distanceVolume()) + "%",
                x + 10, y + 45, 0xFFFFD45B, false);
        graphics.drawString(minecraft.font,
                "Occlusion: " + snapshot.occlusion().blockedRays() + "/" + snapshot.occlusion().totalRays()
                        + " rays  |  Multiplier: " + decimal(snapshot.occlusion().multiplier() * 100.0D) + "%",
                x + 10, y + 57, snapshot.occlusion().blockedRays() > 0 ? 0xFFFF6A5E : 0xFF68C987, false);
        String state = !snapshot.playing() ? "Stopped" : snapshot.paused() ? "Paused" : "Playing";
        graphics.drawString(minecraft.font,
                "Result: " + decimal(snapshot.finalVolume()) + "%  |  " + state,
                x + 10, y + 69, 0xFFE8E8E8, false);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private record SpeakerCandidate(SpeakerBlockEntity speaker, double distanceSquared) {
    }

    private record Snapshot(
            BlockPos pos,
            SpeakerSettings settings,
            double distance,
            double distanceVolume,
            double finalVolume,
            SpatialAudioMath.OcclusionSample occlusion,
            boolean playing,
            boolean paused
    ) {
    }
}
