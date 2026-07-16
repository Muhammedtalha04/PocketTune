package com.pockettune.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PortablePlaybackTimelineTest {
    private static final TrackMetadata FIRST = new TrackMetadata(
            "dQw4w9WgXcQ", "Birinci", "PocketTune", 10_000L);
    private static final TrackMetadata SECOND = new TrackMetadata(
            "9bZkp7q19f0", "İkinci", "PocketTune", 20_000L);

    @Test
    void carriesOverflowIntoTheNextTrack() {
        PortablePlaybackTimeline.Result result = PortablePlaybackTimeline.advance(
                List.of(FIRST, SECOND),
                settings(false, SpeakerSettings.RepeatMode.ALL),
                0,
                4L,
                15_000L,
                true,
                false
        );

        assertEquals(1, result.playlistIndex());
        assertEquals(5L, result.trackSequence());
        assertEquals(5_000L, result.elapsedMillis());
        assertTrue(result.playing());
    }

    @Test
    void repeatOneSkipsEveryCompletedLoopWithoutLosingTime() {
        PortablePlaybackTimeline.Result result = PortablePlaybackTimeline.advance(
                List.of(FIRST),
                settings(false, SpeakerSettings.RepeatMode.ONE),
                0,
                7L,
                45_500L,
                true,
                false
        );

        assertEquals(0, result.playlistIndex());
        assertEquals(11L, result.trackSequence());
        assertEquals(5_500L, result.elapsedMillis());
        assertTrue(result.playing());
    }

    @Test
    void repeatAllFastForwardsAcrossCompleteQueueCycles() {
        PortablePlaybackTimeline.Result result = PortablePlaybackTimeline.advance(
                List.of(FIRST, SECOND),
                settings(false, SpeakerSettings.RepeatMode.ALL),
                0,
                0L,
                100_000L,
                true,
                false
        );

        assertEquals(1, result.playlistIndex());
        assertEquals(7L, result.trackSequence());
        assertEquals(0L, result.elapsedMillis());
    }

    @Test
    void repeatOffStopsAtTheFinalTrackEnd() {
        PortablePlaybackTimeline.Result result = PortablePlaybackTimeline.advance(
                List.of(FIRST, SECOND),
                settings(false, SpeakerSettings.RepeatMode.OFF),
                1,
                12L,
                21_000L,
                true,
                true
        );

        // A paused timeline is intentionally immutable.
        assertTrue(result.playing());
        assertTrue(result.paused());
        assertEquals(21_000L, result.elapsedMillis());

        result = PortablePlaybackTimeline.advance(
                List.of(FIRST, SECOND),
                settings(false, SpeakerSettings.RepeatMode.OFF),
                1,
                12L,
                21_000L,
                true,
                false
        );
        assertFalse(result.playing());
        assertFalse(result.paused());
        assertEquals(20_000L, result.elapsedMillis());
        assertEquals(13L, result.trackSequence());
    }

    @Test
    void shuffleNeverSelectsTheTrackThatJustFinished() {
        RandomGenerator deterministic = new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0L;
            }
        };
        PortablePlaybackTimeline.Result result = PortablePlaybackTimeline.advance(
                List.of(FIRST, SECOND),
                settings(true, SpeakerSettings.RepeatMode.ALL),
                0,
                0L,
                10_001L,
                true,
                false,
                deterministic
        );

        assertEquals(1, result.playlistIndex());
        assertEquals(1L, result.elapsedMillis());
    }

    private static SpeakerSettings settings(boolean shuffle, SpeakerSettings.RepeatMode repeatMode) {
        return new SpeakerSettings(
                80.0D,
                32,
                SpeakerSettings.FadeType.QUADRATIC,
                false,
                0.0D,
                0.0D,
                0.0D,
                shuffle,
                repeatMode
        );
    }
}
