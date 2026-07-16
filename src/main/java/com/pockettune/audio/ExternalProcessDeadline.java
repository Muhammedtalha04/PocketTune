package com.pockettune.audio;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Applies one monotonic timeout budget to process exit and every output-drain phase. */
final class ExternalProcessDeadline {
    private final long timeoutNanos;
    private final LongSupplier nanoClock;
    private final long startedNanos;

    static ExternalProcessDeadline start(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException ignored) {
            timeoutNanos = Long.MAX_VALUE;
        }
        return new ExternalProcessDeadline(timeoutNanos, System::nanoTime);
    }

    ExternalProcessDeadline(long timeoutNanos, LongSupplier nanoClock) {
        if (timeoutNanos < 1L) {
            throw new IllegalArgumentException("timeoutNanos must be positive");
        }
        this.timeoutNanos = timeoutNanos;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.startedNanos = nanoClock.getAsLong();
    }

    boolean awaitProcess(Process process) throws InterruptedException {
        long remaining = remainingNanos();
        return remaining > 0L && process.waitFor(remaining, TimeUnit.NANOSECONDS);
    }

    void awaitStreams(CompletableFuture<?> stdout, CompletableFuture<?> stderr)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        long remaining = remainingNanos();
        if (remaining <= 0L) {
            throw new TimeoutException("External process output deadline elapsed");
        }
        CompletableFuture.allOf(stdout, stderr).get(remaining, TimeUnit.NANOSECONDS);
    }

    long remainingNanos() {
        long elapsed = nanoClock.getAsLong() - startedNanos;
        if (elapsed <= 0L) {
            return timeoutNanos;
        }
        return Math.max(0L, timeoutNanos - elapsed);
    }
}
