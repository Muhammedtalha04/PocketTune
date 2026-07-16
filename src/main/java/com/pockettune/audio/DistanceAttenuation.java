package com.pockettune.audio;

import com.pockettune.model.SpeakerSettings;

public final class DistanceAttenuation {
    private DistanceAttenuation() {
    }

    public static double volumeForDistance(
            double distance,
            SpeakerSettings settings,
            double configuredFullVolumeDistance
    ) {
        double range = settings.rangeBlocks();
        double fullVolumeDistance = Math.max(0.0D, Math.min(configuredFullVolumeDistance, range));
        if (distance >= range) {
            return 0.0D;
        }
        if (distance <= fullVolumeDistance) {
            return settings.volumePercent();
        }
        if (fullVolumeDistance >= range) {
            return 0.0D;
        }
        double normalized = 1.0D - (distance - fullVolumeDistance) / (range - fullVolumeDistance);
        double attenuation = switch (settings.fadeType()) {
            case LINEAR -> normalized;
            case QUADRATIC -> normalized * normalized;
            case LOGARITHMIC -> Math.log1p(9.0D * normalized) / Math.log(10.0D);
        };
        return settings.volumePercent() * attenuation;
    }
}
