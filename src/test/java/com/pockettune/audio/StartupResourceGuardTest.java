package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StartupResourceGuardTest {
    @Test
    void successfulPreparationTransfersTheResourceWithoutCleanup() throws Exception {
        Object resource = new Object();
        AtomicBoolean cleaned = new AtomicBoolean();

        Object result = StartupResourceGuard.prepare(resource, ignored -> {
        }, ignored -> cleaned.set(true));

        assertSame(resource, result);
        assertFalse(cleaned.get());
    }

    @Test
    void runtimeFailureCleansTheResourceAndPropagatesTheSameFailure() {
        Object resource = new Object();
        AtomicBoolean cleaned = new AtomicBoolean();
        IllegalStateException failure = new IllegalStateException("runtime startup failure");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                StartupResourceGuard.prepare(resource, ignored -> {
                    throw failure;
                }, ignored -> cleaned.set(true))
        );

        assertSame(failure, thrown);
        assertTrue(cleaned.get());
    }

    @Test
    void fatalErrorCleansTheResourceWithoutSwallowingTheError() {
        Object resource = new Object();
        AtomicBoolean cleaned = new AtomicBoolean();
        AssertionError failure = new AssertionError("fatal startup failure");

        AssertionError thrown = assertThrows(AssertionError.class, () ->
                StartupResourceGuard.prepare(resource, ignored -> {
                    throw failure;
                }, ignored -> cleaned.set(true))
        );

        assertSame(failure, thrown);
        assertTrue(cleaned.get());
    }
}
