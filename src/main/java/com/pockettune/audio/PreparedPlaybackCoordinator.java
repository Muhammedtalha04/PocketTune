package com.pockettune.audio;

/**
 * Enforces the prepared-playback transaction independently from mpv transport details.
 * The mpv process has already been launched paused and muted before this sequence begins.
 */
final class PreparedPlaybackCoordinator {
    private PreparedPlaybackCoordinator() {
    }

    static void prepare(
            Operations operations,
            double targetPositionSeconds,
            boolean playbackPaused
    ) throws ExternalProcessException {
        operations.awaitMediaReady();
        operations.seekAndVerify(targetPositionSeconds);
        verifyAfterSeek(operations, targetPositionSeconds, playbackPaused);
    }

    static void verifyAfterSeek(
            Operations operations,
            double targetPositionSeconds,
            boolean playbackPaused
    ) throws ExternalProcessException {
        boolean remainsPaused = operations.applyEffectivePause(playbackPaused);
        if (remainsPaused || operations.awaitPlaybackProgress()) {
            return;
        }

        // A loaded but non-advancing stream is nudged once while launch volume is still zero.
        // Continued failure is escalated so the owner can perform its bounded process-level retry.
        operations.forcePause();
        operations.seekAndVerify(targetPositionSeconds);
        remainsPaused = operations.applyEffectivePause(playbackPaused);
        if (!remainsPaused && !operations.awaitPlaybackProgress()) {
            throw new ExternalProcessException(
                    "mpv loaded the media but playback did not advance; an automatic retry will be attempted."
            );
        }
    }

    static void revalidateAfterResume(
            Operations operations,
            double targetPositionSeconds,
            boolean playbackPaused
    ) throws ExternalProcessException {
        operations.forcePause();
        operations.seekAndVerify(targetPositionSeconds);
        verifyAfterSeek(operations, targetPositionSeconds, playbackPaused);
    }

    interface Operations {
        void awaitMediaReady() throws ExternalProcessException;

        void seekAndVerify(double positionSeconds) throws ExternalProcessException;

        boolean applyEffectivePause(boolean playbackPaused) throws ExternalProcessException;

        boolean awaitPlaybackProgress() throws ExternalProcessException;

        void forcePause() throws ExternalProcessException;
    }
}
