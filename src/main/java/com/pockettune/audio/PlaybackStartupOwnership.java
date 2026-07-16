package com.pockettune.audio;

/**
 * Pure ownership check shared by block and portable asynchronous playback startup workers.
 */
public final class PlaybackStartupOwnership {
    private PlaybackStartupOwnership() {
    }

    public static boolean isCurrent(
            long requestedGeneration,
            long currentGeneration,
            long requestedSessionEpoch,
            long currentSessionEpoch,
            boolean disposed
    ) {
        return !disposed
                && requestedGeneration == currentGeneration
                && requestedSessionEpoch == currentSessionEpoch;
    }
}
