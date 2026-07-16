package com.pockettune.service;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Identity-keyed lifecycle registry that keeps a stopped runtime as a tombstone until the owning
 * session has fully stopped. Late requests therefore cannot create a fresh executor mid-shutdown.
 */
final class SessionRuntimeRegistry<S, R> {
    private final Map<S, R> runtimes = new IdentityHashMap<>();
    private final Supplier<R> factory;
    private final Consumer<R> stopper;

    SessionRuntimeRegistry(Supplier<R> factory, Consumer<R> stopper) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.stopper = Objects.requireNonNull(stopper, "stopper");
    }

    synchronized R runtimeFor(S session) {
        Objects.requireNonNull(session, "session");
        return runtimes.computeIfAbsent(session, ignored -> factory.get());
    }

    synchronized void beginStopping(S session) {
        R runtime = runtimeFor(session);
        // Stop while holding the registry lock so runtimeFor cannot observe it before tombstoning.
        stopper.accept(runtime);
    }

    synchronized void forget(S session) {
        R runtime = runtimes.remove(session);
        if (runtime != null) {
            stopper.accept(runtime);
        }
    }

    synchronized int trackedSessionCount() {
        return runtimes.size();
    }
}
