package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalProcessCancellationTest {
    @Test
    void cancellingTerminatesEveryRegisteredRunningProcess() {
        ExternalProcessCancellation cancellation = new ExternalProcessCancellation();
        RecordingProcess first = new RecordingProcess();
        RecordingProcess second = new RecordingProcess();
        assertTrue(cancellation.register(first));
        assertTrue(cancellation.register(second));

        assertTrue(cancellation.cancel());

        assertTrue(first.terminated);
        assertTrue(second.terminated);
        assertFalse(cancellation.cancel());
    }

    @Test
    void processRegisteredAfterCancellationIsRejectedAndTerminated() {
        ExternalProcessCancellation cancellation = new ExternalProcessCancellation();
        cancellation.cancel();
        RecordingProcess lateProcess = new RecordingProcess();

        assertFalse(cancellation.register(lateProcess));
        assertTrue(lateProcess.terminated);
    }

    @Test
    void forcedTerminationWaitsForTheProcessToConfirmExit() {
        RecordingProcess process = new RecordingProcess(true, false);

        ExternalProcessRunner.terminateImmediately(process);

        assertTrue(process.terminated);
        assertTrue(process.timedWaitCalled);
        assertFalse(process.isAlive());
    }

    @Test
    void oneBrokenProcessDoesNotPreventRemainingCancellation() {
        ExternalProcessCancellation cancellation = new ExternalProcessCancellation();
        RecordingProcess broken = new RecordingProcess(false, true);
        RecordingProcess healthy = new RecordingProcess();
        cancellation.register(broken);
        cancellation.register(healthy);

        cancellation.cancel();

        assertTrue(broken.terminated);
        assertTrue(healthy.terminated);
    }

    @Test
    void cancellationRequestSignalsTerminationWithoutWaitingForExit() {
        ExternalProcessCancellation cancellation = new ExternalProcessCancellation();
        RecordingProcess slowExit = new RecordingProcess(true, false);
        cancellation.register(slowExit);

        assertTrue(cancellation.requestCancellation());

        assertTrue(slowExit.terminated);
        assertFalse(slowExit.timedWaitCalled);
        assertTrue(slowExit.isAlive());
    }

    @Test
    void ownerCleanupWaitsUntilTheLastProcessIsUnregistered() {
        ExternalProcessCancellation cancellation = new ExternalProcessCancellation();
        RecordingProcess first = new RecordingProcess();
        RecordingProcess second = new RecordingProcess();
        cancellation.register(first);
        cancellation.register(second);
        AtomicInteger drained = new AtomicInteger();
        cancellation.onDrained(drained::incrementAndGet);

        cancellation.unregister(first);
        assertEquals(0, drained.get());
        cancellation.unregister(second);

        assertEquals(1, drained.get());
    }

    private static final class RecordingProcess extends Process {
        private boolean alive = true;
        private boolean terminated;
        private boolean timedWaitCalled;
        private final boolean exitOnlyAfterWait;
        private final boolean throwOnDestroy;

        private RecordingProcess() {
            this(false, false);
        }

        private RecordingProcess(boolean exitOnlyAfterWait, boolean throwOnDestroy) {
            this.exitOnlyAfterWait = exitOnlyAfterWait;
            this.throwOnDestroy = throwOnDestroy;
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
            alive = false;
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            timedWaitCalled = true;
            alive = false;
            return true;
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
            destroyForcibly();
        }

        @Override
        public Process destroyForcibly() {
            terminated = true;
            if (!exitOnlyAfterWait) {
                alive = false;
            }
            if (throwOnDestroy) {
                throw new IllegalStateException("intentional process failure");
            }
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
