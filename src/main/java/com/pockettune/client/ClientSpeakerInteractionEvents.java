package com.pockettune.client;

import com.pockettune.PocketTune;
import com.pockettune.audio.MpvProcessRegistry;
import com.pockettune.block.entity.SpeakerBlockEntity;
import com.pockettune.client.screen.SpeakerUrlScreen;
import com.pockettune.diagnostics.ExternalToolDiagnostics;
import com.pockettune.config.PocketTuneClientConfig;
import com.pockettune.client.audio.PortableSpeakerPlaybackManager;
import com.pockettune.client.audio.PauseMenuSessionState;
import com.pockettune.client.debug.BlockInteractionDebugTracker;
import com.pockettune.registry.ModBlocks;
import com.pockettune.network.payload.PickupPortableSpeakerPayload;
import com.pockettune.network.payload.BlockInteractionTracePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

@EventBusSubscriber(modid = PocketTune.MOD_ID, value = Dist.CLIENT)
public final class ClientSpeakerInteractionEvents {
    private static final PauseMenuSessionState PAUSE_MENU_SESSION = new PauseMenuSessionState();
    private static boolean pauseApplied;

    private ClientSpeakerInteractionEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()
                || !event.getLevel().getBlockState(event.getPos()).is(ModBlocks.SPEAKER)) {
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof SpeakerBlockEntity speaker)) {
            return;
        }
        if (event.getEntity().isSecondaryUseActive()) {
            UUID requestId = UUID.randomUUID();
            boolean debugRequested = BlockInteractionDebugTracker.enabled();
            BlockInteractionDebugTracker.recordLocal(
                    requestId,
                    event.getPos(),
                    BlockInteractionTracePayload.Stage.CLIENT_RIGHT_CLICK,
                    true,
                    "RightClickBlock iptal edildi; vanilla item kullanımı engellendi"
            );
            PacketDistributor.sendToServer(new PickupPortableSpeakerPayload(
                    event.getPos(),
                    speaker.getSpeakerInstanceId(),
                    requestId,
                    debugRequested,
                    debugRequested && PocketTuneClientConfig.LOG_BLOCK_INTERACTION_EVENTS.get()
            ));
            BlockInteractionDebugTracker.recordLocal(
                    requestId,
                    event.getPos(),
                    BlockInteractionTracePayload.Stage.CLIENT_PACKET_SENT,
                    true,
                    "PickupPortableSpeakerPayload"
            );
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) {
            return;
        }

        minecraft.setScreen(new SpeakerUrlScreen(event.getPos(), speaker.getSpeakerInstanceId()));
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @SubscribeEvent
    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        ExternalToolDiagnostics.checkAsync().thenAccept(status -> Minecraft.getInstance().execute(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (!status.ready() && minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "[PocketTune] İstemci ses araçları hazır değil: " + status.missingToolsMessage()),
                        false
                );
            }
        }));
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        resetPauseTracking();
        com.pockettune.client.gui.ThumbnailCache.instance().clear();
        PortableSpeakerPlaybackManager.clear();
        MpvProcessRegistry.invalidateAndTerminateAll();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean realPauseMenuOpen = minecraft.screen instanceof PauseScreen pauseScreen
                && pauseScreen.showsPauseMenu();
        boolean pauseMenuSessionActive = PAUSE_MENU_SESSION.update(
                minecraft.level != null,
                realPauseMenuOpen,
                minecraft.screen != null
        );
        // ESC menüsünden açılan bütün alt ekranlar aynı pause oturumunda kalır.
        // Doğrudan açılan envanter, sandık, crafting, mod ve PocketTune ekranları bu oturumu başlatmaz.
        boolean shouldPause = PocketTuneClientConfig.PAUSE_AUDIO_ON_PAUSE_SCREEN.get()
                && pauseMenuSessionActive;
        if (pauseApplied == shouldPause) {
            return;
        }
        pauseApplied = shouldPause;
        MpvProcessRegistry.setPaused(shouldPause);
    }

    @SubscribeEvent
    public static void onClientLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            resetPauseTracking();
            PortableSpeakerPlaybackManager.clear();
            MpvProcessRegistry.invalidateAndTerminateAll();
        }
    }

    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        resetPauseTracking();
        PortableSpeakerPlaybackManager.clear();
        MpvProcessRegistry.invalidateAndTerminateAll();
    }

    private static void resetPauseTracking() {
        PAUSE_MENU_SESSION.reset();
        pauseApplied = false;
    }
}
