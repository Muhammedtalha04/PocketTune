package com.pockettune.util;

import java.util.UUID;

/** Authoritative per-placement identity used to reject packets from replaced block entities. */
public final class SpeakerInstanceIdentity {
    public static final UUID UNASSIGNED = new UUID(0L, 0L);

    private SpeakerInstanceIdentity() {
    }

    public static UUID create() {
        UUID identity;
        do {
            identity = UUID.randomUUID();
        } while (UNASSIGNED.equals(identity));
        return identity;
    }

    public static UUID restoreOrCreate(UUID persistedIdentity) {
        return isValid(persistedIdentity) ? persistedIdentity : create();
    }

    public static boolean isValid(UUID identity) {
        return identity != null && !UNASSIGNED.equals(identity);
    }

    public static boolean matches(UUID authoritativeIdentity, UUID reportedIdentity) {
        return isValid(authoritativeIdentity) && authoritativeIdentity.equals(reportedIdentity);
    }
}
