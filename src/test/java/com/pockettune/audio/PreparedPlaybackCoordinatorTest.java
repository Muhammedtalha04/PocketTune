package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PreparedPlaybackCoordinatorTest {
    @Test
    void startupCommitsMediaAndSeekBeforeApplyingEffectivePause() throws ExternalProcessException {
        RecordingOperations operations = new RecordingOperations(true);

        PreparedPlaybackCoordinator.prepare(operations, 42.25D, false);

        assertEquals(
                List.of("media-ready", "seek:42.25", "effective-pause:false", "progress"),
                operations.events
        );
    }

    @Test
    void requestedPauseStopsAfterVerifiedSeekWithoutAdvancingPlayback() throws ExternalProcessException {
        RecordingOperations operations = new RecordingOperations();
        operations.effectivePause = true;

        PreparedPlaybackCoordinator.prepare(operations, 9.5D, true);

        assertEquals(
                List.of("media-ready", "seek:9.5", "effective-pause:true"),
                operations.events
        );
    }

    @Test
    void resumedDuringResolveCanBeRevalidatedWithoutRepeatingMediaLoadOrInitialSeek()
            throws ExternalProcessException {
        RecordingOperations operations = new RecordingOperations(true);

        PreparedPlaybackCoordinator.revalidateAfterResume(operations, 27.75D, false);

        assertEquals(
                List.of("force-pause", "seek:27.75", "effective-pause:false", "progress"),
                operations.events
        );
    }

    @Test
    void stalledStartupGetsOneSilentPauseSeekRecoveryBeforeEscalation() {
        RecordingOperations operations = new RecordingOperations(false, false);

        assertThrows(
                ExternalProcessException.class,
                () -> PreparedPlaybackCoordinator.prepare(operations, 18.0D, false)
        );

        assertEquals(
                List.of(
                        "media-ready",
                        "seek:18.0",
                        "effective-pause:false",
                        "progress",
                        "force-pause",
                        "seek:18.0",
                        "effective-pause:false",
                        "progress"
                ),
                operations.events
        );
    }

    private static final class RecordingOperations implements PreparedPlaybackCoordinator.Operations {
        private final List<String> events = new ArrayList<>();
        private final Deque<Boolean> progressResults = new ArrayDeque<>();
        private boolean effectivePause;

        private RecordingOperations(Boolean... progressResults) {
            this.progressResults.addAll(List.of(progressResults));
        }

        @Override
        public void awaitMediaReady() {
            events.add("media-ready");
        }

        @Override
        public void seekAndVerify(double positionSeconds) {
            events.add("seek:" + positionSeconds);
        }

        @Override
        public boolean applyEffectivePause(boolean playbackPaused) {
            events.add("effective-pause:" + playbackPaused);
            return effectivePause;
        }

        @Override
        public boolean awaitPlaybackProgress() {
            events.add("progress");
            return progressResults.removeFirst();
        }

        @Override
        public void forcePause() {
            events.add("force-pause");
        }
    }
}
