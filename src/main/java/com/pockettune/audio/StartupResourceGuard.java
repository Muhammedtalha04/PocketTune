package com.pockettune.audio;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Transfers a startup resource only after preparation succeeds, cleaning it on every other exit.
 */
final class StartupResourceGuard {
    private StartupResourceGuard() {
    }

    static <T, E extends Exception> T prepare(
            T resource,
            CheckedConsumer<T, E> preparation,
            Consumer<T> cleanup
    ) throws E {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(cleanup, "cleanup");
        boolean succeeded = false;
        try {
            preparation.accept(resource);
            succeeded = true;
            return resource;
        } finally {
            if (!succeeded) {
                cleanup.accept(resource);
            }
        }
    }

    @FunctionalInterface
    interface CheckedConsumer<T, E extends Exception> {
        void accept(T value) throws E;
    }
}
