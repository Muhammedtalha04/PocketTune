package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoundedStartupExecutorTest {
    @Test
    void newestSubmissionEvictsOldQueuedFutureAndClosesItsStartupGate() throws Exception {
        BoundedStartupExecutor executor = new BoundedStartupExecutor(1, 1, "startup-test");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch newestRan = new CountDownLatch(1);
        AtomicBoolean oldRan = new AtomicBoolean();
        PlaybackStartupGate gate = new PlaybackStartupGate();
        PlaybackStartupGate.Decision decision = gate.tick("video-id", 7L, false);
        AtomicReference<PlaybackStartupGate.FailureResult> discardResult = new AtomicReference<>();
        try {
            assertNotNull(executor.submit(() -> {
                firstStarted.countDown();
                await(releaseFirst);
            }, () -> {
            }));
            assertTrue(firstStarted.await(2L, TimeUnit.SECONDS));

            BoundedStartupExecutor.TaskHandle old = executor.submit(
                    () -> oldRan.set(true),
                    () -> discardResult.set(gate.completeFailure("video-id", 7L, decision.token()))
            );
            assertNotNull(old);
            assertNotNull(executor.submit(newestRan::countDown, () -> {
            }));

            assertTrue(old.isCancelled());
            assertFalse(gate.inFlight());
            assertTrue(gate.retryPending());
            assertTrue(discardResult.get() == PlaybackStartupGate.FailureResult.RETRY_SCHEDULED);
            releaseFirst.countDown();
            assertTrue(newestRan.await(2L, TimeUnit.SECONDS));
            assertFalse(oldRan.get());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void ownerCancellationPhysicallyRemovesQueuedFuture() throws Exception {
        BoundedStartupExecutor executor = new BoundedStartupExecutor(1, 1, "startup-cancel-test");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch replacementRan = new CountDownLatch(1);
        AtomicBoolean cancelledRan = new AtomicBoolean();
        try {
            executor.submit(() -> {
                firstStarted.countDown();
                await(releaseFirst);
            }, () -> {
            });
            assertTrue(firstStarted.await(2L, TimeUnit.SECONDS));
            BoundedStartupExecutor.TaskHandle queued = executor.submit(
                    () -> cancelledRan.set(true),
                    () -> {
                    }
            );
            assertNotNull(queued);
            assertTrue(queued.cancelAndRemove());
            assertTrue(executor.queuedTaskCount() == 0);

            assertNotNull(executor.submit(replacementRan::countDown, () -> {
            }));
            releaseFirst.countDown();
            assertTrue(replacementRan.await(2L, TimeUnit.SECONDS));
            assertFalse(cancelledRan.get());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
