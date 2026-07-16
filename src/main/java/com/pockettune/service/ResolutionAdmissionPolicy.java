package com.pockettune.service;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Thread-safe admission policy shared by playlist resolution and unavailable-track verification.
 * It limits each requester over a monotonic sliding window and reserves at most one active task
 * for a speaker key. Tickets make stale completion cleanup unable to release a newer reservation.
 */
final class ResolutionAdmissionPolicy<K, U> {
    private final int maximumRequests;
    private final long windowNanos;
    private final Map<U, ArrayDeque<Long>> requestsByUser = new HashMap<>();
    private final Map<K, Ticket<K>> activeByKey = new HashMap<>();
    private long nextTicketId;

    ResolutionAdmissionPolicy(int maximumRequests, long windowNanos) {
        if (maximumRequests < 1) {
            throw new IllegalArgumentException("maximumRequests must be positive");
        }
        if (windowNanos < 1L) {
            throw new IllegalArgumentException("windowNanos must be positive");
        }
        this.maximumRequests = maximumRequests;
        this.windowNanos = windowNanos;
    }

    synchronized Decision<K> tryAcquire(U user, K key, long nowNanos) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(key, "key");
        pruneExpiredRequests(nowNanos);

        if (activeByKey.containsKey(key)) {
            return Decision.rejected(RejectionReason.SPEAKER_BUSY, 0L);
        }

        ArrayDeque<Long> requests = requestsByUser.computeIfAbsent(user, ignored -> new ArrayDeque<>());
        if (requests.size() >= maximumRequests) {
            long retryAfterNanos = Math.max(1L, windowNanos - (nowNanos - requests.getFirst()));
            return Decision.rejected(RejectionReason.PLAYER_RATE_LIMITED, retryAfterNanos);
        }

        requests.addLast(nowNanos);
        Ticket<K> ticket = new Ticket<>(key, ++nextTicketId);
        activeByKey.put(key, ticket);
        return Decision.admitted(ticket);
    }

    synchronized boolean release(Ticket<K> ticket) {
        if (ticket == null) {
            return false;
        }
        return activeByKey.remove(ticket.key(), ticket);
    }

    synchronized int activeCount() {
        return activeByKey.size();
    }

    synchronized int trackedUserCount() {
        return requestsByUser.size();
    }

    synchronized void clear() {
        activeByKey.clear();
        requestsByUser.clear();
    }

    private void pruneExpiredRequests(long nowNanos) {
        Iterator<Map.Entry<U, ArrayDeque<Long>>> entries = requestsByUser.entrySet().iterator();
        while (entries.hasNext()) {
            ArrayDeque<Long> requests = entries.next().getValue();
            while (!requests.isEmpty() && nowNanos - requests.getFirst() >= windowNanos) {
                requests.removeFirst();
            }
            if (requests.isEmpty()) {
                entries.remove();
            }
        }
    }

    enum RejectionReason {
        NONE,
        SPEAKER_BUSY,
        PLAYER_RATE_LIMITED
    }

    record Ticket<K>(K key, long id) {
        Ticket {
            Objects.requireNonNull(key, "key");
        }
    }

    record Decision<K>(Ticket<K> ticket, RejectionReason rejectionReason, long retryAfterNanos) {
        Decision {
            Objects.requireNonNull(rejectionReason, "rejectionReason");
            if ((ticket == null) == (rejectionReason == RejectionReason.NONE)) {
                throw new IllegalArgumentException("Admission must have a ticket and rejection must not have one");
            }
            retryAfterNanos = Math.max(0L, retryAfterNanos);
        }

        static <K> Decision<K> admitted(Ticket<K> ticket) {
            return new Decision<>(Objects.requireNonNull(ticket, "ticket"), RejectionReason.NONE, 0L);
        }

        static <K> Decision<K> rejected(RejectionReason reason, long retryAfterNanos) {
            if (reason == RejectionReason.NONE) {
                throw new IllegalArgumentException("A rejection must have a rejection reason");
            }
            return new Decision<>(null, reason, retryAfterNanos);
        }

        boolean admitted() {
            return ticket != null;
        }
    }
}
