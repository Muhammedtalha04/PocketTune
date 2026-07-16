package com.pockettune.audio.ipc;

import com.pockettune.audio.ExternalProcessException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IpcRequestExecutorTest {
    @Test
    void deadlineReleasesCallerAndRunsNativeHandleAbort() {
        CountDownLatch blockedRead = new CountDownLatch(1);
        AtomicBoolean aborted = new AtomicBoolean();
        long startedAt = System.nanoTime();

        ExternalProcessException exception = assertThrows(
                ExternalProcessException.class,
                () -> IpcRequestExecutor.execute(
                        () -> {
                            blockedRead.await();
                            return "late";
                        },
                        Duration.ofMillis(40),
                        () -> {
                            aborted.set(true);
                            blockedRead.countDown();
                        }
                )
        );

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        assertTrue(aborted.get());
        assertTrue(elapsedMillis < 1_000L, "deadline took " + elapsedMillis + " ms");
        assertTrue(exception.getMessage().contains("zaman aşımına"));
    }

    @Test
    void successfulRequestDoesNotInvokeAbortHook() throws ExternalProcessException {
        AtomicBoolean aborted = new AtomicBoolean();

        String response = IpcRequestExecutor.execute(
                () -> "success",
                Duration.ofSeconds(1),
                () -> aborted.set(true)
        );

        assertEquals("success", response);
        assertTrue(!aborted.get());
    }
}
