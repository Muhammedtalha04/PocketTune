package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProcessLifecycleRegistryTest {
    @Test
    void capacityAdmissionIsAtomicAndAReleasedSlotCanBeReused() {
        ProcessLifecycleRegistry<Object> registry = new ProcessLifecycleRegistry<>();
        long epoch = registry.currentSessionEpoch();
        Object first = new Object();
        Object second = new Object();
        Object rejected = new Object();

        ProcessLifecycleRegistry.Registration firstRegistration =
                registry.register(first, epoch, 2, ignored -> {
                });
        ProcessLifecycleRegistry.Registration secondRegistration =
                registry.register(second, epoch, 2, ignored -> {
                });
        ProcessLifecycleRegistry.Registration capacityRejection =
                registry.register(rejected, epoch, 2, ignored -> {
                });

        assertTrue(firstRegistration.accepted());
        assertTrue(secondRegistration.accepted());
        assertFalse(capacityRejection.accepted());
        assertEquals(ProcessLifecycleRegistry.RejectionReason.CAPACITY,
                capacityRejection.rejectionReason());
        assertEquals(ProcessLifecycleRegistry.RejectionReason.CAPACITY,
                registry.admissionRejection(epoch, 2));
        assertEquals(2, registry.activeCount());

        assertTrue(registry.unregister(first, firstRegistration.token()));
        assertEquals(ProcessLifecycleRegistry.RejectionReason.NONE,
                registry.admissionRejection(epoch, 2));
        assertTrue(registry.register(rejected, epoch, 2, ignored -> {
        }).accepted());
        assertEquals(2, registry.activeCount());
    }

    @Test
    void concurrentAdmissionNeverExceedsTheHardCapacity() throws Exception {
        int capacity = 4;
        int contenders = 24;
        ProcessLifecycleRegistry<Object> registry = new ProcessLifecycleRegistry<>();
        long epoch = registry.currentSessionEpoch();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<ProcessLifecycleRegistry.Registration>> registrations = new ArrayList<>();
            for (int index = 0; index < contenders; index++) {
                registrations.add(executor.submit(() -> {
                    start.await();
                    return registry.register(new Object(), epoch, capacity, ignored -> {
                    });
                }));
            }
            start.countDown();

            int accepted = 0;
            int capacityRejected = 0;
            for (Future<ProcessLifecycleRegistry.Registration> future : registrations) {
                ProcessLifecycleRegistry.Registration registration = future.get();
                if (registration.accepted()) {
                    accepted++;
                } else if (registration.rejectionReason()
                        == ProcessLifecycleRegistry.RejectionReason.CAPACITY) {
                    capacityRejected++;
                }
            }

            assertEquals(capacity, accepted);
            assertEquals(contenders - capacity, capacityRejected);
            assertEquals(capacity, registry.activeCount());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void invalidationDrainsCurrentProcessesRejectsStaleStartupAndAllowsNextWorld() {
        ProcessLifecycleRegistry<Object> registry = new ProcessLifecycleRegistry<>();
        Object currentProcess = new Object();
        long oldEpoch = registry.currentSessionEpoch();

        ProcessLifecycleRegistry.Registration current = registry.register(currentProcess, oldEpoch);
        ProcessLifecycleRegistry.Invalidation<Object> invalidation = registry.beginInvalidation();

        assertTrue(current.accepted());
        assertEquals(oldEpoch, invalidation.invalidatedEpoch());
        assertEquals(oldEpoch + 1L, invalidation.transitionEpoch());
        assertEquals(1, invalidation.detached().size());
        assertSame(currentProcess, invalidation.detached().getFirst().target());
        assertEquals(current.token(), invalidation.detached().getFirst().token());
        assertEquals(0, registry.activeCount());

        assertFalse(registry.register(new Object(), invalidation.transitionEpoch()).accepted());
        long nextWorldEpoch = registry.completeInvalidation(invalidation.token());
        assertFalse(registry.register(new Object(), oldEpoch).accepted());
        assertEquals(oldEpoch + 2L, nextWorldEpoch);
        assertTrue(registry.register(new Object(), nextWorldEpoch).accepted());
        assertEquals(1, registry.activeCount());
    }

    @Test
    void concurrentRegisterAndInvalidationCanNeverLeaveAnOldEpochOrphan() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 200; iteration++) {
                ProcessLifecycleRegistry<Object> registry = new ProcessLifecycleRegistry<>();
                Object process = new Object();
                long epoch = registry.currentSessionEpoch();
                CountDownLatch start = new CountDownLatch(1);

                Future<ProcessLifecycleRegistry.Registration> registrationFuture =
                        executor.submit(() -> {
                            start.await();
                            return registry.register(process, epoch);
                        });
                Future<ProcessLifecycleRegistry.Invalidation<Object>> invalidationFuture =
                        executor.submit(() -> {
                            start.await();
                            return registry.beginInvalidation();
                        });

                start.countDown();
                ProcessLifecycleRegistry.Registration registration = registrationFuture.get();
                ProcessLifecycleRegistry.Invalidation<Object> invalidation = invalidationFuture.get();

                if (registration.accepted()) {
                    assertEquals(1, invalidation.detached().size());
                    assertSame(process, invalidation.detached().getFirst().target());
                    assertEquals(registration.token(), invalidation.detached().getFirst().token());
                } else {
                    assertTrue(invalidation.detached().isEmpty());
                }
                assertFalse(registry.register(new Object(), invalidation.transitionEpoch()).accepted());
                registry.completeInvalidation(invalidation.token());
                assertEquals(0, registry.activeCount());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cleanupThatHasStartedRemainsDrainableUntilItsFinalUnregister() throws Exception {
        ProcessLifecycleRegistry<Object> registry = new ProcessLifecycleRegistry<>();
        Object process = new Object();
        ProcessLifecycleRegistry.Registration registration =
                registry.register(process, registry.currentSessionEpoch());
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch cleanupFinished = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> cleanup = executor.submit(() -> {
                cleanupStarted.countDown();
                cleanupFinished.await();
                return registry.unregister(process, registration.token());
            });

            cleanupStarted.await();
            ProcessLifecycleRegistry.Invalidation<Object> invalidation = registry.beginInvalidation();

            assertEquals(1, invalidation.detached().size());
            assertSame(process, invalidation.detached().getFirst().target());
            cleanupFinished.countDown();
            assertFalse(cleanup.get());
            registry.completeInvalidation(invalidation.token());
            assertEquals(0, registry.activeCount());
        } finally {
            cleanupFinished.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void stalePauseAndUnregisterOperationsCannotRetainOrRemoveANewerRegistration() {
        ProcessLifecycleRegistry<Object> registry = new ProcessLifecycleRegistry<>();
        Object process = new Object();
        long epoch = registry.currentSessionEpoch();

        ProcessLifecycleRegistry.Registration first = registry.register(process, epoch);
        assertNotNull(registry.setPlaybackPaused(process, first.token(), true));
        assertTrue(registry.unregister(process, first.token()));

        ProcessLifecycleRegistry.Registration second = registry.register(process, epoch);
        assertTrue(second.accepted());
        assertNotEquals(first.token(), second.token());
        assertNull(registry.setPlaybackPaused(process, first.token(), true));
        assertFalse(registry.unregister(process, first.token()));

        ProcessLifecycleRegistry.PauseSnapshot current =
                registry.pauseSnapshot(process, second.token());
        assertNotNull(current);
        assertFalse(current.effectivePaused());
        assertEquals(1, registry.activeCount());
    }

    @Test
    void detachedOldTokenCannotTouchSameObjectRegisteredByTheNextWorld() {
        ProcessLifecycleRegistry<Object> registry = new ProcessLifecycleRegistry<>();
        Object process = new Object();
        ProcessLifecycleRegistry.Registration old =
                registry.register(process, registry.currentSessionEpoch());

        ProcessLifecycleRegistry.Invalidation<Object> invalidation = registry.beginInvalidation();
        long nextWorldEpoch = registry.completeInvalidation(invalidation.token());
        ProcessLifecycleRegistry.Registration current =
                registry.register(process, nextWorldEpoch);

        assertTrue(current.accepted());
        assertFalse(registry.unregister(process, old.token()));
        assertNull(registry.pauseSnapshot(process, old.token()));
        assertNotNull(registry.pauseSnapshot(process, current.token()));
        assertEquals(1, registry.activeCount());
    }

    @Test
    void shutdownIsPermanentButWorldInvalidationIsNot() {
        ProcessLifecycleRegistry<Object> registry = new ProcessLifecycleRegistry<>();
        Object process = new Object();
        registry.register(process, registry.currentSessionEpoch());

        ProcessLifecycleRegistry.Invalidation<Object> shutdown = registry.beginShutdown();

        assertTrue(shutdown.shutdown());
        assertTrue(registry.isShutdown());
        assertEquals(1, shutdown.detached().size());
        assertFalse(registry.register(new Object(), shutdown.transitionEpoch()).accepted());
        long shutdownEpoch = registry.completeInvalidation(shutdown.token());
        assertEquals(shutdown.transitionEpoch(), shutdownEpoch);
        assertFalse(registry.register(new Object(), shutdownEpoch).accepted());
        assertEquals(0, registry.activeCount());
    }

    @Test
    void invalidationClearsGlobalPauseForTheNextWorld() {
        ProcessLifecycleRegistry<Object> registry = new ProcessLifecycleRegistry<>();
        Object process = new Object();
        ProcessLifecycleRegistry.Registration registration =
                registry.register(process, registry.currentSessionEpoch());

        registry.setGlobalPaused(true);
        assertTrue(registry.pauseSnapshot(process, registration.token()).effectivePaused());

        ProcessLifecycleRegistry.Invalidation<Object> invalidation = registry.beginInvalidation();
        assertFalse(registry.setGlobalPaused(true).changed());
        long nextWorldEpoch = registry.completeInvalidation(invalidation.token());
        Object nextProcess = new Object();
        ProcessLifecycleRegistry.Registration next =
                registry.register(nextProcess, nextWorldEpoch);

        assertFalse(registry.isGlobalPauseRequested());
        assertFalse(registry.pauseSnapshot(nextProcess, next.token()).effectivePaused());
    }
}
