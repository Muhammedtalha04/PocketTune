package com.pockettune.model;

public record SpeakerSettings(
        double volumePercent,
        int rangeBlocks,
        FadeType fadeType,
        boolean wallOcclusion,
        double bassDb,
        double midDb,
        double trebleDb,
        boolean shuffle,
        RepeatMode repeatMode
) {
    public static final double MIN_VOLUME = 0.0D;
    public static final double MAX_VOLUME = 100.0D;
    public static final double DEFAULT_VOLUME = 80.0D;
    public static final int MIN_RANGE = 4;
    public static final int MAX_RANGE = 128;
    public static final double MIN_EQ_DB = -12.0D;
    public static final double MAX_EQ_DB = 12.0D;
    public static final double DEFAULT_EQ_DB = 0.0D;
    public static final SpeakerSettings DEFAULT = new SpeakerSettings(
            DEFAULT_VOLUME,
            32,
            FadeType.QUADRATIC,
            false,
            DEFAULT_EQ_DB,
            DEFAULT_EQ_DB,
            DEFAULT_EQ_DB,
            false,
            RepeatMode.ALL
    );

    public SpeakerSettings {
        volumePercent = clampFinite(volumePercent, MIN_VOLUME, MAX_VOLUME, DEFAULT_VOLUME);
        rangeBlocks = Math.max(MIN_RANGE, Math.min(MAX_RANGE, rangeBlocks));
        fadeType = fadeType == null ? FadeType.QUADRATIC : fadeType;
        bassDb = clampFinite(bassDb, MIN_EQ_DB, MAX_EQ_DB, DEFAULT_EQ_DB);
        midDb = clampFinite(midDb, MIN_EQ_DB, MAX_EQ_DB, DEFAULT_EQ_DB);
        trebleDb = clampFinite(trebleDb, MIN_EQ_DB, MAX_EQ_DB, DEFAULT_EQ_DB);
        repeatMode = repeatMode == null ? RepeatMode.ALL : repeatMode;
    }

    private static double clampFinite(double value, double minimum, double maximum, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    public enum FadeType {
        LINEAR("Linear"),
        QUADRATIC("Quadratic (Smooth)"),
        LOGARITHMIC("Logarithmic");

        private final String displayName;

        FadeType(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public enum RepeatMode {
        OFF("Off"),
        ALL("All"),
        ONE("One Track");

        private final String displayName;

        RepeatMode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }
}
