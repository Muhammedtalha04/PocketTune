package com.pockettune.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Machine-local settings that are needed on both an integrated client and a dedicated server.
 * These values deliberately stay out of world/server gameplay configuration because executable
 * locations are properties of the computer running PocketTune.
 */
public final class PocketTuneCommonConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> YT_DLP_PATH_OVERRIDE;
    public static final ModConfigSpec.ConfigValue<String> MPV_PATH_OVERRIDE;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("externalTools");
        YT_DLP_PATH_OVERRIDE = BUILDER
                .comment(
                        "Optional path to the yt-dlp executable on this computer.",
                        "Relative paths are resolved from the Minecraft game directory.",
                        "Leave empty to search PATH and, on Windows, WinGet installation folders.",
                        "A non-empty invalid path is reported as an error and is never silently ignored."
                )
                .define("ytDlpPathOverride", "");
        MPV_PATH_OVERRIDE = BUILDER
                .comment(
                        "Optional path to the mpv executable on this computer.",
                        "Relative paths are resolved from the Minecraft game directory.",
                        "Leave empty to search PATH and, on Windows, WinGet installation folders.",
                        "A non-empty invalid path is reported as an error and is never silently ignored."
                )
                .define("mpvPathOverride", "");
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private PocketTuneCommonConfig() {
    }
}
