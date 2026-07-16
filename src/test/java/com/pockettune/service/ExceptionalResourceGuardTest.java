package com.pockettune.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ExceptionalResourceGuardTest {
    @Test
    void normalCompletionTransfersOwnershipWithoutReleasing() {
        AtomicInteger releases = new AtomicInteger();

        ExceptionalResourceGuard.releaseOnExceptionalExit("ticket", ignored -> releases.incrementAndGet(), () -> {
        });

        assertEquals(0, releases.get());
    }

    @Test
    void runtimeFailureReleasesExactlyOnceAndIsRethrown() {
        AtomicInteger releases = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("intentional");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> ExceptionalResourceGuard.releaseOnExceptionalExit(
                        "ticket",
                        ignored -> releases.incrementAndGet(),
                        () -> {
                            throw failure;
                        }
                )
        );

        assertSame(failure, thrown);
        assertEquals(1, releases.get());
    }

    @Test
    void errorReleasesExactlyOnceAndIsRethrown() {
        AtomicInteger releases = new AtomicInteger();
        AssertionError failure = new AssertionError("intentional");

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> ExceptionalResourceGuard.releaseOnExceptionalExit(
                        "ticket",
                        ignored -> releases.incrementAndGet(),
                        () -> {
                            throw failure;
                        }
                )
        );

        assertSame(failure, thrown);
        assertEquals(1, releases.get());
    }
}
