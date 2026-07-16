package com.pockettune.audio;

import java.util.List;

public final class YtDlpPlaylistSmokeTest {
    private static final String TEST_VIDEO_ID = "dQw4w9WgXcQ";
    private static final String TEST_URL = "https://www.youtube.com/watch?v=" + TEST_VIDEO_ID;

    private YtDlpPlaylistSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        List<String> parsed = YtDlpResolver.parsePlaylistIds(
                TEST_VIDEO_ID + System.lineSeparator()
                        + "invalid id" + System.lineSeparator()
                        + "M7lc1UVf-VE"
        );
        if (!parsed.equals(List.of(TEST_VIDEO_ID, "M7lc1UVf-VE"))) {
            throw new IllegalStateException("Playlist video kimliği filtreleme testi başarısız: " + parsed);
        }

        List<String> resolved = new YtDlpResolver().resolvePlaylistVideoIds(TEST_URL);
        if (!resolved.equals(List.of(TEST_VIDEO_ID))) {
            throw new IllegalStateException("yt-dlp video sırası beklenen sonucu vermedi: " + resolved);
        }

        var metadata = new YtDlpResolver().resolvePlaylistTracks(TEST_URL).getFirst();
        if (!metadata.videoId().equals(TEST_VIDEO_ID)
                || metadata.title().isBlank()
                || metadata.artist().isBlank()
                || metadata.durationMillis() <= 0L) {
            throw new IllegalStateException("yt-dlp metadata çözümleme testi başarısız: " + metadata);
        }

        String canonicalUrl = YtDlpResolver.videoUrl(resolved.getFirst());
        if (!canonicalUrl.equals(TEST_URL)) {
            throw new IllegalStateException("Video URL normalizasyonu başarısız: " + canonicalUrl);
        }

        if (!YtDlpResolver.extractVideoId("https://youtu.be/" + TEST_VIDEO_ID + "?t=30")
                .filter(TEST_VIDEO_ID::equals)
                .isPresent()) {
            throw new IllegalStateException("0.4.2 kısa URL migration testi başarısız.");
        }
    }
}
