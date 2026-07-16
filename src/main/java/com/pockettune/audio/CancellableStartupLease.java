package com.pockettune.audio;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Atomically coordinates a short process spawn/bind section with owner cancellation.
 * Long-running process preparation deliberately happens after {@link #spawnAndBind} releases its
 * monitor. Cancellation either prevents the spawner from running or detaches the newly bound value
 * so its owner can terminate it immediately.
 */
public final class CancellableStartupLease<T> {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private T boundValue;

    public synchronized <E extends Exception> T spawnAndBind(CheckedSupplier<T, E> spawner) throws E {
        Objects.requireNonNull(spawner, "spawner");
        if (cancelled.get()) {
            return null;
        }
        if (boundValue != null) {
            throw new IllegalStateException("This startup lease already owns a bound value.");
        }
        T value = Objects.requireNonNull(spawner.get(), "spawner result");
        boundValue = value;
        return value;
    }

    /** Marks cancellation without waiting for an in-progress native process spawn. */
    public void requestCancellation() {
        cancelled.set(true);
    }

    public T cancelAndDetach() {
        requestCancellation();
        synchronized (this) {
            T detached = boundValue;
            boundValue = null;
            return detached;
        }
    }

    public synchronized boolean publish(T expected) {
        if (cancelled.get() || boundValue != expected) {
            return false;
        }
        boundValue = null;
        return true;
    }

    public synchronized void abandon(T expected) {
        if (boundValue == expected) {
            boundValue = null;
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    @FunctionalInterface
    public interface CheckedSupplier<T, E extends Exception> {
        T get() throws E;
    }
}
