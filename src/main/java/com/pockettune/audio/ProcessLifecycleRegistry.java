package com.pockettune.audio;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.LongConsumer;

/**
 * Thread-safe state machine behind the mpv process registry.
 *
 * <p>Every lifecycle mutation is serialized by this object. Process termination deliberately
 * happens outside it: {@link #beginInvalidation()} and {@link #beginShutdown()} atomically detach
 * the exact registrations that the caller must terminate. The transition stays closed until
 * {@link #completeInvalidation(long)} so a process cannot slip in while detached processes are
 * still being terminated.</p>
 */
final class ProcessLifecycleRegistry<T> {
    static final long NO_REGISTRATION = 0L;

    private final IdentityHashMap<T, Entry> entries = new IdentityHashMap<>();
    private long sessionEpoch;
    private long pauseRevision;
    private long nextRegistrationToken;
    private long nextInvalidationToken;
    private long activeInvalidationToken;
    private boolean globalPauseRequested;
    private boolean invalidating;
    private boolean shutdown;

    synchronized long currentSessionEpoch() {
        return sessionEpoch;
    }

    synchronized boolean isShutdown() {
        return shutdown;
    }

    synchronized Registration register(T target, long expectedEpoch) {
        return register(target, expectedEpoch, ignored -> {
        });
    }

    synchronized Registration register(T target, long expectedEpoch, LongConsumer tokenPublisher) {
        return register(target, expectedEpoch, Integer.MAX_VALUE, tokenPublisher);
    }

    synchronized Registration register(
            T target,
            long expectedEpoch,
            int maximumEntries,
            LongConsumer tokenPublisher
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(tokenPublisher, "tokenPublisher");
        requirePositiveCapacity(maximumEntries);
        if (shutdown || invalidating || expectedEpoch != sessionEpoch || entries.containsKey(target)) {
            return Registration.rejected(sessionEpoch, RejectionReason.SESSION_UNAVAILABLE);
        }
        if (entries.size() >= maximumEntries) {
            return Registration.rejected(sessionEpoch, RejectionReason.CAPACITY);
        }

        long token = ++nextRegistrationToken;
        if (token == NO_REGISTRATION) {
            token = ++nextRegistrationToken;
        }
        tokenPublisher.accept(token);
        entries.put(target, new Entry(token));
        pauseRevision++;
        return Registration.accepted(token, sessionEpoch);
    }

    synchronized RejectionReason admissionRejection(long expectedEpoch, int maximumEntries) {
        requirePositiveCapacity(maximumEntries);
        if (shutdown || invalidating || expectedEpoch != sessionEpoch) {
            return RejectionReason.SESSION_UNAVAILABLE;
        }
        return entries.size() >= maximumEntries
                ? RejectionReason.CAPACITY
                : RejectionReason.NONE;
    }

    synchronized boolean unregister(T target, long registrationToken) {
        Entry entry = entries.get(target);
        if (entry == null || entry.registrationToken != registrationToken) {
            return false;
        }
        entries.remove(target);
        pauseRevision++;
        return true;
    }

    synchronized RegisteredTarget<T> currentRegistration(T target) {
        Entry entry = entries.get(target);
        return entry == null ? null : new RegisteredTarget<>(target, entry.registrationToken);
    }

    synchronized Invalidation<T> beginInvalidation() {
        return beginDrain(false);
    }

    synchronized Invalidation<T> beginShutdown() {
        return beginDrain(true);
    }

    private Invalidation<T> beginDrain(boolean permanentShutdown) {
        if (invalidating) {
            throw new IllegalStateException("A process invalidation is already in progress.");
        }
        invalidating = true;
        activeInvalidationToken = ++nextInvalidationToken;
        long invalidatedEpoch = sessionEpoch;
        // This is a deliberately transitional epoch. It rejects workers from the old session,
        // while completion advances once more to reject workers born during process termination.
        sessionEpoch++;
        globalPauseRequested = false;
        shutdown |= permanentShutdown;
        pauseRevision++;

        List<RegisteredTarget<T>> detached = new ArrayList<>(entries.size());
        entries.forEach((target, entry) ->
                detached.add(new RegisteredTarget<>(target, entry.registrationToken)));
        entries.clear();
        return new Invalidation<>(
                activeInvalidationToken,
                invalidatedEpoch,
                sessionEpoch,
                List.copyOf(detached),
                shutdown
        );
    }

    synchronized long completeInvalidation(long invalidationToken) {
        if (!invalidating || activeInvalidationToken != invalidationToken) {
            throw new IllegalStateException("Unknown or completed process invalidation token.");
        }
        invalidating = false;
        activeInvalidationToken = 0L;
        globalPauseRequested = false;
        if (!shutdown) {
            sessionEpoch++;
        }
        pauseRevision++;
        return sessionEpoch;
    }

    synchronized PauseChange<T> setGlobalPaused(boolean paused) {
        if (shutdown || invalidating) {
            return new PauseChange<>(false, List.of());
        }
        if (globalPauseRequested == paused) {
            return new PauseChange<>(false, List.of());
        }
        globalPauseRequested = paused;
        pauseRevision++;

        List<RegisteredTarget<T>> registrations = new ArrayList<>(entries.size());
        entries.forEach((target, entry) ->
                registrations.add(new RegisteredTarget<>(target, entry.registrationToken)));
        return new PauseChange<>(true, List.copyOf(registrations));
    }

    synchronized boolean isGlobalPauseRequested() {
        return globalPauseRequested;
    }

    synchronized PauseSnapshot setPlaybackPaused(T target, long registrationToken, boolean paused) {
        Entry entry = entries.get(target);
        if (entry == null || entry.registrationToken != registrationToken) {
            return null;
        }
        if (entry.playbackPaused != paused) {
            entry.playbackPaused = paused;
            pauseRevision++;
        }
        return pauseSnapshot(entry);
    }

    synchronized PauseSnapshot pauseSnapshot(T target, long registrationToken) {
        Entry entry = entries.get(target);
        if (entry == null || entry.registrationToken != registrationToken) {
            return null;
        }
        return pauseSnapshot(entry);
    }

    synchronized int activeCount() {
        return entries.size();
    }

    private PauseSnapshot pauseSnapshot(Entry entry) {
        return new PauseSnapshot(
                pauseRevision,
                globalPauseRequested || entry.playbackPaused
        );
    }

    private static void requirePositiveCapacity(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
    }

    enum RejectionReason {
        NONE,
        SESSION_UNAVAILABLE,
        CAPACITY
    }

    record Registration(boolean accepted, long token, long epoch, RejectionReason rejectionReason) {
        private static Registration accepted(long token, long epoch) {
            return new Registration(true, token, epoch, RejectionReason.NONE);
        }

        private static Registration rejected(long epoch, RejectionReason rejectionReason) {
            return new Registration(false, NO_REGISTRATION, epoch, rejectionReason);
        }
    }

    record RegisteredTarget<T>(T target, long token) {
    }

    record Invalidation<T>(
            long token,
            long invalidatedEpoch,
            long transitionEpoch,
            List<RegisteredTarget<T>> detached,
            boolean shutdown
    ) {
    }

    record PauseChange<T>(boolean changed, List<RegisteredTarget<T>> registrations) {
    }

    record PauseSnapshot(long revision, boolean effectivePaused) {
    }

    private static final class Entry {
        private final long registrationToken;
        private boolean playbackPaused;

        private Entry(long registrationToken) {
            this.registrationToken = registrationToken;
        }
    }
}
