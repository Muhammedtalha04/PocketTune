package com.pockettune.client;

/** Pure routing policy that guarantees an operation result is never shown in GUI and chat together. */
public final class FeedbackDeliveryPolicy {
    private FeedbackDeliveryPolicy() {
    }

    public static Destination choose(
            boolean error,
            boolean storedOnSpeaker,
            boolean guiNotificationsEnabled,
            boolean matchingSpeakerScreenOpen
    ) {
        if (storedOnSpeaker && guiNotificationsEnabled && matchingSpeakerScreenOpen) {
            return Destination.GUI;
        }
        return error ? Destination.CHAT : Destination.NONE;
    }

    public enum Destination {
        GUI,
        CHAT,
        NONE
    }
}
