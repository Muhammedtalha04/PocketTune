package com.pockettune.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SpeakerInstanceIdentityTest {
    @Test
    void persistedIdentitySurvivesChunkReload() {
        UUID persisted = UUID.randomUUID();

        assertEquals(persisted, SpeakerInstanceIdentity.restoreOrCreate(persisted));
    }

    @Test
    void legacyOrUnassignedDataGetsAFreshPlacementIdentity() {
        UUID fromMissing = SpeakerInstanceIdentity.restoreOrCreate(null);
        UUID fromZero = SpeakerInstanceIdentity.restoreOrCreate(SpeakerInstanceIdentity.UNASSIGNED);

        assertTrue(SpeakerInstanceIdentity.isValid(fromMissing));
        assertTrue(SpeakerInstanceIdentity.isValid(fromZero));
        assertNotEquals(fromMissing, fromZero);
    }

    @Test
    void onlyTheExactAuthoritativePlacementMatches() {
        UUID authoritative = SpeakerInstanceIdentity.create();

        assertTrue(SpeakerInstanceIdentity.matches(authoritative, authoritative));
        assertFalse(SpeakerInstanceIdentity.matches(authoritative, SpeakerInstanceIdentity.create()));
        assertFalse(SpeakerInstanceIdentity.matches(SpeakerInstanceIdentity.UNASSIGNED, authoritative));
        assertFalse(SpeakerInstanceIdentity.matches(authoritative, SpeakerInstanceIdentity.UNASSIGNED));
    }
}
