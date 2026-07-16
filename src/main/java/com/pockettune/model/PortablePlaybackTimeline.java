package com.pockettune.model;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Advances portable playback without depending on Minecraft runtime classes.
 */
public final class PortablePlaybackTimeline {
    private static final int MAX_SHUFFLE_TRANSITIONS_PER_UPDATE = 4_096;

    private PortablePlaybackTimeline() {
    }

    public static Result advance(
            List<TrackMetadata> queue,
            SpeakerSettings settings,
            int playlistIndex,
            long trackSequence,
            long elapsedMillis,
            boolean playing,
            boolean paused
    ) {
        return advance(
                queue,
                settings,
                playlistIndex,
                trackSequence,
                elapsedMillis,
                playing,
                paused,
                ThreadLocalRandom.current()
        );
    }

    static Result advance(
            List<TrackMetadata> queue,
            SpeakerSettings settings,
            int playlistIndex,
            long trackSequence,
            long elapsedMillis,
            boolean playing,
            boolean paused,
            RandomGenerator random
    ) {
        if (queue == null || queue.isEmpty()) {
            return new Result(0, trackSequence, 0L, false, false);
        }

        SpeakerSettings safeSettings = settings == null ? SpeakerSettings.DEFAULT : settings;
        int nextIndex = Math.floorMod(playlistIndex, queue.size());
        long nextSequence = Math.max(0L, trackSequence);
        long nextElapsed = Math.max(0L, elapsedMillis);
        boolean nextPlaying = playing;
        boolean nextPaused = paused && playing;
        if (!nextPlaying || nextPaused) {
            return result(nextIndex, nextSequence, nextElapsed, nextPlaying, nextPaused);
        }

        long knownCycleDuration = safeSettings.repeatMode() == SpeakerSettings.RepeatMode.ALL
                && !safeSettings.shuffle()
                ? knownCycleDuration(queue)
                : 0L;
        int shuffleTransitions = 0;

        while (nextPlaying) {
            long duration = queue.get(nextIndex).durationMillis();
            if (duration <= 0L || nextElapsed < duration) {
                break;
            }

            long overflow = nextElapsed - duration;
            if (safeSettings.repeatMode() == SpeakerSettings.RepeatMode.ONE) {
                long repeats = 1L + overflow / duration;
                nextSequence = addSequence(nextSequence, repeats);
                nextElapsed = overflow % duration;
                break;
            }

            nextSequence = addSequence(nextSequence, 1L);
            if (safeSettings.shuffle() && queue.size() > 1) {
                int randomIndex = random.nextInt(queue.size() - 1);
                nextIndex = randomIndex >= nextIndex ? randomIndex + 1 : randomIndex;
                nextElapsed = overflow;
                shuffleTransitions++;
                if (shuffleTransitions >= MAX_SHUFFLE_TRANSITIONS_PER_UPDATE) {
                    long nextDuration = queue.get(nextIndex).durationMillis();
                    if (nextDuration > 0L) {
                        nextElapsed %= nextDuration;
                    }
                    break;
                }
                continue;
            }

            if (nextIndex == queue.size() - 1
                    && safeSettings.repeatMode() == SpeakerSettings.RepeatMode.OFF) {
                nextElapsed = duration;
                nextPlaying = false;
                nextPaused = false;
                break;
            }

            nextIndex = (nextIndex + 1) % queue.size();
            nextElapsed = overflow;
            if (knownCycleDuration > 0L && nextElapsed >= knownCycleDuration) {
                long completeCycles = nextElapsed / knownCycleDuration;
                nextSequence = addSequence(nextSequence, completeCycles * queue.size());
                nextElapsed %= knownCycleDuration;
            }
        }

        return result(nextIndex, nextSequence, nextElapsed, nextPlaying, nextPaused);
    }

    private static Result result(
            int index,
            long sequence,
            long elapsed,
            boolean playing,
            boolean paused
    ) {
        long clampedElapsed = Math.min(TrackMetadata.MAX_DURATION_MILLIS, Math.max(0L, elapsed));
        return new Result(index, sequence, clampedElapsed, playing, paused);
    }

    private static long knownCycleDuration(List<TrackMetadata> queue) {
        long total = 0L;
        for (TrackMetadata track : queue) {
            if (track.durationMillis() <= 0L) {
                return 0L;
            }
            if (total > Long.MAX_VALUE - track.durationMillis()) {
                return Long.MAX_VALUE;
            }
            total += track.durationMillis();
        }
        return total;
    }

    private static long addSequence(long sequence, long increments) {
        // The sequence is a non-negative generation counter. Masking makes overflow wrap to zero.
        return (sequence + increments) & Long.MAX_VALUE;
    }

    public record Result(
            int playlistIndex,
            long trackSequence,
            long elapsedMillis,
            boolean playing,
            boolean paused
    ) {
    }
}
