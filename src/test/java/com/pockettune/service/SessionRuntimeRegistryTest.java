package com.pockettune.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SessionRuntimeRegistryTest {
    @Test
    void stoppingKeepsATombstoneSoLateRequestsCannotCreateANewRuntime() {
        AtomicInteger created = new AtomicInteger();
        SessionRuntimeRegistry<Object, RecordingRuntime> registry = new SessionRuntimeRegistry<>(
                () -> new RecordingRuntime(created.incrementAndGet()),
                RecordingRuntime::stop
        );
        Object server = new Object();
        RecordingRuntime original = registry.runtimeFor(server);

        registry.beginStopping(server);
        RecordingRuntime lateRequestRuntime = registry.runtimeFor(server);

        assertSame(original, lateRequestRuntime);
        assertTrue(lateRequestRuntime.stopped);
        assertEquals(1, created.get());
        assertEquals(1, registry.trackedSessionCount());
    }

    @Test
    void fullyStoppedSessionIsForgottenAndCanBeRecreatedCleanly() {
        AtomicInteger created = new AtomicInteger();
        SessionRuntimeRegistry<Object, RecordingRuntime> registry = new SessionRuntimeRegistry<>(
                () -> new RecordingRuntime(created.incrementAndGet()),
                RecordingRuntime::stop
        );
        Object server = new Object();
        RecordingRuntime oldRuntime = registry.runtimeFor(server);
        registry.beginStopping(server);

        registry.forget(server);

        assertEquals(0, registry.trackedSessionCount());
        RecordingRuntime replacement = registry.runtimeFor(server);
        assertNotSame(oldRuntime, replacement);
        assertFalse(replacement.stopped);
        assertEquals(2, created.get());
    }

    @Test
    void serverStoppingInterruptsRunningWorkAndCleansQueuedWork() throws Exception {
        SessionRuntimeRegistry<Object, ManagedRuntime> registry = new SessionRuntimeRegistry<>(
                ManagedRuntime::new,
                ManagedRuntime::shutdown
        );
        Object server = new Object();
        ManagedRuntime runtime = registry.runtimeFor(server);
        runtime.submitRunningWork();
        assertTrue(runtime.runningStarted.await(2L, TimeUnit.SECONDS));
        runtime.submitQueuedWork();

        registry.beginStopping(server);

        assertTrue(runtime.runningCleanup.await(2L, TimeUnit.SECONDS));
        assertEquals(1, runtime.queuedCleanup.get());
        assertSame(runtime, registry.runtimeFor(server));
        assertTrue(runtime.executor.isShutdown());
    }

    private static final class RecordingRuntime {
        private final int id;
        private boolean stopped;

        private RecordingRuntime(int id) {
            this.id = id;
        }

        private void stop() {
            stopped = true;
        }
    }

    private static final class ManagedRuntime {
        private final BoundedResolutionExecutor executor =
                new BoundedResolutionExecutor(1, 2, "managed-runtime-test");
        private final CountDownLatch runningStarted = new CountDownLatch(1);
        private final CountDownLatch runningCleanup = new CountDownLatch(1);
        private final AtomicInteger queuedCleanup = new AtomicInteger();

        private void submitRunningWork() {
            assertTrue(executor.submit(() -> {
                runningStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    runningCleanup.countDown();
                }
            }));
        }

        private void submitQueuedWork() {
            assertTrue(executor.submit(() -> {
            }, queuedCleanup::incrementAndGet));
        }

        private void shutdown() {
            executor.shutdownNow();
        }
    }
}
