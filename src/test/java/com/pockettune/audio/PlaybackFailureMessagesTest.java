package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class PlaybackFailureMessagesTest {
    private static final String SIGNED_URL =
            "https://googlevideo.example/videoplayback?expire=999&sig=secret-token";

    @Test
    void mapsExplicitFailureKindsToClearEnglishMessages() {
        assertEquals(
                "yt-dlp was not found on the server. Ask the server administrator to check the installation.",
                PlaybackFailureMessages.forPlaylistResolution(failure(
                        ExternalProcessException.FailureKind.TOOL_MISSING))
        );
        assertEquals(
                "Could not connect to YouTube. Check the internet connection and try again.",
                PlaybackFailureMessages.forPlaylistResolution(failure(
                        ExternalProcessException.FailureKind.NETWORK))
        );
        assertEquals(
                "YouTube did not respond in time. Please try again later.",
                PlaybackFailureMessages.forPlaylistResolution(failure(
                        ExternalProcessException.FailureKind.TIMEOUT))
        );
        assertEquals(
                "The video or playlist is unavailable, private or restricted.",
                PlaybackFailureMessages.forPlaylistResolution(failure(
                        ExternalProcessException.FailureKind.MEDIA_UNAVAILABLE))
        );
        assertEquals(
                "The concurrent local-audio limit was reached. Stop a speaker or increase the client limit.",
                PlaybackFailureMessages.forPlayback(failure(
                        ExternalProcessException.FailureKind.CAPACITY))
        );
        assertEquals(
                PlaybackFailureMessages.FailureCategory.CAPACITY,
                PlaybackFailureMessages.category(failure(
                        ExternalProcessException.FailureKind.CAPACITY))
        );
    }

    @Test
    void recognizesNetworkFailuresEvenWhenLegacyResolverMarkedMediaUnavailable() {
        ExternalProcessException exception = new ExternalProcessException(
                "yt-dlp stream URL'sini çözemedi: Unable to download webpage: connection timed out",
                ExternalProcessException.FailureKind.MEDIA_UNAVAILABLE
        );

        assertEquals(
                PlaybackFailureMessages.FailureCategory.TIMEOUT,
                PlaybackFailureMessages.category(exception)
        );
        assertEquals(
                "YouTube did not respond in time. Please try again later.",
                PlaybackFailureMessages.forPlaylistResolution(exception)
        );
    }

    @Test
    void playerAndLogMessagesNeverContainRawProcessDetailsOrSignedUrls() {
        ExternalProcessException exception = new ExternalProcessException(
                "mpv failed while opening " + SIGNED_URL,
                new IOException("connection reset for " + SIGNED_URL),
                ExternalProcessException.FailureKind.NETWORK
        );

        String playerMessage = PlaybackFailureMessages.forPlayback(exception);
        String logSummary = PlaybackFailureMessages.safeLogSummary(exception);

        assertFalse(playerMessage.contains("http"));
        assertFalse(playerMessage.contains("secret-token"));
        assertFalse(logSummary.contains("http"));
        assertFalse(logSummary.contains("secret-token"));
        assertEquals(
                "category=NETWORK, kind=NETWORK, cause=IOException",
                logSummary
        );
    }

    @Test
    void invalidInputUsesOneStableMessageInsteadOfEchoingUserText() {
        ExternalProcessException exception = new ExternalProcessException(
                "Geçersiz YouTube URL'si: " + SIGNED_URL,
                ExternalProcessException.FailureKind.INVALID_INPUT
        );

        assertEquals(
                "Enter a valid YouTube video or playlist URL.",
                PlaybackFailureMessages.forUrlInput(exception)
        );
    }

    @Test
    void asynchronousWrapperKeepsFirstExternalFailureAndNeverLeaksItsCause() {
        ExternalProcessException external = new ExternalProcessException(
                "mpv failed while opening " + SIGNED_URL,
                new IOException("local path C:/Users/private and " + SIGNED_URL),
                ExternalProcessException.FailureKind.NETWORK
        );

        String message = PlaybackFailureMessages.forThrowable(new CompletionException(external));

        assertEquals(
                "Could not connect to YouTube. Check the internet connection and try again.",
                message
        );
        assertFalse(message.contains("secret-token"));
        assertFalse(message.contains("C:/Users"));
    }

    private static ExternalProcessException failure(ExternalProcessException.FailureKind kind) {
        return new ExternalProcessException("raw details", kind);
    }
}
