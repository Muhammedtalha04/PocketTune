package com.pockettune.audio;

import java.util.Objects;

/** Keeps process ownership until exit is positively observed, then runs exact-token cleanup. */
final class ProcessExitReaper {
    private ProcessExitReaper() {
    }

    static void releaseNowOrWhenExited(Process process, Runnable releaseOwnership) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(releaseOwnership, "releaseOwnership");
        if (isDefinitelyExited(process)) {
            releaseOwnership.run();
            return;
        }

        try {
            process.onExit().whenComplete((exitedProcess, failure) -> {
                if (failure == null && isDefinitelyExited(exitedProcess)) {
                    releaseOwnership.run();
                }
            });
        } catch (RuntimeException ignored) {
            // Ambiguous ownership is intentionally retained for the JVM shutdown drain.
        }
    }

    private static boolean isDefinitelyExited(Process process) {
        try {
            return !process.isAlive();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
