package com.pockettune.audio;

import com.pockettune.model.SpeakerSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class SpatialAudioMath {
    public static final List<Vec3> OCCLUSION_SAMPLE_OFFSETS = List.of(
            Vec3.ZERO,
            new Vec3(0.45D, 0.0D, 0.0D),
            new Vec3(-0.45D, 0.0D, 0.0D),
            new Vec3(0.0D, 0.45D, 0.0D),
            new Vec3(0.0D, 0.0D, 0.45D),
            new Vec3(0.0D, 0.0D, -0.45D)
    );

    private SpatialAudioMath() {
    }

    public static double volumeForDistance(
            double distance,
            SpeakerSettings settings,
            double configuredFullVolumeDistance
    ) {
        return DistanceAttenuation.volumeForDistance(distance, settings, configuredFullVolumeDistance);
    }

    public static OcclusionSample sampleOcclusion(
            Level level,
            BlockPos speakerPos,
            Player listener,
            SpeakerSettings settings,
            double maximumReduction
    ) {
        if (!settings.wallOcclusion()) {
            return new OcclusionSample(0, OCCLUSION_SAMPLE_OFFSETS.size(), 1.0D, List.of());
        }
        Vec3 eyePosition = listener.getEyePosition();
        Vec3 speakerCenter = Vec3.atCenterOf(speakerPos);
        List<RaySample> rays = new ArrayList<>(OCCLUSION_SAMPLE_OFFSETS.size());
        int blocked = 0;
        for (Vec3 offset : OCCLUSION_SAMPLE_OFFSETS) {
            Vec3 target = speakerCenter.add(offset);
            HitResult hit = level.clip(new ClipContext(
                    eyePosition,
                    target,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    listener
            ));
            boolean rayBlocked = hit.getType() == HitResult.Type.BLOCK
                    && hit instanceof BlockHitResult blockHit
                    && !blockHit.getBlockPos().equals(speakerPos);
            if (rayBlocked) {
                blocked++;
            }
            rays.add(new RaySample(eyePosition, target, hit.getLocation(), rayBlocked));
        }
        double clampedReduction = Math.max(0.0D, Math.min(1.0D, maximumReduction));
        double multiplier = 1.0D - clampedReduction * ((double) blocked / OCCLUSION_SAMPLE_OFFSETS.size());
        return new OcclusionSample(blocked, OCCLUSION_SAMPLE_OFFSETS.size(), multiplier, List.copyOf(rays));
    }

    public record OcclusionSample(int blockedRays, int totalRays, double multiplier, List<RaySample> rays) {
    }

    public record RaySample(Vec3 start, Vec3 target, Vec3 hitLocation, boolean blocked) {
    }
}
