package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ProcessExitReaperTest {
    @Test
    void liveProcessKeepsOwnershipUntilOnExitConfirmsTermination() {
        ReapableProcess process = new ReapableProcess();
        AtomicInteger releases = new AtomicInteger();

        ProcessExitReaper.releaseNowOrWhenExited(process, releases::incrementAndGet);
        assertEquals(0, releases.get());

        process.confirmExit();
        assertEquals(1, releases.get());
    }

    @Test
    void alreadyExitedProcessReleasesOwnershipImmediately() {
        ReapableProcess process = new ReapableProcess();
        process.confirmExit();
        AtomicInteger releases = new AtomicInteger();

        ProcessExitReaper.releaseNowOrWhenExited(process, releases::incrementAndGet);

        assertEquals(1, releases.get());
    }

    @Test
    void ambiguousLivenessAndBrokenOnExitRetainOwnership() {
        ReapableProcess process = new ReapableProcess();
        process.throwFromIsAlive = true;
        process.throwFromOnExit = true;
        AtomicInteger releases = new AtomicInteger();

        ProcessExitReaper.releaseNowOrWhenExited(process, releases::incrementAndGet);

        assertEquals(0, releases.get());
    }

    private static final class ReapableProcess extends Process {
        private final CompletableFuture<Process> exit = new CompletableFuture<>();
        private boolean alive = true;
        private boolean throwFromIsAlive;
        private boolean throwFromOnExit;

        private void confirmExit() {
            alive = false;
            exit.complete(this);
        }

        @Override
        public CompletableFuture<Process> onExit() {
            if (throwFromOnExit) {
                throw new IllegalStateException("intentional onExit failure");
            }
            return exit;
        }

        @Override
        public boolean isAlive() {
            if (throwFromIsAlive) {
                throw new IllegalStateException("intentional liveness failure");
            }
            return alive;
        }

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
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("still alive");
            }
            return 0;
        }

        @Override
        public void destroy() {
            confirmExit();
        }
    }
}
