package com.pockettune.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoundedResolutionExecutorTest {
    @Test
    void saturatedQueueRejectsWithoutThrowingOrGrowing() throws Exception {
        BoundedResolutionExecutor executor = new BoundedResolutionExecutor(1, 1, "bounded-test");
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch queuedTaskFinished = new CountDownLatch(1);

        try {
            assertTrue(executor.submit(() -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(workerStarted.await(2L, TimeUnit.SECONDS));
            assertTrue(executor.submit(queuedTaskFinished::countDown));
            assertFalse(executor.submit(() -> {
                throw new AssertionError("Rejected task must never run");
            }));

            releaseWorker.countDown();
            assertTrue(queuedTaskFinished.await(2L, TimeUnit.SECONDS));
        } finally {
            releaseWorker.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void invalidBoundsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BoundedResolutionExecutor(0, 1, "test")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new BoundedResolutionExecutor(1, 0, "test")
        );
    }

    @Test
    void shutdownCleansEveryQueuedTaskEvenIfOneCleanupFails() throws Exception {
        BoundedResolutionExecutor executor = new BoundedResolutionExecutor(1, 3, "cleanup-test");
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch runningCleanupFinished = new CountDownLatch(1);
        AtomicInteger queuedCleanupCount = new AtomicInteger();

        assertTrue(executor.submit(() -> {
            workerStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                runningCleanupFinished.countDown();
            }
        }));
        assertTrue(workerStarted.await(2L, TimeUnit.SECONDS));
        assertTrue(executor.submit(() -> {
        }, () -> {
            queuedCleanupCount.incrementAndGet();
            throw new IllegalStateException("intentional cleanup failure");
        }));
        assertTrue(executor.submit(() -> {
        }, queuedCleanupCount::incrementAndGet));

        executor.shutdownNow();

        assertTrue(runningCleanupFinished.await(2L, TimeUnit.SECONDS));
        assertEquals(2, queuedCleanupCount.get());
        assertTrue(executor.isShutdown());
    }

    @Test
    void aFreshExecutorWorksAfterAnOlderRuntimeWasShutdown() throws Exception {
        BoundedResolutionExecutor oldExecutor = new BoundedResolutionExecutor(1, 1, "old-runtime");
        oldExecutor.shutdownNow();
        assertFalse(oldExecutor.submit(() -> {
            throw new AssertionError("A stopped runtime must reject new work");
        }));

        BoundedResolutionExecutor restartedExecutor =
                new BoundedResolutionExecutor(1, 1, "restarted-runtime");
        CountDownLatch completed = new CountDownLatch(1);
        try {
            assertTrue(restartedExecutor.submit(completed::countDown));
            assertTrue(completed.await(2L, TimeUnit.SECONDS));
        } finally {
            restartedExecutor.shutdownNow();
        }
    }
}
