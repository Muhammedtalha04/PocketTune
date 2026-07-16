package com.pockettune.block;

import com.mojang.serialization.MapCodec;
import com.pockettune.block.entity.SpeakerBlockEntity;
import com.pockettune.registry.ModBlockEntities;
import com.pockettune.registry.ModDataComponents;
import com.pockettune.model.PortableSpeakerState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public final class SpeakerBlock extends BaseEntityBlock {
    public static final MapCodec<SpeakerBlock> CODEC = simpleCodec(SpeakerBlock::new);

    public SpeakerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpeakerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> blockEntityType
    ) {
        return createTickerHelper(
                blockEntityType,
                ModBlockEntities.SPEAKER.get(),
                level.isClientSide()
                        ? SpeakerBlockEntity::clientTick
                        : SpeakerBlockEntity::serverTick
        );
    }

    @Override
    public void setPlacedBy(
            Level level,
            BlockPos pos,
            BlockState state,
            @Nullable LivingEntity placer,
            ItemStack stack
    ) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide()) {
            return;
        }
        PortableSpeakerState portableState = stack.get(ModDataComponents.PORTABLE_SPEAKER_STATE.get());
        if (portableState != null && level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
            speaker.applyPortableStateFromServer(portableState.advanceTo(level.getGameTime()));
            if (placer instanceof Player player && player.getAbilities().instabuild) {
                stack.remove(ModDataComponents.PORTABLE_SPEAKER_STATE.get());
            }
        }
    }
}
