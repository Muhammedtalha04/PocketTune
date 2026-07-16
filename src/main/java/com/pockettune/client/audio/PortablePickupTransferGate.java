package com.pockettune.client.audio;

import java.util.UUID;

/**
 * Prevents a source BlockEntity from reclaiming a controller while pickup is still being committed.
 */
public final class PortablePickupTransferGate {
    private UUID requestId;
    private boolean pending;
    private boolean committed;
    private boolean itemObservedAfterCommit;
    private boolean itemPresent;

    public void begin(UUID requestId) {
        this.requestId = requestId;
        pending = true;
        committed = false;
        itemObservedAfterCommit = false;
        itemPresent = false;
    }

    public boolean complete(UUID completedRequestId, boolean success) {
        if (!pending || requestId == null || !requestId.equals(completedRequestId)) {
            return false;
        }
        pending = false;
        committed = success;
        if (success && itemPresent) {
            itemObservedAfterCommit = true;
        } else if (!success) {
            itemObservedAfterCommit = false;
        }
        return true;
    }

    public void observeItem(boolean present) {
        itemPresent = present;
        if (committed && present) {
            itemObservedAfterCommit = true;
        }
    }

    public boolean canAttachToBlock() {
        return !pending
                && !itemPresent
                && (!committed || itemObservedAfterCommit);
    }

    public boolean pending() {
        return pending;
    }
}
