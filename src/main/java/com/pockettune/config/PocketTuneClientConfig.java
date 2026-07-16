package com.pockettune.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class PocketTuneClientConfig {
    public static final int DEFAULT_MAX_CONCURRENT_MPV_CONTROLLERS = 8;
    public static final int MIN_MAX_CONCURRENT_MPV_CONTROLLERS = 1;
    public static final int MAX_MAX_CONCURRENT_MPV_CONTROLLERS = 32;
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue SHOW_GUI_NOTIFICATIONS;
    public static final ModConfigSpec.IntValue NOTIFICATION_DURATION_SECONDS;
    public static final ModConfigSpec.BooleanValue PAUSE_AUDIO_ON_PAUSE_SCREEN;
    public static final ModConfigSpec.EnumValue<ThumbnailQuality> THUMBNAIL_QUALITY;
    public static final ModConfigSpec.IntValue THUMBNAIL_CACHE_ENTRIES;
    public static final ModConfigSpec.BooleanValue SHOW_PORTABLE_HUD;
    public static final ModConfigSpec.BooleanValue SHOW_PORTABLE_OVERLAY_WHEN_HUD_HIDDEN;
    public static final ModConfigSpec.IntValue MAX_CONCURRENT_MPV_CONTROLLERS;

    public static final ModConfigSpec.DoubleValue FULL_VOLUME_DISTANCE;
    public static final ModConfigSpec.IntValue OCCLUSION_MAX_REDUCTION_PERCENT;
    public static final ModConfigSpec.IntValue OCCLUSION_SMOOTHING_PERCENT;
    public static final ModConfigSpec.IntValue OUT_OF_RANGE_GRACE_SECONDS;

    public static final ModConfigSpec.BooleanValue TEST_MODE_ENABLED;
    public static final ModConfigSpec.BooleanValue VISUALIZE_RANGE;
    public static final ModConfigSpec.BooleanValue VISUALIZE_ATTENUATION;
    public static final ModConfigSpec.BooleanValue VISUALIZE_OCCLUSION;
    public static final ModConfigSpec.BooleanValue SHOW_TEST_HUD;
    public static final ModConfigSpec.IntValue VISUALIZATION_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue VISUALIZATION_SEGMENTS;
    public static final ModConfigSpec.IntValue MAX_VISUALIZATION_DISTANCE;
    public static final ModConfigSpec.IntValue MAX_VISUALIZED_SPEAKERS;

    public static final ModConfigSpec.BooleanValue BLOCK_INTERACTION_DEBUG_ENABLED;
    public static final ModConfigSpec.BooleanValue SHOW_BLOCK_INTERACTION_HUD;
    public static final ModConfigSpec.BooleanValue LOG_BLOCK_INTERACTION_EVENTS;
    public static final ModConfigSpec.IntValue BLOCK_INTERACTION_HISTORY_SIZE;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("interface");
        SHOW_GUI_NOTIFICATIONS = BUILDER
                .comment("Show PocketTune operation results inside the speaker GUI.")
                .define("showGuiNotifications", true);
        NOTIFICATION_DURATION_SECONDS = BUILDER
                .comment("How long completed GUI notifications remain visible.")
                .defineInRange("notificationDurationSeconds", 5, 1, 15);
        PAUSE_AUDIO_ON_PAUSE_SCREEN = BUILDER
                .comment("Pause local PocketTune audio for the complete ESC pause-menu chain until gameplay resumes.")
                .define("pauseAudioOnPauseScreen", true);
        THUMBNAIL_QUALITY = BUILDER
                .comment("YouTube thumbnail download quality and fallback policy.")
                .defineEnum("thumbnailQuality", ThumbnailQuality.MAXIMUM);
        THUMBNAIL_CACHE_ENTRIES = BUILDER
                .comment("Maximum number of decoded YouTube thumbnails kept in memory.")
                .defineInRange("thumbnailCacheEntries", 64, 16, 256);
        SHOW_PORTABLE_HUD = BUILDER
                .comment("Show the responsive now-playing overlay for a portable speaker.")
                .define("showPortableSpeakerHud", true);
        SHOW_PORTABLE_OVERLAY_WHEN_HUD_HIDDEN = BUILDER
                .comment("Keep the portable music overlay visible when Minecraft HUD is hidden with F1.")
                .define("showPortableOverlayWhenHudHidden", true);
        BUILDER.pop();

        BUILDER.push("audioRuntime");
        MAX_CONCURRENT_MPV_CONTROLLERS = BUILDER
                .comment(
                        "Hard limit for simultaneously active local mpv playback processes.",
                        "Higher values allow more nearby speakers but consume more CPU and memory."
                )
                .defineInRange(
                        "maximumConcurrentMpvControllers",
                        DEFAULT_MAX_CONCURRENT_MPV_CONTROLLERS,
                        MIN_MAX_CONCURRENT_MPV_CONTROLLERS,
                        MAX_MAX_CONCURRENT_MPV_CONTROLLERS
                );
        BUILDER.pop();

        BUILDER.push("spatialAudio");
        FULL_VOLUME_DISTANCE = BUILDER
                .comment("Distance in blocks where the configured speaker volume is still fully applied.")
                .defineInRange("fullVolumeDistance", 4.0D, 1.0D, 16.0D);
        OCCLUSION_MAX_REDUCTION_PERCENT = BUILDER
                .comment("Maximum volume reduction when every wall-occlusion ray is blocked.")
                .defineInRange("occlusionMaxReductionPercent", 65, 0, 100);
        OCCLUSION_SMOOTHING_PERCENT = BUILDER
                .comment("How quickly wall occlusion reacts. Higher values react faster.")
                .defineInRange("occlusionSmoothingPercent", 35, 1, 100);
        OUT_OF_RANGE_GRACE_SECONDS = BUILDER
                .comment("Delay before the local mpv process is suspended after leaving speaker range.")
                .defineInRange("outOfRangeGraceSeconds", 10, 0, 30);
        BUILDER.pop();

        BUILDER.push("testMode");
        TEST_MODE_ENABLED = BUILDER
                .comment("Development-only spatial audio visualization. Disabled by default.")
                .define("enabled", false);
        VISUALIZE_RANGE = BUILDER.define("visualizeRangeBoundary", true);
        VISUALIZE_ATTENUATION = BUILDER.define("visualizeAttenuationZones", true);
        VISUALIZE_OCCLUSION = BUILDER.define("visualizeOcclusionRays", true);
        SHOW_TEST_HUD = BUILDER.define("showMeasurementHud", true);
        VISUALIZATION_INTERVAL_TICKS = BUILDER
                .comment("Ticks between visualization refreshes. Lower values are smoother but heavier.")
                .defineInRange("refreshIntervalTicks", 10, 2, 40);
        VISUALIZATION_SEGMENTS = BUILDER
                .comment("Particle segments per range ring.")
                .defineInRange("ringSegments", 32, 12, 64);
        MAX_VISUALIZATION_DISTANCE = BUILDER
                .comment("Only loaded speakers within this distance are visualized.")
                .defineInRange("maximumDistance", 160, 16, 256);
        MAX_VISUALIZED_SPEAKERS = BUILDER
                .comment("Maximum speakers visualized at the same time.")
                .defineInRange("maximumSpeakers", 4, 1, 16);
        BUILDER.pop();

        BUILDER.push("blockInteractionDebug");
        BLOCK_INTERACTION_DEBUG_ENABLED = BUILDER
                .comment("Trace speaker pickup events, packets, callbacks, BlockEntity lifecycle and state sync.")
                .define("enabled", false);
        SHOW_BLOCK_INTERACTION_HUD = BUILDER
                .comment("Show recent block interaction traces in a client HUD while debug is enabled.")
                .define("showHud", true);
        LOG_BLOCK_INTERACTION_EVENTS = BUILDER
                .comment("Write block interaction traces to the Minecraft log while debug is enabled.")
                .define("writeToLog", false);
        BLOCK_INTERACTION_HISTORY_SIZE = BUILDER
                .comment("Maximum trace entries retained and shown by the block interaction debugger.")
                .defineInRange("historySize", 12, 6, 24);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private PocketTuneClientConfig() {
    }

    public enum ThumbnailQuality {
        MAXIMUM("Maksimum"),
        HIGH("Yüksek"),
        BALANCED("Dengeli");

        private final String displayName;

        ThumbnailQuality(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public ThumbnailQuality next() {
            ThumbnailQuality[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }
}
