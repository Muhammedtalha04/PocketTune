package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CancellableStartupLeaseTest {
    @Test
    void cancellationBeforeSpawnPreventsTheSpawnerFromRunning() throws Exception {
        CancellableStartupLease<Object> lease = new CancellableStartupLease<>();
        AtomicBoolean spawned = new AtomicBoolean();

        assertNull(lease.cancelAndDetach());
        assertNull(lease.spawnAndBind(() -> {
            spawned.set(true);
            return new Object();
        }));

        assertFalse(spawned.get());
        assertTrue(lease.isCancelled());
    }

    @Test
    void cancellationCannotSlipBetweenSpawnAndBinding() throws Exception {
        CancellableStartupLease<Object> lease = new CancellableStartupLease<>();
        Object process = new Object();
        CountDownLatch spawnEntered = new CountDownLatch(1);
        CountDownLatch allowBinding = new CountDownLatch(1);
        CountDownLatch cancellationAttempted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> spawned = executor.submit(() -> lease.spawnAndBind(() -> {
                spawnEntered.countDown();
                allowBinding.await();
                return process;
            }));
            assertTrue(spawnEntered.await(5, TimeUnit.SECONDS));
            Future<Object> cancelled = executor.submit(() -> {
                cancellationAttempted.countDown();
                return lease.cancelAndDetach();
            });

            assertTrue(cancellationAttempted.await(5, TimeUnit.SECONDS));
            assertFalse(cancelled.isDone());
            allowBinding.countDown();

            assertSame(process, spawned.get(5, TimeUnit.SECONDS));
            assertSame(process, cancelled.get(5, TimeUnit.SECONDS));
            assertTrue(lease.isCancelled());
        } finally {
            allowBinding.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void publicationTransfersOwnershipAwayFromTheLease() throws Exception {
        CancellableStartupLease<Object> lease = new CancellableStartupLease<>();
        Object process = lease.spawnAndBind(Object::new);

        assertTrue(lease.publish(process));
        assertNull(lease.cancelAndDetach());
        assertFalse(lease.publish(process));
    }

    @Test
    void nonBlockingCancellationRequestReturnsWhileNativeSpawnIsStillRunning() throws Exception {
        CancellableStartupLease<Object> lease = new CancellableStartupLease<>();
        Object process = new Object();
        CountDownLatch spawnEntered = new CountDownLatch(1);
        CountDownLatch releaseSpawn = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Object> spawned = executor.submit(() -> lease.spawnAndBind(() -> {
                spawnEntered.countDown();
                releaseSpawn.await();
                return process;
            }));
            assertTrue(spawnEntered.await(2L, TimeUnit.SECONDS));

            long started = System.nanoTime();
            lease.requestCancellation();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMillis < 100L);
            assertTrue(lease.isCancelled());
            releaseSpawn.countDown();
            assertSame(process, spawned.get(2L, TimeUnit.SECONDS));
            assertSame(process, lease.cancelAndDetach());
        } finally {
            releaseSpawn.countDown();
            executor.shutdownNow();
        }
    }
}
