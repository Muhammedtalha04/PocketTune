package com.pockettune.service;

import java.util.Objects;
import java.util.function.Consumer;

/** Transfers a resource on normal return and releases it exactly once on unchecked failure. */
final class ExceptionalResourceGuard {
    private ExceptionalResourceGuard() {
    }

    static <T> void releaseOnExceptionalExit(T resource, Consumer<T> releaser, Runnable work) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(releaser, "releaser");
        Objects.requireNonNull(work, "work");
        try {
            work.run();
        } catch (RuntimeException | Error failure) {
            try {
                releaser.accept(resource);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }
}
