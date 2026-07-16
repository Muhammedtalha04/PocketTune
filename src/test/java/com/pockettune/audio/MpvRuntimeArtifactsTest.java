package com.pockettune.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MpvRuntimeArtifactsTest {
    @TempDir
    Path runtimeDirectory;

    @Test
    void cleanupNeverDeletesAnActiveArtifactEvenWhenItIsExpiredAndOverTheCountLimit()
            throws Exception {
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        Path activeLog = managed("00000000-0000-0000-0000-000000000001", ".log");
        Files.writeString(activeLog, "active");
        Files.setLastModifiedTime(activeLog, FileTime.from(now.minus(Duration.ofDays(10L))));
        MpvRuntimeArtifacts.Reservation reservation = MpvRuntimeArtifacts.reserve(
                runtimeDirectory,
                activeLog,
                null
        );
        try {
            Path staleLog = managed("00000000-0000-0000-0000-000000000002", ".log");
            Files.writeString(staleLog, "stale");
            Files.setLastModifiedTime(staleLog, FileTime.from(now.minus(Duration.ofDays(10L))));

            int deleted = MpvRuntimeArtifacts.cleanupStale(
                    runtimeDirectory,
                    now,
                    Duration.ofHours(1L),
                    0
            );

            assertEquals(1, deleted);
            assertTrue(Files.exists(activeLog));
            assertFalse(Files.exists(staleLog));
        } finally {
            reservation.releaseAndDelete();
        }
    }

    @Test
    void cleanupRetainsOnlyTheNewestBoundedInactiveArtifacts() throws Exception {
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        List<Path> artifacts = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            Path artifact = managed(
                    "00000000-0000-0000-0000-" + String.format("%012d", index + 10),
                    ".log"
            );
            Files.writeString(artifact, "artifact-" + index);
            Files.setLastModifiedTime(
                    artifact,
                    FileTime.from(now.minus(Duration.ofMinutes(5L - index)))
            );
            artifacts.add(artifact);
        }
        Path unrelated = runtimeDirectory.resolve("do-not-delete.txt");
        Files.writeString(unrelated, "unrelated");

        int deleted = MpvRuntimeArtifacts.cleanupStale(
                runtimeDirectory,
                now,
                Duration.ofDays(1L),
                2
        );

        assertEquals(3, deleted);
        assertFalse(Files.exists(artifacts.get(0)));
        assertFalse(Files.exists(artifacts.get(1)));
        assertFalse(Files.exists(artifacts.get(2)));
        assertTrue(Files.exists(artifacts.get(3)));
        assertTrue(Files.exists(artifacts.get(4)));
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void finalReleaseDeletesItsLogAndSocketIdempotently() throws Exception {
        String id = "00000000-0000-0000-0000-000000000099";
        Path log = managed(id, ".log");
        Path socket = managed(id, ".sock");
        MpvRuntimeArtifacts.Reservation reservation = MpvRuntimeArtifacts.reserve(
                runtimeDirectory,
                log,
                socket
        );
        Files.writeString(log, "log");
        Files.writeString(socket, "socket");

        reservation.releaseAndDelete();
        reservation.releaseAndDelete();

        assertFalse(Files.exists(log));
        assertFalse(Files.exists(socket));
    }

    private Path managed(String id, String suffix) {
        return runtimeDirectory.resolve("mpv-" + id + suffix);
    }
}
