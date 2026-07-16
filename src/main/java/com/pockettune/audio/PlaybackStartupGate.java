package com.pockettune.audio;

import java.util.Objects;

/**
 * Coordinates one playback startup per track identity and applies bounded retry backoff.
 * All methods are synchronized because completion callbacks run on the startup executor.
 */
public final class PlaybackStartupGate {
    private static final int[] RETRY_DELAYS_TICKS = {5, 20, 60};
    private static final int MAX_ATTEMPTS = RETRY_DELAYS_TICKS.length + 1;

    private String videoId = "";
    private long trackSequence = -1L;
    private int attempts;
    private int retryTicks;
    private boolean inFlight;
    private boolean terminalFailure;
    private long tokenSequence;
    private long activeToken;

    public synchronized Decision tick(String requestedVideoId, long requestedSequence, boolean controllerReady) {
        Objects.requireNonNull(requestedVideoId, "requestedVideoId");
        boolean identityChanged = !videoId.equals(requestedVideoId) || trackSequence != requestedSequence;
        if (identityChanged) {
            videoId = requestedVideoId;
            trackSequence = requestedSequence;
            attempts = 0;
            retryTicks = 0;
            inFlight = false;
            terminalFailure = false;
            activeToken = 0L;
        }

        if (controllerReady) {
            attempts = 0;
            retryTicks = 0;
            inFlight = false;
            terminalFailure = false;
            activeToken = 0L;
            return new Decision(Action.NONE, 0, identityChanged, 0L);
        }
        if (inFlight || terminalFailure || requestedVideoId.isBlank()) {
            return new Decision(Action.NONE, attempts, identityChanged, activeToken);
        }
        if (retryTicks > 0 && --retryTicks > 0) {
            return new Decision(Action.NONE, attempts, identityChanged, 0L);
        }

        attempts++;
        inFlight = true;
        activeToken = ++tokenSequence;
        return new Decision(Action.START, attempts, identityChanged, activeToken);
    }

    public synchronized boolean completeSuccess(
            String completedVideoId,
            long completedSequence,
            long completedToken
    ) {
        if (!matches(completedVideoId, completedSequence)
                || !inFlight
                || activeToken != completedToken) {
            return false;
        }
        attempts = 0;
        retryTicks = 0;
        inFlight = false;
        terminalFailure = false;
        activeToken = 0L;
        return true;
    }

    public synchronized FailureResult completeFailure(
            String failedVideoId,
            long failedSequence,
            long failedToken
    ) {
        if (!matches(failedVideoId, failedSequence)
                || !inFlight
                || activeToken != failedToken) {
            return FailureResult.STALE;
        }
        inFlight = false;
        activeToken = 0L;
        if (attempts >= MAX_ATTEMPTS) {
            terminalFailure = true;
            retryTicks = 0;
            return FailureResult.TERMINAL;
        }
        retryTicks = RETRY_DELAYS_TICKS[attempts - 1];
        return FailureResult.RETRY_SCHEDULED;
    }

    public synchronized void adoptReady(String adoptedVideoId, long adoptedSequence) {
        videoId = Objects.requireNonNull(adoptedVideoId, "adoptedVideoId");
        trackSequence = adoptedSequence;
        attempts = 0;
        retryTicks = 0;
        inFlight = false;
        terminalFailure = false;
        activeToken = 0L;
    }

    public synchronized void cancel() {
        videoId = "";
        trackSequence = -1L;
        attempts = 0;
        retryTicks = 0;
        inFlight = false;
        terminalFailure = false;
        activeToken = 0L;
    }

    public synchronized boolean inFlight() {
        return inFlight;
    }

    public synchronized boolean retryPending() {
        return retryTicks > 0;
    }

    public synchronized boolean terminalFailure() {
        return terminalFailure;
    }

    public synchronized int attempts() {
        return attempts;
    }

    private boolean matches(String candidateVideoId, long candidateSequence) {
        return videoId.equals(candidateVideoId) && trackSequence == candidateSequence;
    }

    public enum Action {
        NONE,
        START
    }

    public enum FailureResult {
        RETRY_SCHEDULED,
        TERMINAL,
        STALE
    }

    public record Decision(Action action, int attempt, boolean identityChanged, long token) {
        public boolean shouldStart() {
            return action == Action.START;
        }
    }
}
