package com.pockettune.audio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ExternalProcessRunner {
    private static final System.Logger LOGGER = System.getLogger(ExternalProcessRunner.class.getName());
    private static final int MAX_CAPTURED_CHARACTERS = 1_048_576;
    private static final long TERMINATION_WAIT_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final ProcessLifecycleRegistry<Process> PROCESS_LIFECYCLE =
            new ProcessLifecycleRegistry<>();
    private static final ExecutorService STREAM_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "PocketTune process stream");
        thread.setDaemon(true);
        return thread;
    });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                ExternalProcessRunner::shutdownAllProcesses,
                "PocketTune external process shutdown"
        ));
    }

    private ExternalProcessRunner() {
    }

    static ProcessResult run(List<String> command, Duration timeout) throws ExternalProcessException {
        return run(command, timeout, new ExternalProcessCancellation());
    }

    static ProcessResult run(
            List<String> command,
            Duration timeout,
            ExternalProcessCancellation cancellation
    ) throws ExternalProcessException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(cancellation, "cancellation");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new ExternalProcessException(
                    "Harici medya aracı başlatılamadı.",
                    exception,
                    ExternalProcessException.FailureKind.TOOL_MISSING
            );
        }

        ProcessLifecycleRegistry.Registration globalRegistration = PROCESS_LIFECYCLE.register(
                process,
                PROCESS_LIFECYCLE.currentSessionEpoch()
        );
        if (!globalRegistration.accepted()) {
            terminateImmediately(process);
            throw cancelledException(null);
        }

        boolean cancellationRegistered = false;
        CompletableFuture<String> stdout = null;
        CompletableFuture<String> stderr = null;
        try {
            if (!cancellation.register(process)) {
                throw cancelledException(null);
            }
            cancellationRegistered = true;
            ExternalProcessDeadline deadline = ExternalProcessDeadline.start(timeout);
            stdout = CompletableFuture.supplyAsync(
                    () -> readStream(process.getInputStream()), STREAM_EXECUTOR);
            stderr = CompletableFuture.supplyAsync(
                    () -> readStream(process.getErrorStream()), STREAM_EXECUTOR);

            if (!deadline.awaitProcess(process)) {
                throw deadlineException(cancellation, null);
            }

            if (cancellation.isCancelled()) {
                throw cancelledException(null);
            }

            deadline.awaitStreams(stdout, stderr);
            if (cancellation.isCancelled()) {
                throw cancelledException(null);
            }
            return new ProcessResult(
                    process.exitValue(),
                    stdout.getNow("").trim(),
                    stderr.getNow("").trim()
            );
        } catch (TimeoutException exception) {
            abortProcessExecution(process, stdout, stderr);
            throw deadlineException(cancellation, exception);
        } catch (ExecutionException exception) {
            abortProcessExecution(process, stdout, stderr);
            if (cancellation.isCancelled()) {
                throw cancelledException(exception.getCause());
            }
            throw new ExternalProcessException(
                    "Harici medya aracının çıktısı okunamadı.",
                    exception.getCause(),
                    ExternalProcessException.FailureKind.GENERAL
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            abortProcessExecution(process, stdout, stderr);
            throw cancelledException(exception);
        } catch (ExternalProcessException exception) {
            if (exception.kind() == ExternalProcessException.FailureKind.CANCELLED
                    || exception.kind() == ExternalProcessException.FailureKind.TIMEOUT) {
                abortProcessExecution(process, stdout, stderr);
            }
            throw exception;
        } catch (RuntimeException exception) {
            abortProcessExecution(process, stdout, stderr);
            throw new ExternalProcessException(
                    "Harici medya aracı işlemi tamamlanamadı.",
                    exception,
                    ExternalProcessException.FailureKind.GENERAL
            );
        } finally {
            boolean registeredWithCancellation = cancellationRegistered;
            ProcessExitReaper.releaseNowOrWhenExited(process, () -> {
                try {
                    if (registeredWithCancellation) {
                        cancellation.unregister(process);
                    }
                } finally {
                    PROCESS_LIFECYCLE.unregister(process, globalRegistration.token());
                }
            });
        }
    }

    static void terminateImmediately(Process process) {
        List<ProcessHandle> descendants = signalTermination(process);
        awaitTermination(process, descendants);
    }

    static void requestTermination(Process process) {
        signalTermination(process);
    }

    private static void abortProcessExecution(
            Process process,
            CompletableFuture<String> stdout,
            CompletableFuture<String> stderr
    ) {
        closeProcessStreams(process);
        if (stdout != null) {
            stdout.cancel(true);
        }
        if (stderr != null) {
            stderr.cancel(true);
        }
        terminateImmediately(process);
    }

    private static void closeProcessStreams(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException | RuntimeException ignored) {
            // Best effort: forced process-tree termination below remains authoritative.
        }
        try {
            process.getErrorStream().close();
        } catch (IOException | RuntimeException ignored) {
            // Best effort: forced process-tree termination below remains authoritative.
        }
        try {
            process.getOutputStream().close();
        } catch (IOException | RuntimeException ignored) {
            // Best effort: forced process-tree termination below remains authoritative.
        }
    }

    private static List<ProcessHandle> signalTermination(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>();
        try {
            process.descendants().forEach(descendants::add);
        } catch (RuntimeException ignored) {
            // The root process is still terminated below if descendants cannot be enumerated.
        }
        descendants.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
        for (ProcessHandle descendant : descendants) {
            try {
                if (descendant.isAlive()) {
                    descendant.destroyForcibly();
                }
            } catch (RuntimeException ignored) {
                // Best effort; continue terminating the remaining process tree.
            }
        }
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (RuntimeException ignored) {
            // The process may already have exited between the checks.
        }

        return descendants;
    }

    private static void awaitTermination(Process process, List<ProcessHandle> descendants) {
        long deadline = System.nanoTime() + TERMINATION_WAIT_NANOS;
        boolean interrupted = false;
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0L && process.isAlive()) {
                process.waitFor(remaining, TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException exception) {
            interrupted = true;
        } catch (RuntimeException ignored) {
            // Exit verification is best effort for platform-specific Process implementations.
        }
        for (ProcessHandle descendant : descendants) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            try {
                if (descendant.isAlive()) {
                    descendant.onExit().get(remaining, TimeUnit.NANOSECONDS);
                }
            } catch (InterruptedException exception) {
                interrupted = true;
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException ignored) {
                // Continue checking the remaining descendants within the shared deadline.
            }
        }
        if (mayBeAlive(process) || descendants.stream().anyMatch(ExternalProcessRunner::mayBeAlive)) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "An external media process tree remained alive after forced termination"
            );
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void shutdownAllProcesses() {
        ProcessLifecycleRegistry.Invalidation<Process> shutdown = PROCESS_LIFECYCLE.beginShutdown();
        try {
            for (ProcessLifecycleRegistry.RegisteredTarget<Process> registration : shutdown.detached()) {
                try {
                    terminateImmediately(registration.target());
                } catch (RuntimeException ignored) {
                    // One broken process implementation must not block the remaining shutdown drain.
                }
            }
        } finally {
            PROCESS_LIFECYCLE.completeInvalidation(shutdown.token());
        }
    }

    private static boolean mayBeAlive(Process process) {
        try {
            return process.isAlive();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static boolean mayBeAlive(ProcessHandle process) {
        try {
            return process.isAlive();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static ExternalProcessException cancelledException(Throwable cause) {
        return new ExternalProcessException(
                "Harici medya aracının çalışması iptal edildi.",
                cause,
                ExternalProcessException.FailureKind.CANCELLED
        );
    }

    private static ExternalProcessException deadlineException(
            ExternalProcessCancellation cancellation,
            Throwable cause
    ) {
        if (cancellation.isCancelled()) {
            return cancelledException(cause);
        }
        return new ExternalProcessException(
                "Harici medya aracı zaman aşımına uğradı.",
                cause,
                ExternalProcessException.FailureKind.TIMEOUT
        );
    }

    private static String readStream(InputStream inputStream) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < MAX_CAPTURED_CHARACTERS) {
                    int remaining = MAX_CAPTURED_CHARACTERS - output.length();
                    output.append(line, 0, Math.min(line.length(), remaining)).append('\n');
                }
            }
        } catch (IOException ignored) {
            // Süreç sonlandırılırken stream'in kapanması normaldir.
        }
        return output.toString();
    }

    record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
