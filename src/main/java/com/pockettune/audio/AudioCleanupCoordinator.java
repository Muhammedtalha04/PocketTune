package com.pockettune.audio;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/** Coordinates non-blocking owner detachment with bounded mpv cleanup and retry ownership. */
public final class AudioCleanupCoordinator {
    private static final ManagedCleanupExecutor<CancellableStartupLease<MpvController>> STARTUP_LEASES =
            new ManagedCleanupExecutor<>(
                    1,
                    64,
                    Duration.ofMillis(250L),
                    "PocketTune startup lease cleanup",
                    AudioCleanupCoordinator::cleanStartupLease
            );
    private static final ManagedCleanupExecutor<MpvController> CONTROLLERS =
            new ManagedCleanupExecutor<>(
                    2,
                    128,
                    Duration.ofMillis(500L),
                    "PocketTune mpv cleanup",
                    MpvController::tryTerminateImmediately
            );

    private AudioCleanupCoordinator() {
    }

    public static void cancelStartupLease(CancellableStartupLease<MpvController> lease) {
        if (lease == null) {
            return;
        }
        lease.requestCancellation();
        STARTUP_LEASES.submit(lease);
    }

    public static void terminate(MpvController controller) {
        if (controller != null) {
            CONTROLLERS.submit(controller);
        }
    }

    static void shutdownAndDrain(Collection<MpvController> detachedControllers) {
        STARTUP_LEASES.shutdownAndDrain(List.of());
        CONTROLLERS.shutdownAndDrain(
                detachedControllers,
                MpvController::requestTerminationSignal,
                Duration.ofSeconds(2L)
        );
    }

    static int trackedControllerCount() {
        return CONTROLLERS.ownedCount();
    }

    private static boolean cleanStartupLease(CancellableStartupLease<MpvController> lease) {
        MpvController controller = lease.cancelAndDetach();
        if (controller != null) {
            // The controller executor records exact identity ownership synchronously before this
            // lease entry is released, including when its bounded worker queue is saturated.
            CONTROLLERS.submit(controller);
        }
        return true;
    }
}
