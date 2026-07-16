package com.pockettune.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ResolutionAdmissionPolicyTest {
    @Test
    void oneSpeakerCanOnlyHaveOneActiveResolution() {
        ResolutionAdmissionPolicy<String, String> policy = new ResolutionAdmissionPolicy<>(4, 100L);

        ResolutionAdmissionPolicy.Decision<String> first = policy.tryAcquire("player-a", "speaker", 0L);
        ResolutionAdmissionPolicy.Decision<String> second = policy.tryAcquire("player-b", "speaker", 1L);

        assertTrue(first.admitted());
        assertFalse(second.admitted());
        assertEquals(ResolutionAdmissionPolicy.RejectionReason.SPEAKER_BUSY, second.rejectionReason());
        assertEquals(1, policy.activeCount());
    }

    @Test
    void busyRejectionDoesNotConsumePlayerRateBudget() {
        ResolutionAdmissionPolicy<String, String> policy = new ResolutionAdmissionPolicy<>(2, 100L);

        ResolutionAdmissionPolicy.Decision<String> first = policy.tryAcquire("player", "speaker-a", 0L);
        assertFalse(policy.tryAcquire("player", "speaker-a", 1L).admitted());
        assertTrue(policy.release(first.ticket()));

        ResolutionAdmissionPolicy.Decision<String> second = policy.tryAcquire("player", "speaker-b", 2L);
        assertTrue(second.admitted());
        assertTrue(policy.release(second.ticket()));

        ResolutionAdmissionPolicy.Decision<String> limited = policy.tryAcquire("player", "speaker-c", 3L);
        assertEquals(ResolutionAdmissionPolicy.RejectionReason.PLAYER_RATE_LIMITED, limited.rejectionReason());
        assertEquals(97L, limited.retryAfterNanos());
    }

    @Test
    void rateWindowExpiresUsingMonotonicTime() {
        ResolutionAdmissionPolicy<String, String> policy = new ResolutionAdmissionPolicy<>(2, 100L);

        ResolutionAdmissionPolicy.Decision<String> first = policy.tryAcquire("player", "speaker-a", 0L);
        assertTrue(policy.release(first.ticket()));
        ResolutionAdmissionPolicy.Decision<String> second = policy.tryAcquire("player", "speaker-b", 10L);
        assertTrue(policy.release(second.ticket()));
        assertFalse(policy.tryAcquire("player", "speaker-c", 20L).admitted());

        ResolutionAdmissionPolicy.Decision<String> afterExpiry =
                policy.tryAcquire("player", "speaker-c", 100L);
        assertTrue(afterExpiry.admitted());
    }

    @Test
    void staleTicketCannotReleaseAReplacementReservation() {
        ResolutionAdmissionPolicy<String, String> policy = new ResolutionAdmissionPolicy<>(10, 100L);

        ResolutionAdmissionPolicy.Decision<String> first = policy.tryAcquire("player", "speaker", 0L);
        assertTrue(policy.release(first.ticket()));
        ResolutionAdmissionPolicy.Decision<String> replacement = policy.tryAcquire("player", "speaker", 1L);

        assertFalse(policy.release(first.ticket()));
        ResolutionAdmissionPolicy.Decision<String> concurrent =
                policy.tryAcquire("other-player", "speaker", 2L);
        assertEquals(ResolutionAdmissionPolicy.RejectionReason.SPEAKER_BUSY, concurrent.rejectionReason());
        assertTrue(policy.release(replacement.ticket()));
        assertEquals(0, policy.activeCount());
    }

    @Test
    void expiredPlayerBucketsAreRemoved() {
        ResolutionAdmissionPolicy<String, String> policy = new ResolutionAdmissionPolicy<>(2, 100L);

        ResolutionAdmissionPolicy.Decision<String> first = policy.tryAcquire("old-player", "speaker-a", 0L);
        assertTrue(policy.release(first.ticket()));
        assertEquals(1, policy.trackedUserCount());

        policy.tryAcquire("new-player", "speaker-b", 100L);
        assertEquals(1, policy.trackedUserCount());
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ResolutionAdmissionPolicy<>(0, 1L));
        assertThrows(IllegalArgumentException.class, () -> new ResolutionAdmissionPolicy<>(1, 0L));
    }

    @Test
    void clearReleasesActiveReservationsAndPlayerReferences() {
        ResolutionAdmissionPolicy<String, String> policy = new ResolutionAdmissionPolicy<>(2, 100L);
        ResolutionAdmissionPolicy.Decision<String> admitted =
                policy.tryAcquire("player", "speaker", 0L);

        policy.clear();

        assertEquals(0, policy.activeCount());
        assertEquals(0, policy.trackedUserCount());
        assertFalse(policy.release(admitted.ticket()));
        assertTrue(policy.tryAcquire("player", "speaker", 1L).admitted());
    }
}
