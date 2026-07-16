package com.pockettune.item;

import com.pockettune.model.PortableSpeakerState;
import com.pockettune.model.TrackMetadata;
import com.pockettune.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;

public final class SpeakerBlockItem extends BlockItem {
    public SpeakerBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean selected) {
        super.inventoryTick(stack, level, entity, slotId, selected);
        if (level.isClientSide() || Math.floorMod(level.getGameTime() + slotId, 20L) != 0L) {
            return;
        }
        PortableSpeakerState state = stack.get(ModDataComponents.PORTABLE_SPEAKER_STATE.get());
        if (state == null) {
            return;
        }
        PortableSpeakerState advanced = state.ensureIdentity().advanceTo(level.getGameTime());
        if (!advanced.equals(state)) {
            stack.set(ModDataComponents.PORTABLE_SPEAKER_STATE.get(), advanced);
        }
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            TooltipContext context,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        super.appendHoverText(stack, context, tooltip, flag);
        PortableSpeakerState state = stack.get(ModDataComponents.PORTABLE_SPEAKER_STATE.get());
        if (state == null || state.playlistQueue().isEmpty()) {
            tooltip.add(Component.literal("Boş hoparlör").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        TrackMetadata active = state.activeTrack();
        tooltip.add(Component.literal(active == null ? "YouTube" : active.title())
                .withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.literal(
                        (state.playing() ? state.paused() ? "Duraklatıldı" : "Envanterde çalıyor" : "Durdu")
                                + " • " + formatTime(state.elapsedMillis()))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal(
                        "Sıra " + (state.playlistIndex() + 1) + "/" + state.playlistQueue().size())
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1_000L;
        return "%d:%02d".formatted(totalSeconds / 60L, totalSeconds % 60L);
    }
}
