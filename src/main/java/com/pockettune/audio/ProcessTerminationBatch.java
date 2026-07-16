package com.pockettune.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs best-effort process cleanup without allowing one broken process handle to strand the rest.
 */
final class ProcessTerminationBatch {
    private ProcessTerminationBatch() {
    }

    static <T> List<Failure<T>> run(Iterable<T> targets, Consumer<T> terminator) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(terminator, "terminator");
        List<Failure<T>> failures = new ArrayList<>();
        for (T target : targets) {
            try {
                terminator.accept(target);
            } catch (RuntimeException exception) {
                failures.add(new Failure<>(target, exception));
            }
        }
        return List.copyOf(failures);
    }

    record Failure<T>(T target, RuntimeException cause) {
        Failure {
            Objects.requireNonNull(cause, "cause");
        }
    }
}
