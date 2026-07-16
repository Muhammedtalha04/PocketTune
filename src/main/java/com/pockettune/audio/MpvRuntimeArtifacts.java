package com.pockettune.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Owns mpv runtime files from pre-spawn reservation until confirmed process termination. */
final class MpvRuntimeArtifacts {
    private static final System.Logger LOGGER = System.getLogger(MpvRuntimeArtifacts.class.getName());
    private static final Duration DEFAULT_RETENTION = Duration.ofHours(24L);
    private static final int DEFAULT_MAX_STALE_ARTIFACTS = 64;
    private static final Pattern MANAGED_FILE_NAME = Pattern.compile(
            "mpv-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(?:log|sock)"
    );
    private static final Object LOCK = new Object();
    private static final Set<Path> ACTIVE_PATHS = new HashSet<>();

    private MpvRuntimeArtifacts() {
    }

    static Reservation reserve(Path runtimeDirectory, Path logPath, Path socketPath) {
        Path directory = normalize(Objects.requireNonNull(runtimeDirectory, "runtimeDirectory"));
        Path normalizedLog = requireDirectChild(directory, logPath, "logPath");
        Path normalizedSocket = socketPath == null
                ? null
                : requireDirectChild(directory, socketPath, "socketPath");
        Reservation reservation = new Reservation(directory, normalizedLog, normalizedSocket);
        synchronized (LOCK) {
            ACTIVE_PATHS.add(normalizedLog);
            if (normalizedSocket != null) {
                ACTIVE_PATHS.add(normalizedSocket);
            }
            cleanupLocked(directory, Instant.now(), DEFAULT_RETENTION, DEFAULT_MAX_STALE_ARTIFACTS);
        }
        return reservation;
    }

    static int cleanupStale(
            Path runtimeDirectory,
            Instant now,
            Duration retention,
            int maximumRetainedArtifacts
    ) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(retention, "retention");
        if (retention.isNegative()) {
            throw new IllegalArgumentException("retention must not be negative");
        }
        if (maximumRetainedArtifacts < 0) {
            throw new IllegalArgumentException("maximumRetainedArtifacts must not be negative");
        }
        synchronized (LOCK) {
            return cleanupLocked(
                    normalize(Objects.requireNonNull(runtimeDirectory, "runtimeDirectory")),
                    now,
                    retention,
                    maximumRetainedArtifacts
            );
        }
    }

    private static int cleanupLocked(
            Path runtimeDirectory,
            Instant now,
            Duration retention,
            int maximumRetainedArtifacts
    ) {
        if (!Files.isDirectory(runtimeDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }

        List<Candidate> retainedCandidates = new ArrayList<>();
        int deleted = 0;
        Instant cutoff = now.minus(retention);
        try (var paths = Files.list(runtimeDirectory)) {
            for (Path path : paths.toList()) {
                Path normalized = normalize(path);
                if (!isManagedArtifact(normalized)
                        || Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                        || ACTIVE_PATHS.contains(normalized)) {
                    continue;
                }
                FileTime modified;
                try {
                    modified = Files.getLastModifiedTime(normalized, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException | RuntimeException exception) {
                    logCleanupFailure(normalized, exception);
                    continue;
                }
                if (modified.toInstant().isBefore(cutoff)) {
                    deleted += deleteSafely(normalized) ? 1 : 0;
                } else {
                    retainedCandidates.add(new Candidate(normalized, modified));
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "PocketTune could not enumerate stale mpv runtime artifacts.",
                    exception
            );
            return deleted;
        }

        retainedCandidates.sort(Comparator.comparing(Candidate::modified).reversed());
        for (int index = maximumRetainedArtifacts; index < retainedCandidates.size(); index++) {
            deleted += deleteSafely(retainedCandidates.get(index).path()) ? 1 : 0;
        }
        return deleted;
    }

    private static boolean isManagedArtifact(Path path) {
        Path name = path.getFileName();
        return name != null && MANAGED_FILE_NAME.matcher(name.toString()).matches();
    }

    private static Path requireDirectChild(Path directory, Path candidate, String name) {
        Path normalized = normalize(Objects.requireNonNull(candidate, name));
        if (!directory.equals(normalized.getParent()) || !isManagedArtifact(normalized)) {
            throw new IllegalArgumentException(name + " must be a managed direct runtime child");
        }
        return normalized;
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static void release(Reservation reservation) {
        synchronized (LOCK) {
            if (reservation.released) {
                return;
            }
            reservation.released = true;
            ACTIVE_PATHS.remove(reservation.logPath);
            if (reservation.socketPath != null) {
                ACTIVE_PATHS.remove(reservation.socketPath);
            }
            deleteSafely(reservation.socketPath);
            deleteSafely(reservation.logPath);
        }
    }

    private static boolean deleteSafely(Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(path);
        } catch (IOException | RuntimeException exception) {
            logCleanupFailure(path, exception);
            return false;
        }
    }

    private static void logCleanupFailure(Path path, Throwable exception) {
        LOGGER.log(
                System.Logger.Level.DEBUG,
                "PocketTune could not delete or inspect mpv runtime artifact " + path.getFileName() + ".",
                exception
        );
    }

    static final class Reservation {
        private final Path runtimeDirectory;
        private final Path logPath;
        private final Path socketPath;
        private boolean released;

        private Reservation(Path runtimeDirectory, Path logPath, Path socketPath) {
            this.runtimeDirectory = runtimeDirectory;
            this.logPath = logPath;
            this.socketPath = socketPath;
        }

        Path runtimeDirectory() {
            return runtimeDirectory;
        }

        Path logPath() {
            return logPath;
        }

        Path socketPath() {
            return socketPath;
        }

        void releaseAndDelete() {
            release(this);
        }
    }

    private record Candidate(Path path, FileTime modified) {
    }
}
