package com.pockettune.audio.ipc;

import com.pockettune.audio.ExternalProcessException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs blocking named-pipe/socket requests behind a hard caller-side deadline. The abort hook closes
 * the active native handle, while the daemon worker prevents a broken OS pipe from pinning Minecraft.
 */
final class IpcRequestExecutor {
    private static final AtomicLong THREAD_SEQUENCE = new AtomicLong();
    private static final ExecutorService IO_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(
                runnable,
                "PocketTune mpv IPC " + THREAD_SEQUENCE.incrementAndGet()
        );
        thread.setDaemon(true);
        return thread;
    });

    private IpcRequestExecutor() {
    }

    static <T> T execute(Callable<T> operation, Duration timeout, Runnable abort)
            throws ExternalProcessException {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(abort, "abort");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("IPC timeout must be positive");
        }

        Future<T> future = IO_EXECUTOR.submit(operation);
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            abortQuietly(abort);
            future.cancel(true);
            throw new ExternalProcessException(
                    "The mpv IPC request timed out; the stalled connection was closed.",
                    exception
            );
        } catch (InterruptedException exception) {
            abortQuietly(abort);
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ExternalProcessException("The mpv IPC request was interrupted.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ExternalProcessException externalProcessException) {
                throw externalProcessException;
            }
            throw new ExternalProcessException("The mpv IPC request could not be completed.", cause);
        }
    }

    private static void abortQuietly(Runnable abort) {
        try {
            abort.run();
        } catch (RuntimeException ignored) {
            // The deadline still releases the caller even if native handle cleanup itself fails.
        }
    }
}
