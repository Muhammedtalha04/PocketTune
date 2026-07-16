package com.pockettune.audio;

import com.mojang.logging.LogUtils;
import com.pockettune.config.PocketTuneClientConfig;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MpvProcessRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ProcessLifecycleRegistry<MpvController> LIFECYCLE =
            new ProcessLifecycleRegistry<>();
    private static final Object INVALIDATION_LOCK = new Object();
    private static final int MAX_SYNCHRONOUS_PAUSE_RETRIES = 4;
    private static final ExecutorService CONTROL_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PocketTune global mpv control");
        thread.setDaemon(true);
        return thread;
    });

    static {
        Thread shutdownHook = new Thread(
                MpvProcessRegistry::shutdownAndTerminateAll,
                "PocketTune mpv shutdown"
        );
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private MpvProcessRegistry() {
    }

    /**
     * Registers a process only if the startup still belongs to the expected world session.
     * Registration and token publication are one atomic lifecycle transition.
     */
    static void ensureStartupAllowed(long expectedSessionEpoch) throws ExternalProcessException {
        int limit = configuredControllerLimit();
        ProcessLifecycleRegistry.RejectionReason rejection =
                LIFECYCLE.admissionRejection(expectedSessionEpoch, limit);
        if (rejection != ProcessLifecycleRegistry.RejectionReason.NONE) {
            throw admissionFailure(rejection, limit);
        }
    }

    static void register(MpvController controller, long expectedSessionEpoch)
            throws ExternalProcessException {
        int limit = configuredControllerLimit();
        ProcessLifecycleRegistry.Registration registration = LIFECYCLE.register(
                controller,
                expectedSessionEpoch,
                limit,
                controller::publishRegistryToken
        );
        if (!registration.accepted()) {
            throw admissionFailure(registration.rejectionReason(), limit);
        }
    }

    private static int configuredControllerLimit() {
        int configured;
        try {
            configured = PocketTuneClientConfig.MAX_CONCURRENT_MPV_CONTROLLERS.get();
        } catch (IllegalStateException exception) {
            // A very early test/startup probe may run before NeoForge attaches the client config.
            configured = PocketTuneClientConfig.DEFAULT_MAX_CONCURRENT_MPV_CONTROLLERS;
        }
        return Math.max(
                PocketTuneClientConfig.MIN_MAX_CONCURRENT_MPV_CONTROLLERS,
                Math.min(PocketTuneClientConfig.MAX_MAX_CONCURRENT_MPV_CONTROLLERS, configured)
        );
    }

    private static ExternalProcessException admissionFailure(
            ProcessLifecycleRegistry.RejectionReason rejection,
            int limit
    ) {
        if (rejection == ProcessLifecycleRegistry.RejectionReason.CAPACITY) {
            return new ExternalProcessException(
                    "PocketTune eşzamanlı yerel ses sınırına ulaştı (" + limit + ").",
                    ExternalProcessException.FailureKind.CAPACITY
            );
        }
        return new ExternalProcessException(
                "mpv başlangıcı eski veya kapanmış bir PocketTune oturumuna ait olduğu için iptal edildi.",
                ExternalProcessException.FailureKind.CANCELLED
        );
    }

    static void unregister(MpvController controller) {
        long token = controller.registryToken();
        if (token == ProcessLifecycleRegistry.NO_REGISTRATION) {
            return;
        }
        LIFECYCLE.unregister(controller, token);
        controller.clearRegistryToken(token);
    }

    public static long currentSessionEpoch() {
        return LIFECYCLE.currentSessionEpoch();
    }

    public static void setPaused(boolean paused) {
        ProcessLifecycleRegistry.PauseChange<MpvController> change =
                LIFECYCLE.setGlobalPaused(paused);
        if (!change.changed()) {
            return;
        }
        for (ProcessLifecycleRegistry.RegisteredTarget<MpvController> registration
                : change.registrations()) {
            applyEffectivePauseAsync(registration);
        }
    }

    public static boolean isPauseRequested() {
        return LIFECYCLE.isGlobalPauseRequested();
    }

    public static void synchronizePauseState(MpvController controller) {
        ProcessLifecycleRegistry.RegisteredTarget<MpvController> registration =
                LIFECYCLE.currentRegistration(controller);
        if (registration != null) {
            applyEffectivePauseAsync(registration);
        }
    }

    public static void setPlaybackPaused(MpvController controller, boolean paused) {
        long token = controller.registryToken();
        ProcessLifecycleRegistry.PauseSnapshot snapshot =
                LIFECYCLE.setPlaybackPaused(controller, token, paused);
        if (snapshot != null) {
            applyEffectivePauseAsync(
                    new ProcessLifecycleRegistry.RegisteredTarget<>(controller, token)
            );
        }
    }

    /**
     * Synchronously applies the latest effective pause state. If global pause changes while the IPC
     * command is in flight, the command is repeated against the new revision before returning.
     *
     * @return whether the controller remains paused after the operation
     */
    public static boolean applyPlaybackPauseNow(MpvController controller, boolean paused)
            throws ExternalProcessException {
        long token = controller.registryToken();
        ProcessLifecycleRegistry.PauseSnapshot initial =
                LIFECYCLE.setPlaybackPaused(controller, token, paused);
        if (initial == null) {
            return true;
        }
        return applyEffectivePauseNow(controller, token, initial);
    }

    /**
     * Compatibility alias for prepared startup callers compiled against the previous internal API.
     */
    static boolean initializePlaybackPause(MpvController controller, boolean paused)
            throws ExternalProcessException {
        return applyPlaybackPauseNow(controller, paused);
    }

    private static boolean applyEffectivePauseNow(
            MpvController controller,
            long registrationToken,
            ProcessLifecycleRegistry.PauseSnapshot initial
    ) throws ExternalProcessException {
        ProcessLifecycleRegistry.PauseSnapshot snapshot = initial;
        for (int attempt = 0; attempt < MAX_SYNCHRONOUS_PAUSE_RETRIES; attempt++) {
            controller.setPaused(snapshot.effectivePaused());
            ProcessLifecycleRegistry.PauseSnapshot latest =
                    LIFECYCLE.pauseSnapshot(controller, registrationToken);
            if (latest == null) {
                return true;
            }
            if (latest.revision() == snapshot.revision()
                    && latest.effectivePaused() == snapshot.effectivePaused()) {
                return snapshot.effectivePaused();
            }
            snapshot = latest;
        }

        // Rapid pause changes must not hold the startup worker indefinitely. One final write uses
        // the newest state that still belongs to this exact controller registration.
        ProcessLifecycleRegistry.PauseSnapshot latest =
                LIFECYCLE.pauseSnapshot(controller, registrationToken);
        if (latest == null) {
            return true;
        }
        controller.setPaused(latest.effectivePaused());
        return latest.effectivePaused();
    }

    private static void applyEffectivePauseAsync(
            ProcessLifecycleRegistry.RegisteredTarget<MpvController> registration
    ) {
        CONTROL_EXECUTOR.execute(() -> {
            MpvController controller = registration.target();
            if (!controller.isAlive()) {
                return;
            }
            ProcessLifecycleRegistry.PauseSnapshot snapshot =
                    LIFECYCLE.pauseSnapshot(controller, registration.token());
            if (snapshot == null) {
                return;
            }
            try {
                applyEffectivePauseNow(controller, registration.token(), snapshot);
            } catch (ExternalProcessException ignored) {
                // A queued pause command may lose its process while the owner is closing it.
            }
        });
    }

    /**
     * Invalidates the current world session and atomically detaches every process registered in it.
     * Registrations started against the old epoch are rejected; a later world can use the new epoch.
     */
    public static void invalidateAndTerminateAll() {
        transitionAndTerminate(false);
    }

    private static void shutdownAndTerminateAll() {
        transitionAndTerminate(true);
    }

    private static void transitionAndTerminate(boolean permanentShutdown) {
        synchronized (INVALIDATION_LOCK) {
            ProcessLifecycleRegistry.Invalidation<MpvController> invalidation = permanentShutdown
                    ? LIFECYCLE.beginShutdown()
                    : LIFECYCLE.beginInvalidation();
            try {
                if (permanentShutdown) {
                    AudioCleanupCoordinator.shutdownAndDrain(
                            invalidation.detached().stream().map(
                                    ProcessLifecycleRegistry.RegisteredTarget::target
                            ).toList()
                    );
                } else {
                    for (ProcessLifecycleRegistry.RegisteredTarget<MpvController> registration
                            : invalidation.detached()) {
                        AudioCleanupCoordinator.terminate(registration.target());
                    }
                }
            } finally {
                LIFECYCLE.completeInvalidation(invalidation.token());
            }
        }
    }

    public static int activeControllerCount() {
        return LIFECYCLE.activeCount();
    }
}
