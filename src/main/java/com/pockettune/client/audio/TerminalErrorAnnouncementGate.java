package com.pockettune.client.audio;

/** Ensures an asynchronous terminal failure is announced once for one exact track generation. */
final class TerminalErrorAnnouncementGate {
    private String videoId = "";
    private long trackSequence = -1L;
    private boolean announced;

    synchronized boolean claim(String nextVideoId, long nextTrackSequence) {
        select(nextVideoId, nextTrackSequence);
        if (announced) {
            return false;
        }
        announced = true;
        return true;
    }

    synchronized void markHealthy(String nextVideoId, long nextTrackSequence) {
        videoId = safeVideoId(nextVideoId);
        trackSequence = nextTrackSequence;
        announced = false;
    }

    synchronized void clear() {
        videoId = "";
        trackSequence = -1L;
        announced = false;
    }

    private void select(String nextVideoId, long nextTrackSequence) {
        String safeId = safeVideoId(nextVideoId);
        if (!videoId.equals(safeId) || trackSequence != nextTrackSequence) {
            videoId = safeId;
            trackSequence = nextTrackSequence;
            announced = false;
        }
    }

    private static String safeVideoId(String videoId) {
        return videoId == null ? "" : videoId;
    }
}
