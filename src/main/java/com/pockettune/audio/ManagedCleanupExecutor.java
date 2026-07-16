package com.pockettune.audio;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Owns cleanup targets until their cleanup operation confirms completion.
 *
 * <p>The normal worker queue is bounded. A saturated or shutting-down worker pool transfers the
 * exact target to the identity-based retry set before returning to the caller, so rejection can
 * never turn into resource ownership loss. Retry attempts run on one daemon reaper and each
 * operation is expected to enforce its own bounded deadline.</p>
 */
final class ManagedCleanupExecutor<T> {
    private static final System.Logger LOGGER = System.getLogger(ManagedCleanupExecutor.class.getName());

    private final Object lock = new Object();
    private final Map<T, Entry<T>> owned = new IdentityHashMap<>();
    private final CleanupOperation<T> operation;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor reaper;
    private boolean shuttingDown;

    ManagedCleanupExecutor(
            int workerCount,
            int queueCapacity,
            Duration retryInterval,
            String threadNamePrefix,
            CleanupOperation<T> operation
    ) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        if (retryInterval.isZero() || retryInterval.isNegative()) {
            throw new IllegalArgumentException("retryInterval must be positive");
        }
        this.operation = Objects.requireNonNull(operation, "operation");
        String safePrefix = Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
        AtomicInteger workerNumber = new AtomicInteger();
        ThreadFactory workerFactory = runnable -> daemonThread(
                runnable,
                safePrefix + "-worker-" + workerNumber.incrementAndGet()
        );
        workers = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                workerFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
        reaper = new ScheduledThreadPoolExecutor(
                1,
                runnable -> daemonThread(runnable, safePrefix + "-reaper")
        );
        reaper.setRemoveOnCancelPolicy(true);
        long retryNanos = retryInterval.toNanos();
        reaper.scheduleWithFixedDelay(
                this::runReaperPassSafely,
                retryNanos,
                retryNanos,
                TimeUnit.NANOSECONDS
        );
    }

    void submit(T target) {
        Objects.requireNonNull(target, "target");
        Entry<T> entry;
        boolean runInline;
        synchronized (lock) {
            if (owned.containsKey(target)) {
                return;
            }
            entry = new Entry<>(target);
            owned.put(target, entry);
            runInline = shuttingDown;
            entry.state = runInline ? State.RUNNING : State.QUEUED;
        }
        if (runInline) {
            runEntry(entry);
            return;
        }

        try {
            workers.execute(new Worker(entry));
        } catch (RejectedExecutionException exception) {
            transferToReaper(entry);
        }
    }

    /**
     * Stops daemon workers and attempts every supplied, queued, running, or retrying target during
     * the caller's shutdown drain. The method is idempotent; submissions racing after shutdown run
     * synchronously and remain recorded if their bounded cleanup still cannot finish.
     */
    void shutdownAndDrain(Collection<? extends T> additionalTargets) {
        shutdownAndDrain(additionalTargets, ignored -> {
        }, Duration.ofSeconds(2L));
    }

    void shutdownAndDrain(
            Collection<? extends T> additionalTargets,
            Consumer<T> preShutdownSignal,
            Duration totalDrainDeadline
    ) {
        Objects.requireNonNull(additionalTargets, "additionalTargets");
        Objects.requireNonNull(preShutdownSignal, "preShutdownSignal");
        Objects.requireNonNull(totalDrainDeadline, "totalDrainDeadline");
        synchronized (lock) {
            shuttingDown = true;
            for (T target : additionalTargets) {
                if (target != null && !owned.containsKey(target)) {
                    Entry<T> entry = new Entry<>(target);
                    entry.state = State.RETRY;
                    owned.put(target, entry);
                }
            }
        }

        // Signal every exact target before waiting for any of them. This keeps total JVM shutdown
        // latency independent from the number of stubborn process trees.
        List<Entry<T>> signalledSnapshot;
        synchronized (lock) {
            signalledSnapshot = List.copyOf(owned.values());
        }
        for (Entry<T> entry : signalledSnapshot) {
            try {
                preShutdownSignal.accept(entry.target);
            } catch (RuntimeException exception) {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "PocketTune pre-shutdown cleanup signal failed; bounded drain will still run.",
                        exception
                );
            }
        }

        workers.shutdownNow();
        synchronized (lock) {
            for (Entry<T> entry : owned.values()) {
                if (entry.state == State.QUEUED) {
                    entry.state = State.RETRY;
                }
            }
        }
        reaper.shutdownNow();
        awaitWorkerExit();
        drainOwnedInParallel(totalDrainDeadline);
    }

    int ownedCount() {
        synchronized (lock) {
            return owned.size();
        }
    }

    boolean owns(T target) {
        synchronized (lock) {
            return owned.containsKey(target);
        }
    }

    private void runReaperPassSafely() {
        try {
            List<Entry<T>> snapshot = new ArrayList<>();
            synchronized (lock) {
                if (shuttingDown) {
                    return;
                }
                for (Entry<T> entry : owned.values()) {
                    if (entry.state == State.RETRY) {
                        entry.state = State.RUNNING;
                        snapshot.add(entry);
                    }
                }
            }
            snapshot.forEach(this::runEntry);
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.ERROR,
                    "PocketTune cleanup reaper pass failed; retained targets will be retried.",
                    exception
            );
        }
    }

    private boolean claimQueued(Entry<T> entry) {
        synchronized (lock) {
            if (owned.get(entry.target) != entry || entry.state != State.QUEUED) {
                return false;
            }
            entry.state = State.RUNNING;
            return true;
        }
    }

    private void runEntry(Entry<T> entry) {
        boolean complete = false;
        try {
            complete = operation.clean(entry.target);
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "PocketTune bounded cleanup attempt failed; ownership was retained for retry.",
                    exception
            );
        } finally {
            synchronized (lock) {
                if (owned.get(entry.target) != entry) {
                    return;
                }
                if (complete) {
                    owned.remove(entry.target);
                } else {
                    entry.state = State.RETRY;
                }
            }
        }
    }

    private void transferToReaper(Entry<T> entry) {
        synchronized (lock) {
            if (owned.get(entry.target) == entry && entry.state != State.RUNNING) {
                entry.state = State.RETRY;
            }
        }
    }

    private void drainOwnedInParallel(Duration totalDeadline) {
        List<Entry<T>> snapshot;
        synchronized (lock) {
            snapshot = List.copyOf(owned.values());
            snapshot.forEach(entry -> entry.state = State.RUNNING);
        }
        if (snapshot.isEmpty()) {
            return;
        }
        int parallelism = Math.min(8, snapshot.size());
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService drainWorkers = Executors.newFixedThreadPool(
                parallelism,
                runnable -> daemonThread(
                        runnable,
                        "PocketTune shutdown cleanup-" + threadNumber.incrementAndGet()
                )
        );
        snapshot.forEach(entry -> drainWorkers.execute(() -> runEntry(entry)));
        drainWorkers.shutdown();
        try {
            if (!drainWorkers.awaitTermination(totalDeadline.toNanos(), TimeUnit.NANOSECONDS)) {
                drainWorkers.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            drainWorkers.shutdownNow();
        }
    }

    private void awaitWorkerExit() {
        try {
            workers.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @FunctionalInterface
    interface CleanupOperation<T> {
        /** @return true only when ownership can be safely released */
        boolean clean(T target);
    }

    private enum State {
        QUEUED,
        RUNNING,
        RETRY
    }

    private static final class Entry<T> {
        private final T target;
        private State state;

        private Entry(T target) {
            this.target = target;
        }
    }

    private final class Worker implements Runnable {
        private final Entry<T> entry;

        private Worker(Entry<T> entry) {
            this.entry = entry;
        }

        @Override
        public void run() {
            if (claimQueued(entry)) {
                runEntry(entry);
            }
        }
    }
}
