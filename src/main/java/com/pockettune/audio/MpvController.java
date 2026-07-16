package com.pockettune.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pockettune.audio.ipc.MpvIpcClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class MpvController implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(MpvController.class.getName());
    public static final String DEFAULT_EXECUTABLE = "mpv";
    private static final Duration IPC_START_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration MEDIA_START_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration SEEK_READY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SEEK_COMPLETION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PLAYBACK_PROGRESS_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FORCE_TERMINATION_TIMEOUT = Duration.ofSeconds(2);
    private static final double MINIMUM_STARTUP_PROGRESS_SECONDS = 0.08D;
    static final double SEEK_POSITION_TOLERANCE_SECONDS = 0.75D;

    private final Process process;
    private final MpvIpcClient ipcClient;
    private final Path unixSocketPath;
    private final Path logPath;
    private final MpvRuntimeArtifacts.Reservation artifactReservation;
    private final boolean preparedLaunch;
    private final List<ProcessHandle> terminationHandles = new ArrayList<>();
    private volatile long registryToken = ProcessLifecycleRegistry.NO_REGISTRATION;
    private volatile boolean closed;
    private volatile boolean terminationFinalized;

    private MpvController(
            Process process,
            MpvIpcClient ipcClient,
            MpvRuntimeArtifacts.Reservation artifactReservation,
            boolean preparedLaunch
    ) {
        this.process = process;
        this.ipcClient = ipcClient;
        this.artifactReservation = artifactReservation;
        this.unixSocketPath = artifactReservation.socketPath();
        this.logPath = artifactReservation.logPath();
        this.preparedLaunch = preparedLaunch;
    }

    public static MpvController start(String streamUrl, Path runtimeDirectory) throws ExternalProcessException {
        long expectedSessionEpoch = MpvProcessRegistry.currentSessionEpoch();
        String executable = configuredExecutable();
        return startInternal(
                executable,
                streamUrl,
                runtimeDirectory,
                100.0D,
                false,
                false,
                null,
                expectedSessionEpoch,
                null
        );
    }

    public static MpvController start(
            String streamUrl,
            Path runtimeDirectory,
            double initialVolume,
            boolean loop
    ) throws ExternalProcessException {
        long expectedSessionEpoch = MpvProcessRegistry.currentSessionEpoch();
        String executable = configuredExecutable();
        return startInternal(
                executable,
                streamUrl,
                runtimeDirectory,
                initialVolume,
                loop,
                false,
                null,
                expectedSessionEpoch,
                null
        );
    }

    public static MpvController start(
            String streamUrl,
            Path runtimeDirectory,
            double initialVolume,
            boolean loop,
            boolean keepOpenAtEnd
    ) throws ExternalProcessException {
        long expectedSessionEpoch = MpvProcessRegistry.currentSessionEpoch();
        String executable = configuredExecutable();
        return startInternal(
                executable,
                streamUrl,
                runtimeDirectory,
                initialVolume,
                loop,
                keepOpenAtEnd,
                null,
                expectedSessionEpoch,
                null
        );
    }

    public static MpvController start(
            String executable,
            String streamUrl,
            Path runtimeDirectory,
            double initialVolume,
            boolean loop
    )
            throws ExternalProcessException {
        long expectedSessionEpoch = MpvProcessRegistry.currentSessionEpoch();
        return startInternal(
                executable,
                streamUrl,
                runtimeDirectory,
                initialVolume,
                loop,
                false,
                null,
                expectedSessionEpoch,
                null
        );
    }

    public static MpvController start(
            String executable,
            String streamUrl,
            Path runtimeDirectory,
            double initialVolume,
            boolean loop,
            boolean keepOpenAtEnd
    ) throws ExternalProcessException {
        long expectedSessionEpoch = MpvProcessRegistry.currentSessionEpoch();
        return startInternal(
                executable,
                streamUrl,
                runtimeDirectory,
                initialVolume,
                loop,
                keepOpenAtEnd,
                null,
                expectedSessionEpoch,
                null
        );
    }

    /**
     * Starts mpv paused and silent, commits the initial seek, then verifies that playback can advance
     * before exposing the controller to the caller.
     */
    public static MpvController startPrepared(
            String streamUrl,
            Path runtimeDirectory,
            double initialVolume,
            boolean loop,
            boolean keepOpenAtEnd,
            double initialPositionSeconds,
            boolean playbackPaused
    ) throws ExternalProcessException {
        long expectedSessionEpoch = MpvProcessRegistry.currentSessionEpoch();
        return startPrepared(
                streamUrl,
                runtimeDirectory,
                initialVolume,
                loop,
                keepOpenAtEnd,
                initialPositionSeconds,
                playbackPaused,
                expectedSessionEpoch
        );
    }

    /**
     * Starts prepared playback only while the caller's world-session epoch remains valid.
     */
    public static MpvController startPrepared(
            String streamUrl,
            Path runtimeDirectory,
            double initialVolume,
            boolean loop,
            boolean keepOpenAtEnd,
            double initialPositionSeconds,
            boolean playbackPaused,
            long expectedSessionEpoch
    ) throws ExternalProcessException {
        return startPrepared(
                streamUrl,
                runtimeDirectory,
                initialVolume,
                loop,
                keepOpenAtEnd,
                initialPositionSeconds,
                playbackPaused,
                expectedSessionEpoch,
                null
        );
    }

    public static MpvController startPrepared(
            String streamUrl,
            Path runtimeDirectory,
            double initialVolume,
            boolean loop,
            boolean keepOpenAtEnd,
            double initialPositionSeconds,
            boolean playbackPaused,
            long expectedSessionEpoch,
            CancellableStartupLease<MpvController> startupLease
    ) throws ExternalProcessException {
        String executable = configuredExecutable();
        return startInternal(
                executable,
                streamUrl,
                runtimeDirectory,
                initialVolume,
                loop,
                keepOpenAtEnd,
                new PlaybackPreparation(initialPositionSeconds, playbackPaused),
                expectedSessionEpoch,
                startupLease
        );
    }

    private static MpvController startInternal(
            String executable,
            String streamUrl,
            Path runtimeDirectory,
            double initialVolume,
            boolean loop,
            boolean keepOpenAtEnd,
            PlaybackPreparation preparation,
            long expectedSessionEpoch,
            CancellableStartupLease<MpvController> startupLease
    ) throws ExternalProcessException {
        try {
            Files.createDirectories(runtimeDirectory);
        } catch (IOException exception) {
            throw new ExternalProcessException("The PocketTune working directory could not be created.", exception);
        }

        MpvProcessRegistry.ensureStartupAllowed(expectedSessionEpoch);

        String id = UUID.randomUUID().toString();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path unixSocket = windows ? null : runtimeDirectory.resolve("mpv-" + id + ".sock");
        String endpoint = windows ? "\\\\.\\pipe\\pockettune-" + id : unixSocket.toString();
        Path logPath = runtimeDirectory.resolve("mpv-" + id + ".log");

        List<String> command = buildLaunchCommand(
                executable,
                streamUrl,
                endpoint,
                initialVolume,
                loop,
                keepOpenAtEnd,
                preparation != null
        );

        MpvController controller = startupLease == null
                ? spawnRegisteredController(
                        executable,
                        command,
                        runtimeDirectory,
                        endpoint,
                        unixSocket,
                        logPath,
                        preparation != null,
                        expectedSessionEpoch
                )
                : startupLease.spawnAndBind(() -> spawnRegisteredController(
                        executable,
                        command,
                        runtimeDirectory,
                        endpoint,
                        unixSocket,
                        logPath,
                        preparation != null,
                        expectedSessionEpoch
                ));
        if (controller == null) {
            throw new ExternalProcessException(
                    "mpv startup was skipped because its owning playback request was cancelled."
            );
        }
        if (startupLease != null && startupLease.isCancelled()) {
            // Ownership deliberately remains with the lease. Its cancellation cleanup can detach
            // and terminate this exact controller without letting a stale startup publish it.
            throw new ExternalProcessException(
                    "The mpv startup request was cancelled after the process was created."
            );
        }
        return StartupResourceGuard.prepare(controller, preparedController -> {
            preparedController.awaitIpc();
            if (preparation == null) {
                preparedController.awaitMediaReady();
                MpvProcessRegistry.synchronizePauseState(preparedController);
            } else {
                preparedController.preparePlayback(preparation);
            }
        }, failedController -> {
            try {
                terminateFailedStartupSafely(failedController);
            } finally {
                if (startupLease != null) {
                    startupLease.abandon(failedController);
                }
            }
        });
    }

    private static MpvController spawnRegisteredController(
            String executable,
            List<String> command,
            Path runtimeDirectory,
            String endpoint,
            Path unixSocket,
            Path logPath,
            boolean prepared,
            long expectedSessionEpoch
    ) throws ExternalProcessException {
        Process process = null;
        MpvController controller = null;
        boolean spawnSucceeded = false;
        MpvRuntimeArtifacts.Reservation artifacts = MpvRuntimeArtifacts.reserve(
                runtimeDirectory,
                logPath,
                unixSocket
        );
        try {
            try {
                process = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()))
                        .start();
            } catch (IOException exception) {
                throw new ExternalProcessException(
                        "mpv could not be started: '" + executable
                                + "'. Check the path setting in pockettune-common.toml or the system PATH.",
                        exception
                );
            }

            controller = new MpvController(
                    process,
                    new MpvIpcClient(endpoint, unixSocket),
                    artifacts,
                    prepared
            );
            MpvProcessRegistry.register(controller, expectedSessionEpoch);
            spawnSucceeded = true;
            return controller;
        } finally {
            if (!spawnSucceeded) {
                if (controller != null) {
                    terminateFailedStartupSafely(controller);
                } else if (process != null) {
                    terminateUnmanagedProcessSafely(process);
                    ProcessExitReaper.releaseNowOrWhenExited(process, artifacts::releaseAndDelete);
                } else {
                    artifacts.releaseAndDelete();
                }
            }
        }
    }

    private static void terminateFailedStartupSafely(MpvController controller) {
        try {
            controller.terminateImmediately();
        } catch (RuntimeException exception) {
            // Preserve exact ownership even if a platform process API fails unexpectedly.
            AudioCleanupCoordinator.terminate(controller);
            LOGGER.log(
                    System.Logger.Level.ERROR,
                    "PocketTune failed to clean up an unpublished mpv startup.",
                    exception
            );
        }
    }

    private static void terminateUnmanagedProcessSafely(Process process) {
        try {
            ProcessHandle root = process.toHandle();
            ProcessHandle[] descendants;
            try {
                descendants = root.descendants().toArray(ProcessHandle[]::new);
            } catch (RuntimeException exception) {
                descendants = new ProcessHandle[0];
            }
            for (ProcessHandle descendant : descendants) {
                destroyHandleSafely(descendant, "unmanaged child");
            }
            destroyHandleSafely(root, "unmanaged root");
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            } catch (RuntimeException exception) {
                LOGGER.log(System.Logger.Level.ERROR, "PocketTune could not kill an unmanaged mpv process.", exception);
            }
            awaitProcessTreeTermination(descendants, root);
        } catch (RuntimeException exception) {
            try {
                process.destroyForcibly();
            } catch (RuntimeException fallbackFailure) {
                exception.addSuppressed(fallbackFailure);
            }
            LOGGER.log(System.Logger.Level.ERROR, "PocketTune could not inspect an unmanaged mpv process.", exception);
        }
    }

    /**
     * Builds the exact mpv launch command used by production. Prepared playback is always born
     * paused and muted; its owner applies EQ and the effective volume only after publication.
     */
    static List<String> buildLaunchCommand(
            String executable,
            String streamUrl,
            String endpoint,
            double initialVolume,
            boolean loop,
            boolean keepOpenAtEnd,
            boolean prepared
    ) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--no-config");
        command.add("--no-video");
        command.add("--force-window=no");
        command.add("--audio-display=no");
        command.add("--input-terminal=no");
        command.add("--idle=" + (keepOpenAtEnd ? "yes" : "no"));
        command.add("--keep-open=" + (keepOpenAtEnd ? "yes" : "no"));
        if (prepared) {
            command.add("--pause=yes");
        }
        double safeInitialVolume = Double.isFinite(initialVolume) ? initialVolume : 0.0D;
        double launchVolume = prepared ? 0.0D : Math.max(0.0D, Math.min(100.0D, safeInitialVolume));
        command.add("--volume=" + launchVolume);
        if (loop) {
            command.add("--loop-file=inf");
        }
        command.add("--input-ipc-server=" + endpoint);
        command.add("--");
        command.add(streamUrl);
        return List.copyOf(command);
    }

    public static YtDlpResolver.ToolVersion probe() {
        try {
            String executable = configuredExecutable();
            ExternalProcessRunner.ProcessResult result = ExternalProcessRunner.run(
                    List.of(executable, "--version"), VERSION_TIMEOUT);
            if (result.exitCode() == 0 && !result.stdout().isBlank()) {
                return new YtDlpResolver.ToolVersion(true, result.stdout().lines().findFirst().orElse("unknown"));
            }
            String details = result.stderr().isBlank()
                    ? "The mpv version check failed (exit code " + result.exitCode() + ")."
                    : summarizeProbeError(result.stderr());
            return new YtDlpResolver.ToolVersion(false, "", details);
        } catch (ExternalProcessException exception) {
            return new YtDlpResolver.ToolVersion(false, "", exception.getMessage());
        }
    }

    private static String configuredExecutable() throws ExternalProcessException {
        return ExternalToolLocator.resolveConfiguredMpv().command();
    }

    private static String summarizeProbeError(String error) {
        String singleLine = error.replaceAll("\\s+", " ").trim();
        int maximumLength = 500;
        return singleLine.length() <= maximumLength
                ? singleLine
                : singleLine.substring(0, maximumLength) + "…";
    }

    public boolean isAlive() {
        return !closed && process.isAlive();
    }

    void publishRegistryToken(long token) {
        registryToken = token;
    }

    long registryToken() {
        return registryToken;
    }

    void clearRegistryToken(long expectedToken) {
        if (registryToken == expectedToken) {
            registryToken = ProcessLifecycleRegistry.NO_REGISTRATION;
        }
    }

    public Path getLogPath() {
        return logPath;
    }

    public void setPaused(boolean paused) throws ExternalProcessException {
        setProperty("pause", paused);
    }

    public void setVolume(double volume) throws ExternalProcessException {
        double safeVolume = Double.isFinite(volume) ? volume : 0.0D;
        setProperty("volume", Math.max(0.0D, Math.min(100.0D, safeVolume)));
    }

    public void setEqualizer(double bassDb, double midDb, double trebleDb) throws ExternalProcessException {
        double bass = clampEqualizer(bassDb);
        double mid = clampEqualizer(midDb);
        double treble = clampEqualizer(trebleDb);
        if (Math.abs(bass) < 0.01D && Math.abs(mid) < 0.01D && Math.abs(treble) < 0.01D) {
            setProperty("af", "");
            return;
        }
        String filter = String.format(
                Locale.ROOT,
                "lavfi=[equalizer=f=100:t=q:w=1:g=%.2f,equalizer=f=1000:t=q:w=1:g=%.2f,equalizer=f=10000:t=q:w=1:g=%.2f]",
                bass,
                mid,
                treble
        );
        setProperty("af", filter);
    }

    private static double clampEqualizer(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(-12.0D, Math.min(12.0D, value));
    }

    public void seek(double seconds) throws ExternalProcessException {
        seekAndAwait(seconds);
    }

    /**
     * Revalidates a prepared controller when playback resumed while its asynchronous stream resolve
     * was running. The probe is muted, reapplies the latest effective pause, and performs one
     * pause/seek retry if mpv does not advance. Callers must restore EQ and volume only afterward.
     */
    public void ensurePlaybackProgressAfterResume(double targetSeconds, boolean playbackPaused)
            throws ExternalProcessException {
        if (!preparedLaunch) {
            throw new ExternalProcessException("Playback-progress verification is available only for prepared mpv sessions.");
        }
        if (!isAlive()) {
            throw new ExternalProcessException("mpv exited before playback progress was verified.");
        }
        double target = Double.isFinite(targetSeconds) ? Math.max(0.0D, targetSeconds) : 0.0D;
        setVolume(0.0D);
        PreparedPlaybackCoordinator.revalidateAfterResume(
                preparedPlaybackOperations(),
                target,
                playbackPaused
        );
    }

    private void seekAndAwait(double seconds) throws ExternalProcessException {
        double target = Double.isFinite(seconds) ? Math.max(0.0D, seconds) : 0.0D;
        JsonArray command = new JsonArray();
        command.add("seek");
        command.add(target);
        command.add("absolute+exact");
        long deadline = System.nanoTime() + SEEK_READY_TIMEOUT.toNanos();
        ExternalProcessException lastError = null;
        while (System.nanoTime() < deadline) {
            if (!isAlive()) {
                throw new ExternalProcessException("mpv exited while waiting for seek readiness.");
            }
            try {
                JsonElement seekable = getProperty("seekable");
                if (seekable != null && !seekable.isJsonNull() && seekable.getAsBoolean()) {
                    send(command);
                    awaitSeekCompletion(target);
                    return;
                }
            } catch (ExternalProcessException exception) {
                lastError = exception;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ExternalProcessException("mpv seek preparation was interrupted.", exception);
            }
        }
        throw new ExternalProcessException("This media stream does not support seeking or is not ready yet.", lastError);
    }

    private void awaitSeekCompletion(double target) throws ExternalProcessException {
        long deadline = System.nanoTime() + SEEK_COMPLETION_TIMEOUT.toNanos();
        ExternalProcessException lastError = null;
        Double lastObservedPosition = null;
        while (System.nanoTime() < deadline) {
            if (!isAlive()) {
                throw new ExternalProcessException("mpv exited before the seek completed.");
            }
            try {
                JsonElement seeking = getProperty("seeking");
                boolean seekFinished = seeking == null || seeking.isJsonNull() || !seeking.getAsBoolean();
                JsonElement timePosition = getProperty("time-pos");
                if (timePosition != null && !timePosition.isJsonNull()) {
                    double current = timePosition.getAsDouble();
                    if (Double.isFinite(current)) {
                        lastObservedPosition = current;
                        if (seekFinished && isSeekPositionVerified(target, current)) {
                            return;
                        }
                    }
                }
            } catch (ExternalProcessException exception) {
                lastError = exception;
            }
            sleepForPolling("The operation was interrupted while waiting for mpv seek completion.", 50L);
        }
        String observed = lastObservedPosition == null
                ? "bilinmiyor"
                : String.format(Locale.ROOT, "%.3f", lastObservedPosition);
        throw new ExternalProcessException(
                String.format(
                        Locale.ROOT,
                        "The mpv seek target could not be verified (target=%.3f, observed=%s).",
                        target,
                        observed
                ),
                lastError
        );
    }

    static boolean isSeekPositionVerified(double target, double observed) {
        return Double.isFinite(target)
                && Double.isFinite(observed)
                && Math.abs(target - observed) <= SEEK_POSITION_TOLERANCE_SECONDS;
    }

    private void preparePlayback(PlaybackPreparation preparation) throws ExternalProcessException {
        double target = Double.isFinite(preparation.initialPositionSeconds())
                ? Math.max(0.0D, preparation.initialPositionSeconds())
                : 0.0D;
        PreparedPlaybackCoordinator.prepare(
                preparedPlaybackOperations(),
                target,
                preparation.playbackPaused()
        );
    }

    private PreparedPlaybackCoordinator.Operations preparedPlaybackOperations() {
        return new PreparedPlaybackCoordinator.Operations() {
            @Override
            public void awaitMediaReady() throws ExternalProcessException {
                MpvController.this.awaitMediaReady();
            }

            @Override
            public void seekAndVerify(double positionSeconds) throws ExternalProcessException {
                MpvController.this.seekAndAwait(positionSeconds);
            }

            @Override
            public boolean applyEffectivePause(boolean playbackPaused) throws ExternalProcessException {
                return MpvProcessRegistry.applyPlaybackPauseNow(MpvController.this, playbackPaused);
            }

            @Override
            public boolean awaitPlaybackProgress() throws ExternalProcessException {
                return MpvController.this.awaitPlaybackProgress();
            }

            @Override
            public void forcePause() throws ExternalProcessException {
                MpvController.this.setPaused(true);
            }
        };
    }

    private boolean awaitPlaybackProgress() throws ExternalProcessException {
        long deadline = System.nanoTime() + PLAYBACK_PROGRESS_TIMEOUT.toNanos();
        Double baseline = null;
        ExternalProcessException lastError = null;
        while (System.nanoTime() < deadline) {
            if (!isAlive()) {
                throw new ExternalProcessException("mpv exited during playback verification.");
            }
            if (MpvProcessRegistry.isPauseRequested()) {
                return true;
            }
            try {
                JsonElement paused = getProperty("pause");
                if (paused != null && !paused.isJsonNull() && paused.getAsBoolean()) {
                    return true;
                }
                JsonElement timePosition = getProperty("time-pos");
                if (timePosition != null && !timePosition.isJsonNull()) {
                    double current = timePosition.getAsDouble();
                    if (Double.isFinite(current)) {
                        if (baseline == null) {
                            baseline = current;
                        } else if (current - baseline >= MINIMUM_STARTUP_PROGRESS_SECONDS) {
                            return true;
                        }
                    }
                }
            } catch (ExternalProcessException exception) {
                lastError = exception;
            }
            sleepForPolling("The operation was interrupted while waiting for mpv playback progress.", 100L);
        }
        if (lastError != null && !isAlive()) {
            throw lastError;
        }
        return false;
    }

    public double getTimeSeconds() throws ExternalProcessException {
        JsonElement data = getProperty("time-pos");
        return data == null || data.isJsonNull() ? 0.0D : data.getAsDouble();
    }

    public double getDurationSeconds() throws ExternalProcessException {
        JsonElement data = getProperty("duration");
        return data == null || data.isJsonNull() ? 0.0D : data.getAsDouble();
    }

    public boolean isEofReached() throws ExternalProcessException {
        JsonElement data = getProperty("eof-reached");
        return data != null && !data.isJsonNull() && data.getAsBoolean();
    }

    private static void sleepForPolling(String interruptedMessage, long millis) throws ExternalProcessException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExternalProcessException(interruptedMessage, exception);
        }
    }

    private void awaitIpc() throws ExternalProcessException {
        long deadline = System.nanoTime() + IPC_START_TIMEOUT.toNanos();
        ExternalProcessException lastError = null;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                close();
                throw new ExternalProcessException("mpv exited before playback started. Log: " + logPath);
            }
            try {
                getProperty("idle-active");
                return;
            } catch (ExternalProcessException exception) {
                lastError = exception;
            }

            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                close();
                throw new ExternalProcessException("The operation was interrupted while waiting for the mpv IPC connection.", exception);
            }
        }
        close();
        throw new ExternalProcessException("The mpv IPC connection timed out.", lastError);
    }

    private void awaitMediaReady() throws ExternalProcessException {
        long deadline = System.nanoTime() + MEDIA_START_TIMEOUT.toNanos();
        ExternalProcessException lastError = null;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new ExternalProcessException("mpv exited while loading media. Log: " + logPath);
            }
            try {
                JsonElement idle = getProperty("idle-active");
                if (idle != null && !idle.isJsonNull() && !idle.getAsBoolean()) {
                    return;
                }
            } catch (ExternalProcessException exception) {
                lastError = exception;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ExternalProcessException("The media-load wait was interrupted.", exception);
            }
        }
        throw new ExternalProcessException("mpv media loading timed out. Log: " + logPath, lastError);
    }

    private JsonElement getProperty(String property) throws ExternalProcessException {
        JsonArray command = new JsonArray();
        command.add("get_property");
        command.add(property);
        JsonObject response = send(command);
        return response.get("data");
    }

    private void setProperty(String property, boolean value) throws ExternalProcessException {
        JsonArray command = new JsonArray();
        command.add("set_property");
        command.add(property);
        command.add(value);
        send(command);
    }

    private void setProperty(String property, double value) throws ExternalProcessException {
        JsonArray command = new JsonArray();
        command.add("set_property");
        command.add(property);
        command.add(value);
        send(command);
    }

    private void setProperty(String property, String value) throws ExternalProcessException {
        JsonArray command = new JsonArray();
        command.add("set_property");
        command.add(property);
        command.add(value);
        send(command);
    }

    private JsonObject send(JsonArray command) throws ExternalProcessException {
        if (closed || !process.isAlive()) {
            throw new ExternalProcessException("The mpv process is not running.");
        }
        JsonObject request = new JsonObject();
        request.add("command", command);
        try {
            return ipcClient.request(request);
        } catch (ExternalProcessException exception) {
            if (!ipcClient.isUsable()) {
                terminateImmediately();
            }
            throw exception;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (process.isAlive()) {
                try {
                    JsonArray quit = new JsonArray();
                    quit.add("quit");
                    JsonObject request = new JsonObject();
                    request.add("command", quit);
                    ipcClient.request(request);
                } catch (ExternalProcessException ignored) {
                    // Kapanış sırasında IPC bağlantısının önce kapanması normaldir.
                }

                try {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroy();
                    }
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        terminateProcessTree();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    terminateProcessTree();
                }
            }
        } finally {
            try {
                // Covers graceful-shutdown failures and confirms the final forced cleanup attempt.
                terminateProcessTree();
            } finally {
                if (isProcessTreeAlive()) {
                    AudioCleanupCoordinator.terminate(this);
                } else {
                    finalizeTermination();
                }
            }
        }
    }

    public void terminateImmediately() {
        if (!tryTerminateImmediately()) {
            AudioCleanupCoordinator.terminate(this);
        }
    }

    /** Performs one bounded kill attempt; false retains ownership in the cleanup coordinator. */
    synchronized boolean tryTerminateImmediately() {
        if (terminationFinalized) {
            return true;
        }
        closed = true;
        terminateProcessTree();
        if (isProcessTreeAlive()) {
            return false;
        }
        finalizeTermination();
        return true;
    }

    /** Sends force-termination requests without waiting; used to fan out JVM shutdown signals. */
    synchronized void requestTerminationSignal() {
        if (terminationFinalized) {
            return;
        }
        closed = true;
        ProcessHandle root;
        try {
            root = process.toHandle();
        } catch (RuntimeException exception) {
            destroyRootProcessSafely();
            return;
        }
        ProcessHandle[] descendants;
        try {
            descendants = root.descendants().toArray(ProcessHandle[]::new);
        } catch (RuntimeException exception) {
            descendants = new ProcessHandle[0];
        }
        rememberTerminationHandle(root);
        for (ProcessHandle descendant : descendants) {
            rememberTerminationHandle(descendant);
            destroyHandleSafely(descendant, "shutdown child");
        }
        destroyHandleSafely(root, "shutdown root");
        destroyRootProcessSafely();
    }

    private void finalizeTermination() {
        if (terminationFinalized) {
            return;
        }
        try {
            artifactReservation.releaseAndDelete();
        } finally {
            MpvProcessRegistry.unregister(this);
            terminationFinalized = true;
        }
    }

    private boolean isProcessTreeAlive() {
        try {
            if (process.isAlive()) {
                return true;
            }
            return terminationHandles.stream().anyMatch(MpvController::isAliveConservatively);
        } catch (RuntimeException exception) {
            // Unknown platform state must retain ownership for the next bounded retry.
            return true;
        }
    }

    private static boolean isAliveConservatively(ProcessHandle handle) {
        try {
            return handle.isAlive();
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private void terminateProcessTree() {
        ProcessHandle handle;
        try {
            handle = process.toHandle();
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "PocketTune could not inspect the mpv process tree; forcing the root process instead.",
                    exception
            );
            destroyRootProcessSafely();
            awaitRootProcessTerminationSafely();
            return;
        }
        ProcessHandle[] descendants;
        try {
            descendants = handle.descendants().toArray(ProcessHandle[]::new);
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "PocketTune could not enumerate mpv child processes; forcing the root process.",
                    exception
            );
            descendants = new ProcessHandle[0];
        }
        rememberTerminationHandle(handle);
        for (ProcessHandle descendant : descendants) {
            rememberTerminationHandle(descendant);
        }
        for (ProcessHandle descendant : descendants) {
            destroyHandleSafely(descendant, "child");
        }
        destroyHandleSafely(handle, "root");
        destroyRootProcessSafely();
        awaitProcessTreeTermination(descendants, handle);
    }

    private void rememberTerminationHandle(ProcessHandle candidate) {
        long candidatePid = safePid(candidate);
        for (ProcessHandle existing : terminationHandles) {
            if (existing == candidate || (candidatePid >= 0L && safePid(existing) == candidatePid)) {
                return;
            }
        }
        terminationHandles.add(candidate);
    }

    private static void destroyHandleSafely(ProcessHandle handle, String kind) {
        try {
            if (handle.isAlive() && !handle.destroyForcibly()) {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "PocketTune could not request forced termination for mpv {0} process {1}.",
                        kind,
                        handle.pid()
                );
            }
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "PocketTune failed while terminating mpv " + kind + " process " + safePid(handle) + ".",
                    exception
            );
        }
    }

    private void destroyRootProcessSafely() {
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.ERROR,
                    "PocketTune could not forcefully terminate the mpv root process.",
                    exception
            );
        }
    }

    private static void awaitProcessTreeTermination(ProcessHandle[] descendants, ProcessHandle root) {
        long deadline = System.nanoTime() + FORCE_TERMINATION_TIMEOUT.toNanos();
        for (ProcessHandle descendant : descendants) {
            if (!awaitHandleTermination(descendant, deadline, "child") && Thread.currentThread().isInterrupted()) {
                return;
            }
        }
        awaitHandleTermination(root, deadline, "root");
    }

    private static boolean awaitHandleTermination(ProcessHandle handle, long deadline, String kind) {
        try {
            if (!handle.isAlive()) {
                return true;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "PocketTune timed out waiting for mpv {0} process {1} to terminate.",
                        kind,
                        safePid(handle)
                );
                return false;
            }
            handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "PocketTune process cleanup was interrupted while waiting for mpv {0} process {1}.",
                    kind,
                    safePid(handle)
            );
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "PocketTune could not confirm termination of mpv " + kind + " process " + safePid(handle) + ".",
                    exception
            );
            return false;
        }
    }

    private void awaitRootProcessTerminationSafely() {
        try {
            if (!process.waitFor(FORCE_TERMINATION_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "PocketTune timed out waiting for the mpv root process to terminate."
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "PocketTune could not confirm termination of the mpv root process.",
                    exception
            );
        }
    }

    private static long safePid(ProcessHandle handle) {
        try {
            return handle.pid();
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private record PlaybackPreparation(double initialPositionSeconds, boolean playbackPaused) {
    }
}
