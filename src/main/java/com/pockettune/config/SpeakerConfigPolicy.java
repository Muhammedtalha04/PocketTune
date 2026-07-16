package com.pockettune.config;

import com.pockettune.model.SpeakerSettings;

/**
 * Sunucu config değerlerini ağdan ve dosyadan gelen hoparlör ayarlarına güvenli biçimde uygular.
 */
public final class SpeakerConfigPolicy {
    private SpeakerConfigPolicy() {
    }

    public static SpeakerSettings defaults(double configuredVolume, int configuredMaximumRange) {
        int maximumRange = maximumRange(configuredMaximumRange);
        double volume = Double.isFinite(configuredVolume)
                ? Math.max(SpeakerSettings.MIN_VOLUME,
                        Math.min(SpeakerSettings.MAX_VOLUME, configuredVolume))
                : SpeakerSettings.DEFAULT_VOLUME;
        return new SpeakerSettings(
                volume,
                Math.min(SpeakerSettings.DEFAULT.rangeBlocks(), maximumRange),
                SpeakerSettings.DEFAULT.fadeType(),
                SpeakerSettings.DEFAULT.wallOcclusion(),
                SpeakerSettings.DEFAULT.bassDb(),
                SpeakerSettings.DEFAULT.midDb(),
                SpeakerSettings.DEFAULT.trebleDb(),
                SpeakerSettings.DEFAULT.shuffle(),
                SpeakerSettings.DEFAULT.repeatMode()
        );
    }

    public static SpeakerSettings constrain(SpeakerSettings settings, int configuredMaximumRange) {
        SpeakerSettings source = settings == null ? SpeakerSettings.DEFAULT : settings;
        int safeRange = Math.min(source.rangeBlocks(), maximumRange(configuredMaximumRange));
        if (safeRange == source.rangeBlocks()) {
            return source;
        }
        return new SpeakerSettings(
                source.volumePercent(),
                safeRange,
                source.fadeType(),
                source.wallOcclusion(),
                source.bassDb(),
                source.midDb(),
                source.trebleDb(),
                source.shuffle(),
                source.repeatMode()
        );
    }

    public static int maximumRange(int configuredMaximumRange) {
        return Math.max(
                SpeakerSettings.MIN_RANGE,
                Math.min(SpeakerSettings.MAX_RANGE, configuredMaximumRange)
        );
    }
}
