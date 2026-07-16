package com.pockettune.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class TrackMetadataTest {
    @Test
    void ordersThumbnailCandidatesFromHighestToLowestQuality() {
        TrackMetadata track = TrackMetadata.fallback("dQw4w9WgXcQ");

        assertEquals(List.of(
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"
        ), track.thumbnailUrls());
        assertEquals(track.thumbnailUrls().getFirst(), track.thumbnailUrl());
    }

    @Test
    void preservesWholeEmojiWhileEnforcingUtf16NetworkLimit() {
        TrackMetadata track = new TrackMetadata(
                "dQw4w9WgXcQ",
                "🎵".repeat(101),
                "Artist",
                1_000L
        );

        assertEquals(200, track.title().length());
        assertFalse(Character.isHighSurrogate(track.title().charAt(track.title().length() - 1)));
    }
}
