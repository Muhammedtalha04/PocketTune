package com.pockettune.config;

import com.pockettune.model.SpeakerSettings;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class PocketTuneServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue DEFAULT_VOLUME_PERCENT;
    public static final ModConfigSpec.IntValue MAXIMUM_RANGE_BLOCKS;
    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("speakerDefaults");
        DEFAULT_VOLUME_PERCENT = BUILDER
                .comment("Default volume percentage assigned to newly created speakers.")
                .defineInRange("defaultVolumePercent", SpeakerSettings.DEFAULT_VOLUME, 0.0D, 100.0D);
        MAXIMUM_RANGE_BLOCKS = BUILDER
                .comment("Server-authoritative maximum speaker range in blocks.")
                .defineInRange(
                        "maximumRangeBlocks",
                        SpeakerSettings.MAX_RANGE,
                        SpeakerSettings.MIN_RANGE,
                        SpeakerSettings.MAX_RANGE
                );
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private PocketTuneServerConfig() {
    }

    public static SpeakerSettings defaultSpeakerSettings() {
        return SpeakerConfigPolicy.defaults(
                DEFAULT_VOLUME_PERCENT.get(),
                MAXIMUM_RANGE_BLOCKS.get()
        );
    }

    public static SpeakerSettings constrain(SpeakerSettings settings) {
        return SpeakerConfigPolicy.constrain(settings, MAXIMUM_RANGE_BLOCKS.get());
    }

    public static int maximumRangeBlocks() {
        return SpeakerConfigPolicy.maximumRange(MAXIMUM_RANGE_BLOCKS.get());
    }
}
