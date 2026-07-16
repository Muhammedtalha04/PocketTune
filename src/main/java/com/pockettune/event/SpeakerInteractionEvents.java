package com.pockettune.event;

import com.pockettune.PocketTune;
import com.pockettune.network.ModNetworking;
import com.pockettune.registry.ModBlocks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber(modid = PocketTune.MOD_ID)
public final class SpeakerInteractionEvents {
    private SpeakerInteractionEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return;
        }

        boolean alreadyCanceled = event.isCanceled();
        if (event.getEntity() instanceof ServerPlayer player
                && ModNetworking.consumePendingPickupRightClick(player, event.getPos(), alreadyCanceled)) {
            if (!alreadyCanceled) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }
        if (alreadyCanceled) {
            return;
        }
        if (!event.getLevel().getBlockState(event.getPos()).is(ModBlocks.SPEAKER)) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModNetworking.expirePendingPickup(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModNetworking.clearPendingPickup(player);
        }
    }
}
