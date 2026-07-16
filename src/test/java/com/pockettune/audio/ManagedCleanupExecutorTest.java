package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedCleanupExecutorTest {
    @Test
    void failedFakeProcessKillRetainsExactOwnershipUntilRetryConfirmsExit() throws Exception {
        FakeProcess process = new FakeProcess(3);
        CountDownLatch exited = new CountDownLatch(1);
        ManagedCleanupExecutor<FakeProcess> cleanup = new ManagedCleanupExecutor<>(
                1,
                1,
                Duration.ofMillis(20L),
                "fake-process-cleanup",
                candidate -> {
                    candidate.destroyForcibly();
                    if (!candidate.isAlive()) {
                        exited.countDown();
                        return true;
                    }
                    return false;
                }
        );
        try {
            cleanup.submit(process);
            assertTrue(cleanup.owns(process));
            assertTrue(exited.await(2L, TimeUnit.SECONDS));
            awaitOwnershipRelease(cleanup, process);
            assertFalse(cleanup.owns(process));
            assertEquals(3, process.destroyAttempts());
        } finally {
            cleanup.shutdownAndDrain(List.of());
        }
    }

    @Test
    void saturatedWorkerQueueTransfersRejectedTargetToRetryOwner() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch thirdCleaned = new CountDownLatch(1);
        Object first = new Object();
        Object second = new Object();
        Object third = new Object();
        ManagedCleanupExecutor<Object> cleanup = new ManagedCleanupExecutor<>(
                1,
                1,
                Duration.ofMillis(20L),
                "rejection-cleanup",
                target -> {
                    if (target == first) {
                        firstEntered.countDown();
                        await(releaseFirst);
                    }
                    if (target == third) {
                        thirdCleaned.countDown();
                    }
                    return true;
                }
        );
        try {
            cleanup.submit(first);
            assertTrue(firstEntered.await(2L, TimeUnit.SECONDS));
            cleanup.submit(second);
            cleanup.submit(third);
            assertTrue(cleanup.owns(third));
            assertTrue(thirdCleaned.await(2L, TimeUnit.SECONDS));
            awaitOwnershipRelease(cleanup, third);
            assertFalse(cleanup.owns(third));
        } finally {
            releaseFirst.countDown();
            cleanup.shutdownAndDrain(List.of());
        }
    }

    @Test
    void shutdownSignalsEveryTargetThenUsesOneSharedParallelDeadline() {
        int targetCount = 16;
        AtomicInteger signalCount = new AtomicInteger();
        AtomicBoolean cleanupRanBeforeAllSignals = new AtomicBoolean();
        List<FakeProcess> targets = java.util.stream.IntStream.range(0, targetCount)
                .mapToObj(ignored -> new FakeProcess(Integer.MAX_VALUE))
                .toList();
        ManagedCleanupExecutor<FakeProcess> cleanup = new ManagedCleanupExecutor<>(
                1,
                1,
                Duration.ofSeconds(30L),
                "shutdown-cleanup",
                process -> {
                    if (signalCount.get() != targetCount) {
                        cleanupRanBeforeAllSignals.set(true);
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(350L));
                    return true;
                }
        );

        long started = System.nanoTime();
        cleanup.shutdownAndDrain(
                targets,
                ignored -> signalCount.incrementAndGet(),
                Duration.ofSeconds(2L)
        );
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(targetCount, signalCount.get());
        assertFalse(cleanupRanBeforeAllSignals.get());
        assertTrue(elapsedMillis < 2_500L, "shutdown drain took " + elapsedMillis + " ms");
        assertEquals(0, cleanup.ownedCount());
    }

    private static <T> void awaitOwnershipRelease(ManagedCleanupExecutor<T> cleanup, T target)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (cleanup.owns(target) && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class FakeProcess extends Process {
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger destroyAttempts = new AtomicInteger();
        private final int attemptsBeforeExit;

        private FakeProcess(int attemptsBeforeExit) {
            this.attemptsBeforeExit = attemptsBeforeExit;
        }

        private int destroyAttempts() {
            return destroyAttempts.get();
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("still alive");
            }
            return 0;
        }

        @Override
        public void destroy() {
            destroyForcibly();
        }

        @Override
        public Process destroyForcibly() {
            if (destroyAttempts.incrementAndGet() >= attemptsBeforeExit) {
                alive.set(false);
            }
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }
    }
}
