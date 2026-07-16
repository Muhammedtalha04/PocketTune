package com.pockettune.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates non-zero operation identifiers that are unique for the lifetime of this sequence.
 * A single process-wide instance can therefore distinguish work owned by replaced object instances.
 */
public final class OperationIdSequence {
    private final AtomicLong lastIssued = new AtomicLong();

    public long next() {
        return lastIssued.updateAndGet(previous -> {
            long candidate = previous + 1L;
            return candidate == 0L ? 1L : candidate;
        });
    }
}
