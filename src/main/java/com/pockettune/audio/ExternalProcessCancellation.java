package com.pockettune.audio;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cancellation scope for one or more external processes owned by a higher-level operation.
 * Cancellation is sticky: a process registered after cancellation is terminated immediately.
 */
public final class ExternalProcessCancellation {
    private final Object lock = new Object();
    private final Map<Process, Boolean> processes = new IdentityHashMap<>();
    private final List<Runnable> drainListeners = new ArrayList<>();
    private boolean cancelled;

    boolean register(Process process) {
        Objects.requireNonNull(process, "process");
        synchronized (lock) {
            if (!cancelled) {
                processes.put(process, Boolean.TRUE);
                return true;
            }
        }
        ExternalProcessRunner.terminateImmediately(process);
        return false;
    }

    void unregister(Process process) {
        List<Runnable> listeners = List.of();
        synchronized (lock) {
            if (processes.remove(process) != null && processes.isEmpty() && !drainListeners.isEmpty()) {
                listeners = List.copyOf(drainListeners);
                drainListeners.clear();
            }
        }
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // One owner cleanup must not prevent the remaining lifecycle listeners.
            }
        }
    }

    /** Runs once all currently registered processes have positively exited and been unregistered. */
    public void onDrained(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        boolean runNow;
        synchronized (lock) {
            runNow = processes.isEmpty();
            if (!runNow) {
                drainListeners.add(listener);
            }
        }
        if (runNow) {
            listener.run();
        }
    }

    public boolean cancel() {
        return cancel(true);
    }

    /** Sets the sticky cancellation flag and signals process termination without waiting for exit. */
    public boolean requestCancellation() {
        return cancel(false);
    }

    private boolean cancel(boolean awaitExit) {
        List<Process> snapshot;
        boolean firstRequest;
        synchronized (lock) {
            firstRequest = !cancelled;
            cancelled = true;
            snapshot = List.copyOf(processes.keySet());
        }
        for (Process process : snapshot) {
            try {
                if (awaitExit) {
                    ExternalProcessRunner.terminateImmediately(process);
                } else {
                    ExternalProcessRunner.requestTermination(process);
                }
            } catch (RuntimeException ignored) {
                // A broken Process implementation must not prevent the remaining tree cleanup.
            }
        }
        return firstRequest;
    }

    public boolean isCancelled() {
        synchronized (lock) {
            return cancelled;
        }
    }
}
