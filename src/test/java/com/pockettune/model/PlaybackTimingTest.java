package com.pockettune.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlaybackTimingTest {
    @Test
    void knownDurationAdvancesOnlyWhenServerClockReachesTheEnd() {
        assertFalse(PlaybackTiming.hasReachedKnownEnd(212_999L, 213_000L));
        assertTrue(PlaybackTiming.hasReachedKnownEnd(213_000L, 213_000L));
        assertTrue(PlaybackTiming.hasReachedKnownEnd(214_000L, 213_000L));
        assertFalse(PlaybackTiming.hasReachedKnownEnd(214_000L, 0L));
    }

    @Test
    void finishReportRequiresKnownDurationAndServerClockNearEnd() {
        assertFalse(PlaybackTiming.isServerKnownEndReportPlausible(211_999L, 214_000L));
        assertTrue(PlaybackTiming.isServerKnownEndReportPlausible(212_000L, 214_000L));
        assertTrue(PlaybackTiming.isServerKnownEndReportPlausible(214_000L, 214_000L));
        assertFalse(PlaybackTiming.isServerKnownEndReportPlausible(214_000L, 0L));
        assertFalse(PlaybackTiming.isServerKnownEndReportPlausible(214_000L, -1L));
    }

    @Test
    void shortTrackCannotBeAdvancedImmediatelyByFinishReport() {
        assertFalse(PlaybackTiming.isServerKnownEndReportPlausible(0L, 1_000L));
        assertFalse(PlaybackTiming.isServerKnownEndReportPlausible(949L, 1_000L));
        assertTrue(PlaybackTiming.isServerKnownEndReportPlausible(950L, 1_000L));
    }
}
