package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlaybackStartupGateTest {
    @Test
    void duplicateTicksCannotStartTheSameTrackTwiceWhileAttemptIsInFlight() {
        PlaybackStartupGate gate = new PlaybackStartupGate();

        assertTrue(gate.tick("dQw4w9WgXcQ", 3L, false).shouldStart());
        assertFalse(gate.tick("dQw4w9WgXcQ", 3L, false).shouldStart());
        assertTrue(gate.inFlight());
        assertEquals(1, gate.attempts());
    }

    @Test
    void transientFailureRetriesOnlyAfterBoundedBackoff() {
        PlaybackStartupGate gate = new PlaybackStartupGate();
        PlaybackStartupGate.Decision first = gate.tick("dQw4w9WgXcQ", 3L, false);

        assertEquals(PlaybackStartupGate.FailureResult.RETRY_SCHEDULED,
                gate.completeFailure("dQw4w9WgXcQ", 3L, first.token()));
        for (int tick = 0; tick < 4; tick++) {
            assertFalse(gate.tick("dQw4w9WgXcQ", 3L, false).shouldStart());
        }
        PlaybackStartupGate.Decision retry = gate.tick("dQw4w9WgXcQ", 3L, false);
        assertTrue(retry.shouldStart());
        assertEquals(2, retry.attempt());
    }

    @Test
    void startupStopsAfterInitialAttemptAndThreeRetries() {
        PlaybackStartupGate gate = new PlaybackStartupGate();
        String videoId = "dQw4w9WgXcQ";
        long sequence = 9L;

        for (int expectedAttempt = 1; expectedAttempt <= 4; expectedAttempt++) {
            PlaybackStartupGate.Decision decision = expectedAttempt == 1
                    ? gate.tick(videoId, sequence, false)
                    : advanceUntilStart(gate, videoId, sequence, 100);
            assertTrue(decision.shouldStart());
            assertEquals(expectedAttempt, decision.attempt());
            PlaybackStartupGate.FailureResult failure =
                    gate.completeFailure(videoId, sequence, decision.token());
            assertEquals(
                    expectedAttempt == 4
                            ? PlaybackStartupGate.FailureResult.TERMINAL
                            : PlaybackStartupGate.FailureResult.RETRY_SCHEDULED,
                    failure
            );
        }

        assertTrue(gate.terminalFailure());
        for (int tick = 0; tick < 120; tick++) {
            assertFalse(gate.tick(videoId, sequence, false).shouldStart());
        }
    }

    @Test
    void newTrackIdentityInvalidatesOldAsyncCompletionAndStartsImmediately() {
        PlaybackStartupGate gate = new PlaybackStartupGate();
        PlaybackStartupGate.Decision first = gate.tick("dQw4w9WgXcQ", 1L, false);

        PlaybackStartupGate.Decision next = gate.tick("M7lc1UVf-VE", 2L, false);

        assertTrue(next.shouldStart());
        assertTrue(next.identityChanged());
        assertFalse(gate.completeSuccess("dQw4w9WgXcQ", 1L, first.token()));
        assertEquals(PlaybackStartupGate.FailureResult.STALE,
                gate.completeFailure("dQw4w9WgXcQ", 1L, first.token()));
        assertTrue(gate.completeSuccess("M7lc1UVf-VE", 2L, next.token()));
    }

    @Test
    void readyControllerClearsRetryAndTerminalStateForItsIdentity() {
        PlaybackStartupGate gate = new PlaybackStartupGate();
        PlaybackStartupGate.Decision first = gate.tick("dQw4w9WgXcQ", 4L, false);
        gate.completeFailure("dQw4w9WgXcQ", 4L, first.token());

        PlaybackStartupGate.Decision decision = gate.tick("dQw4w9WgXcQ", 4L, true);

        assertFalse(decision.shouldStart());
        assertFalse(gate.inFlight());
        assertFalse(gate.retryPending());
        assertFalse(gate.terminalFailure());
        assertEquals(0, gate.attempts());
    }

    @Test
    void cancelAndRestartWithSameIdentityRejectsOldAttemptCompletion() {
        PlaybackStartupGate gate = new PlaybackStartupGate();
        PlaybackStartupGate.Decision oldAttempt = gate.tick("dQw4w9WgXcQ", 7L, false);

        gate.cancel();
        PlaybackStartupGate.Decision newAttempt = gate.tick("dQw4w9WgXcQ", 7L, false);

        assertTrue(newAttempt.shouldStart());
        assertFalse(gate.completeSuccess("dQw4w9WgXcQ", 7L, oldAttempt.token()));
        assertEquals(
                PlaybackStartupGate.FailureResult.STALE,
                gate.completeFailure("dQw4w9WgXcQ", 7L, oldAttempt.token())
        );
        assertTrue(gate.inFlight());
        assertTrue(gate.completeSuccess("dQw4w9WgXcQ", 7L, newAttempt.token()));
    }

    private static PlaybackStartupGate.Decision advanceUntilStart(
            PlaybackStartupGate gate,
            String videoId,
            long sequence,
            int maximumTicks
    ) {
        for (int tick = 0; tick < maximumTicks; tick++) {
            PlaybackStartupGate.Decision decision = gate.tick(videoId, sequence, false);
            if (decision.shouldStart()) {
                return decision;
            }
        }
        throw new AssertionError("Retry did not start within " + maximumTicks + " ticks");
    }
}
