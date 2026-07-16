package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class YtDlpResolverValidationTest {
    @Test
    void acceptsSupportedYoutubeHosts() throws Exception {
        assertEquals(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                YtDlpResolver.validateYoutubeUrl(" https://www.youtube.com/watch?v=dQw4w9WgXcQ ")
        );
    }

    @Test
    void rejectsLookalikeHostsCredentialsAndUnsafePorts() {
        assertThrows(ExternalProcessException.class,
                () -> YtDlpResolver.validateYoutubeUrl("https://youtube.com.example.org/watch?v=dQw4w9WgXcQ"));
        assertThrows(ExternalProcessException.class,
                () -> YtDlpResolver.validateYoutubeUrl("https://user@youtube.com/watch?v=dQw4w9WgXcQ"));
        assertThrows(ExternalProcessException.class,
                () -> YtDlpResolver.validateYoutubeUrl("https://youtube.com:444/watch?v=dQw4w9WgXcQ"));
        ExternalProcessException insecure = assertThrows(ExternalProcessException.class,
                () -> YtDlpResolver.validateYoutubeUrl("http://youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals(ExternalProcessException.FailureKind.INVALID_INPUT, insecure.kind());
        assertThrows(ExternalProcessException.class,
                () -> YtDlpResolver.validateYoutubeUrl(
                        "https://www.youtube.com/results?search_query=müzik"));
        assertThrows(ExternalProcessException.class,
                () -> YtDlpResolver.validateYoutubeUrl("https://www.youtube.com/"));
    }

    @Test
    void removesFragmentsAndTrackingWithoutBreakingVideoOrPlaylistParameters() throws Exception {
        assertEquals(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123456789&index=2&t=30",
                YtDlpResolver.validateYoutubeUrl(
                        "https://WWW.YouTube.com:443/watch?v=dQw4w9WgXcQ&si=secret"
                                + "&list=PL123456789&index=2&utm_source=share&t=30#private-fragment")
        );
        assertEquals(
                "https://youtu.be/dQw4w9WgXcQ?t=45",
                YtDlpResolver.validateYoutubeUrl(
                        "https://youtu.be/dQw4w9WgXcQ?%73%69=secret&feature=shared&t=45#fragment")
        );
        assertEquals(
                "https://www.youtube.com/playlist?list=PL123456789",
                YtDlpResolver.validateYoutubeUrl(
                        "https://www.youtube.com/playlist?list=PL123456789&pp=tracking")
        );
        assertEquals(
                "https://www.youtube.com/shorts/dQw4w9WgXcQ?list=PL123456789&t=12",
                YtDlpResolver.validateYoutubeUrl(
                        "https://www.youtube.com/shorts/dQw4w9WgXcQ"
                                + "?list=PL123456789&t=12&feature=share")
        );
        assertEquals(
                "https://www.youtube-nocookie.com/embed/videoseries?list=PL123456789&start=5",
                YtDlpResolver.validateYoutubeUrl(
                        "https://www.youtube-nocookie.com/embed/videoseries"
                                + "?list=PL123456789&start=5&si=secret")
        );
    }

    @Test
    void canonicalizesAndBoundsAllowedQueryValues() throws Exception {
        assertEquals(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123456789&index=2&t=1h2m3s&start_radio=1",
                YtDlpResolver.validateYoutubeUrl(
                        "https://www.youtube.com/watch?"
                                + "v=dQw4w9WgXcQ&v=AAAAAAAAAAA"
                                + "&list=PL123456789&list=secretDuplicate"
                                + "&index=2&t=1h2m3s&start=private-value"
                                + "&end=" + "9".repeat(20)
                                + "&time_continue=-1&start_radio=1")
        );

        assertEquals(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                YtDlpResolver.validateYoutubeUrl(
                        "https://www.youtube.com/watch?v=bad&v=dQw4w9WgXcQ&t=not-a-time")
        );

        assertThrows(ExternalProcessException.class,
                () -> YtDlpResolver.validateYoutubeUrl(
                        "https://www.youtube.com/playlist?list=x&index=1"));
    }

    @Test
    void persistedUrlPolicyDropsMalformedOrInsecureValues() {
        assertEquals("", YtDlpResolver.sanitizeStoredYoutubeUrl("http://youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("", YtDlpResolver.sanitizeStoredYoutubeUrl("https://example.org/watch?v=dQw4w9WgXcQ"));
        assertEquals("", YtDlpResolver.sanitizeStoredYoutubeUrl("x".repeat(2_049)));
        String sanitized = YtDlpResolver.sanitizeStoredYoutubeUrl(
                "https://youtube.com/watch?v=dQw4w9WgXcQ&si=secret#fragment");
        assertEquals("https://youtube.com/watch?v=dQw4w9WgXcQ", sanitized);
        assertFalse(sanitized.contains("secret"));
    }

    @Test
    void extractsSupportedVideoUrlShapes() {
        assertEquals("dQw4w9WgXcQ", YtDlpResolver.extractVideoId(
                "https://youtu.be/dQw4w9WgXcQ?t=30").orElseThrow());
        assertEquals("dQw4w9WgXcQ", YtDlpResolver.extractVideoId(
                "https://www.youtube.com/shorts/dQw4w9WgXcQ").orElseThrow());
    }
}
