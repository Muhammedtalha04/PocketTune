package com.pockettune.model;

public final class PlaybackTiming {
    public static final long SERVER_FINISH_REPORT_MAX_TOLERANCE_MILLIS = 2_000L;

    private PlaybackTiming() {
    }

    public static boolean hasReachedKnownEnd(long elapsedMillis, long durationMillis) {
        return durationMillis > 0L && elapsedMillis >= durationMillis;
    }

    public static boolean isServerKnownEndReportPlausible(long serverElapsedMillis, long durationMillis) {
        if (serverElapsedMillis < 0L || durationMillis <= 0L) {
            return false;
        }
        long proportionalTolerance = Math.max(50L, durationMillis / 20L);
        long tolerance = Math.min(SERVER_FINISH_REPORT_MAX_TOLERANCE_MILLIS, proportionalTolerance);
        return serverElapsedMillis >= Math.max(0L, durationMillis - tolerance);
    }
}
