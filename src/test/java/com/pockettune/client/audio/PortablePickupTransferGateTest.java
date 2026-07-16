package com.pockettune.client.audio;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PortablePickupTransferGateTest {
    @Test
    void sourceBlockCannotReclaimSessionBeforePickupResult() {
        PortablePickupTransferGate gate = new PortablePickupTransferGate();
        UUID requestId = UUID.randomUUID();

        gate.begin(requestId);

        assertTrue(gate.pending());
        assertFalse(gate.canAttachToBlock());
    }

    @Test
    void successfulPickupRequiresObservedItemBeforeAPlacementCanAttach() {
        PortablePickupTransferGate gate = new PortablePickupTransferGate();
        UUID requestId = UUID.randomUUID();
        gate.begin(requestId);

        assertTrue(gate.complete(requestId, true));
        assertFalse(gate.canAttachToBlock());
        gate.observeItem(true);
        assertFalse(gate.canAttachToBlock());
        gate.observeItem(false);
        assertTrue(gate.canAttachToBlock());
    }

    @Test
    void failedPickupAllowsExplicitRollbackToTheExistingBlock() {
        PortablePickupTransferGate gate = new PortablePickupTransferGate();
        UUID requestId = UUID.randomUUID();
        gate.begin(requestId);

        assertTrue(gate.complete(requestId, false));
        assertTrue(gate.canAttachToBlock());
    }

    @Test
    void inventorySyncMayArriveBeforeTheSuccessResult() {
        PortablePickupTransferGate gate = new PortablePickupTransferGate();
        UUID requestId = UUID.randomUUID();
        gate.begin(requestId);
        gate.observeItem(true);

        assertTrue(gate.complete(requestId, true));
        assertFalse(gate.canAttachToBlock());
        gate.observeItem(false);
        assertTrue(gate.canAttachToBlock());
    }

    @Test
    void unrelatedResultCannotReleasePendingTransfer() {
        PortablePickupTransferGate gate = new PortablePickupTransferGate();
        gate.begin(UUID.randomUUID());

        assertFalse(gate.complete(UUID.randomUUID(), true));
        assertFalse(gate.canAttachToBlock());
    }
}
