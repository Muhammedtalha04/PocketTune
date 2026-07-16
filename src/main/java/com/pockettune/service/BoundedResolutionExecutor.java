package com.pockettune.service;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A fixed-size daemon executor whose work queue can never grow without a bound. */
final class BoundedResolutionExecutor {
    private static final System.Logger LOGGER = System.getLogger(BoundedResolutionExecutor.class.getName());
    private final ThreadPoolExecutor executor;

    BoundedResolutionExecutor(int workerCount, int queueCapacity, String threadNamePrefix) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        String safePrefix = Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, safePrefix + "-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    boolean submit(Runnable task) {
        return submit(task, () -> {
        });
    }

    /**
     * Submits work with cleanup that is invoked exactly once if shutdown removes it from the queue
     * before execution starts. Rejected submissions remain owned by the caller and do not invoke it.
     */
    boolean submit(Runnable task, Runnable queuedTaskCleanup) {
        ManagedTask managedTask = new ManagedTask(task, queuedTaskCleanup);
        try {
            executor.execute(managedTask);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    int queuedTaskCount() {
        return executor.getQueue().size();
    }

    void shutdownNow() {
        for (Runnable queuedTask : executor.shutdownNow()) {
            if (queuedTask instanceof ManagedTask managedTask) {
                try {
                    managedTask.cancelBeforeStart();
                } catch (RuntimeException exception) {
                    LOGGER.log(
                            System.Logger.Level.WARNING,
                            "Queued resolver cleanup failed; remaining tasks will still be cleaned",
                            exception
                    );
                }
            }
        }
        // Deliberately do not await workers here. ServerStopping runs on the server thread; running
        // tasks own sticky cancellation scopes and the global process reaper keeps ownership until
        // their process trees have positively exited.
    }

    boolean isShutdown() {
        return executor.isShutdown();
    }

    private static final class ManagedTask implements Runnable {
        private static final int PENDING = 0;
        private static final int RUNNING = 1;
        private static final int CANCELLED = 2;

        private final Runnable task;
        private final Runnable queuedTaskCleanup;
        private final AtomicInteger state = new AtomicInteger(PENDING);

        private ManagedTask(Runnable task, Runnable queuedTaskCleanup) {
            this.task = Objects.requireNonNull(task, "task");
            this.queuedTaskCleanup = Objects.requireNonNull(queuedTaskCleanup, "queuedTaskCleanup");
        }

        @Override
        public void run() {
            if (state.compareAndSet(PENDING, RUNNING)) {
                task.run();
            }
        }

        private void cancelBeforeStart() {
            if (state.compareAndSet(PENDING, CANCELLED)) {
                queuedTaskCleanup.run();
            }
        }
    }
}
