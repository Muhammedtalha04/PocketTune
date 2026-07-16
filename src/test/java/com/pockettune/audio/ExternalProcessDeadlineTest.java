package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalProcessDeadlineTest {
    @Test
    void processWaitAndStreamDrainShareOneMonotonicBudget() throws Exception {
        AtomicLong now = new AtomicLong(100L);
        ExternalProcessDeadline deadline = new ExternalProcessDeadline(1_000L, now::get);
        RecordingWaitProcess process = new RecordingWaitProcess();

        assertEquals(1_000L, deadline.remainingNanos());
        now.addAndGet(400L);
        deadline.awaitProcess(process);
        assertEquals(600L, deadline.remainingNanos());
        assertEquals(600L, process.observedWaitNanos);
        now.addAndGet(600L);

        assertThrows(
                TimeoutException.class,
                () -> deadline.awaitStreams(
                        CompletableFuture.completedFuture("stdout"),
                        CompletableFuture.completedFuture("stderr")
                )
        );
    }

    private static final class RecordingWaitProcess extends Process {
        private long observedWaitNanos;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            observedWaitNanos = unit.toNanos(timeout);
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }
    }

    @Test
    void completedStreamsDrainWithinTheRemainingBudget() throws Exception {
        ExternalProcessDeadline deadline = new ExternalProcessDeadline(1_000_000_000L, System::nanoTime);

        deadline.awaitStreams(
                CompletableFuture.completedFuture("stdout"),
                CompletableFuture.completedFuture("stderr")
        );
    }

    @Test
    void veryLargeDurationsAreSafelyClamped() {
        ExternalProcessDeadline deadline = ExternalProcessDeadline.start(Duration.ofSeconds(Long.MAX_VALUE));

        assertTrue(deadline.remainingNanos() > 0L);
    }
}
