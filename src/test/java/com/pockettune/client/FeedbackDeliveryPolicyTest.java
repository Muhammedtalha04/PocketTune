package com.pockettune.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FeedbackDeliveryPolicyTest {
    @Test
    void errorUsesGuiWithoutAlsoFallingBackToChat() {
        assertEquals(
                FeedbackDeliveryPolicy.Destination.GUI,
                FeedbackDeliveryPolicy.choose(true, true, true, true)
        );
    }

    @Test
    void errorFallsBackToChatWhenGuiCannotShowIt() {
        assertEquals(
                FeedbackDeliveryPolicy.Destination.CHAT,
                FeedbackDeliveryPolicy.choose(true, true, true, false)
        );
        assertEquals(
                FeedbackDeliveryPolicy.Destination.CHAT,
                FeedbackDeliveryPolicy.choose(true, true, false, true)
        );
        assertEquals(
                FeedbackDeliveryPolicy.Destination.CHAT,
                FeedbackDeliveryPolicy.choose(true, false, true, true)
        );
    }

    @Test
    void pendingAndSuccessNeverCreateChatNoise() {
        assertEquals(
                FeedbackDeliveryPolicy.Destination.NONE,
                FeedbackDeliveryPolicy.choose(false, true, false, false)
        );
        assertEquals(
                FeedbackDeliveryPolicy.Destination.GUI,
                FeedbackDeliveryPolicy.choose(false, true, true, true)
        );
    }
}
