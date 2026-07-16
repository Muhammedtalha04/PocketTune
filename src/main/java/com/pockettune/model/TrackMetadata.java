package com.pockettune.model;

import com.pockettune.audio.YtDlpResolver;

import java.util.List;

public record TrackMetadata(String videoId, String title, String artist, long durationMillis) {
    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_ARTIST_LENGTH = 120;
    public static final long MAX_DURATION_MILLIS = 12L * 60L * 60L * 1_000L;

    public TrackMetadata {
        if (!YtDlpResolver.isValidVideoId(videoId)) {
            throw new IllegalArgumentException("Invalid YouTube video ID");
        }
        title = sanitize(title, MAX_TITLE_LENGTH, "YouTube Video");
        artist = sanitize(artist, MAX_ARTIST_LENGTH, "YouTube");
        durationMillis = Math.max(0L, Math.min(MAX_DURATION_MILLIS, durationMillis));
    }

    public static TrackMetadata fallback(String videoId) {
        return new TrackMetadata(videoId, "YouTube Video", "YouTube", 0L);
    }

    public String videoUrl() {
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    public String thumbnailUrl() {
        return thumbnailUrls().getFirst();
    }

    public List<String> thumbnailUrls() {
        String base = "https://i.ytimg.com/vi/" + videoId + "/";
        return List.of(
                base + "maxresdefault.jpg",
                base + "hqdefault.jpg",
                base + "mqdefault.jpg"
        );
    }

    private static String sanitize(String value, int maxLength, String fallback) {
        if (value == null) {
            return fallback;
        }
        // Ağ katmanı (Utf8String.write) UTF-16 char sayısını sınırladığı için limit
        // code point yerine char cinsinden uygulanır; surrogate çiftler bölünmez.
        StringBuilder sanitized = new StringBuilder(Math.min(value.length(), maxLength));
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)) {
                continue;
            }
            if (sanitized.length() + Character.charCount(codePoint) > maxLength) {
                break;
            }
            sanitized.appendCodePoint(codePoint);
        }
        String result = sanitized.toString().trim();
        return result.isEmpty() ? fallback : result;
    }
}
