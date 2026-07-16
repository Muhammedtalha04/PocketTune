package com.pockettune.client;

import com.pockettune.block.entity.SpeakerBlockEntity;
import com.pockettune.client.screen.SpeakerUrlScreen;
import com.pockettune.config.PocketTuneClientConfig;
import com.pockettune.network.payload.SpeakerOperationFeedbackPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** Routes one server operation result to either the active speaker GUI or a chat fallback. */
public final class ClientSpeakerFeedback {
    private ClientSpeakerFeedback() {
    }

    public static void handle(SpeakerOperationFeedbackPayload payload, Player player) {
        boolean appliedToSpeaker = false;
        if (player.level().getBlockEntity(payload.pos()) instanceof SpeakerBlockEntity speaker) {
            speaker.applyOperationFeedback(payload.state(), payload.message());
            appliedToSpeaker = true;
        }

        Minecraft minecraft = Minecraft.getInstance();
        boolean matchingScreenOpen = minecraft.screen instanceof SpeakerUrlScreen screen
                && screen.displaysSpeaker(payload.pos());
        FeedbackDeliveryPolicy.Destination destination = FeedbackDeliveryPolicy.choose(
                payload.state() == SpeakerOperationFeedbackPayload.State.ERROR,
                appliedToSpeaker,
                PocketTuneClientConfig.SHOW_GUI_NOTIFICATIONS.get(),
                matchingScreenOpen
        );
        if (destination == FeedbackDeliveryPolicy.Destination.CHAT && minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.literal("[PocketTune] " + payload.message()),
                    false
            );
        }
    }

    public static void showPortableTerminalError(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.literal("[PocketTune] Taşınabilir hoparlör: " + message),
                        false
                );
            }
        });
    }
}
