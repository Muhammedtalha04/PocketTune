package com.pockettune.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded daemon executor for expensive playback startup work.
 *
 * <p>When the queue is saturated, the oldest task that has not started is explicitly cancelled and
 * its rejection cleanup runs before the newest task is admitted. Owners can also cancel a handle;
 * cancellation removes that exact future from the queue and interrupts it if already running.</p>
 */
public final class BoundedStartupExecutor {
    private static final System.Logger LOGGER = System.getLogger(BoundedStartupExecutor.class.getName());
    private final Object submissionLock = new Object();
    private final ThreadPoolExecutor executor;

    public BoundedStartupExecutor(int workerCount, int queueCapacity, String threadNamePrefix) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        String safePrefix = Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(
                    runnable,
                    safePrefix + "-" + threadNumber.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public TaskHandle submit(Runnable task, Runnable rejectionCleanup) {
        TaskHandle candidate = new TaskHandle(task, rejectionCleanup);
        List<TaskHandle> discarded = new ArrayList<>();
        TaskHandle accepted = null;
        synchronized (submissionLock) {
            while (!executor.isShutdown()) {
                try {
                    executor.execute(candidate);
                    accepted = candidate;
                    break;
                } catch (RejectedExecutionException ignored) {
                    Runnable oldest = executor.getQueue().poll();
                    if (oldest instanceof TaskHandle handle) {
                        discarded.add(handle);
                        continue;
                    }
                    break;
                }
            }
        }
        discarded.forEach(TaskHandle::rejectBeforeStart);
        if (accepted == null) {
            candidate.rejectBeforeStart();
        }
        return accepted;
    }

    int queuedTaskCount() {
        return executor.getQueue().size();
    }

    void shutdownNow() {
        for (Runnable queued : executor.shutdownNow()) {
            if (queued instanceof BoundedStartupExecutor.TaskHandle handle) {
                handle.rejectBeforeStart();
            }
        }
    }

    public final class TaskHandle extends FutureTask<Void> {
        private final Runnable rejectionCleanup;
        private final AtomicBoolean rejectionHandled = new AtomicBoolean();

        private TaskHandle(Runnable task, Runnable rejectionCleanup) {
            super(Objects.requireNonNull(task, "task"), null);
            this.rejectionCleanup = Objects.requireNonNull(rejectionCleanup, "rejectionCleanup");
        }

        public boolean cancelAndRemove() {
            boolean cancelled = cancel(true);
            executor.remove(this);
            return cancelled;
        }

        private void rejectBeforeStart() {
            if (cancel(false) && rejectionHandled.compareAndSet(false, true)) {
                try {
                    rejectionCleanup.run();
                } catch (RuntimeException exception) {
                    LOGGER.log(
                            System.Logger.Level.WARNING,
                            "PocketTune startup rejection cleanup failed; remaining discards continue.",
                            exception
                    );
                }
            }
        }
    }
}
