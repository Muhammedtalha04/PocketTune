package com.pockettune.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class ServerDebugLogGate<K> {
    private final long cooldownNanos;
    private final Map<K, Long> lastAuthorizationNanos = new HashMap<>();

    ServerDebugLogGate(long cooldownNanos) {
        if (cooldownNanos <= 0L) {
            throw new IllegalArgumentException("cooldownNanos must be positive");
        }
        this.cooldownNanos = cooldownNanos;
    }

    synchronized boolean tryAuthorize(K key, boolean requested, boolean permitted, long nowNanos) {
        Objects.requireNonNull(key, "key");
        if (!requested || !permitted) {
            return false;
        }

        Long previousNanos = lastAuthorizationNanos.get(key);
        if (previousNanos != null && nowNanos - previousNanos < cooldownNanos) {
            return false;
        }
        lastAuthorizationNanos.put(key, nowNanos);
        pruneExpired(nowNanos);
        return true;
    }

    private void pruneExpired(long nowNanos) {
        if (lastAuthorizationNanos.size() < 128) {
            return;
        }
        lastAuthorizationNanos.entrySet().removeIf(
                entry -> nowNanos - entry.getValue() >= cooldownNanos
        );
    }
}
