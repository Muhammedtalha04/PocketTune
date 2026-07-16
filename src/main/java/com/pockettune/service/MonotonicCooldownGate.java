package com.pockettune.service;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/** Thread-safe, monotonic cooldown used to bound repeated feedback for the same key. */
final class MonotonicCooldownGate<K> {
    private final long cooldownNanos;
    private final Map<K, Long> lastAcceptedByKey = new HashMap<>();

    MonotonicCooldownGate(long cooldownNanos) {
        if (cooldownNanos < 1L) {
            throw new IllegalArgumentException("cooldownNanos must be positive");
        }
        this.cooldownNanos = cooldownNanos;
    }

    synchronized boolean tryAcquire(K key, long nowNanos) {
        Objects.requireNonNull(key, "key");
        pruneExpired(nowNanos);
        Long previous = lastAcceptedByKey.get(key);
        if (previous != null && nowNanos - previous < cooldownNanos) {
            return false;
        }
        lastAcceptedByKey.put(key, nowNanos);
        return true;
    }

    synchronized void clear() {
        lastAcceptedByKey.clear();
    }

    synchronized int trackedKeyCount() {
        return lastAcceptedByKey.size();
    }

    private void pruneExpired(long nowNanos) {
        Iterator<Map.Entry<K, Long>> entries = lastAcceptedByKey.entrySet().iterator();
        while (entries.hasNext()) {
            if (nowNanos - entries.next().getValue() >= cooldownNanos) {
                entries.remove();
            }
        }
    }
}
