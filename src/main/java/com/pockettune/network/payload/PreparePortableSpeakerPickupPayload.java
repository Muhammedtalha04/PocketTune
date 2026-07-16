package com.pockettune.network.payload;

import com.pockettune.PocketTune;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record PreparePortableSpeakerPickupPayload(BlockPos pos, UUID speakerId, UUID requestId)
        implements CustomPacketPayload {
    public static final Type<PreparePortableSpeakerPickupPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PocketTune.MOD_ID, "prepare_portable_speaker_pickup"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PreparePortableSpeakerPickupPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
                        buffer.writeUUID(payload.speakerId());
                        buffer.writeUUID(payload.requestId());
                    },
                    buffer -> new PreparePortableSpeakerPickupPayload(
                            BlockPos.STREAM_CODEC.decode(buffer),
                            buffer.readUUID(),
                            buffer.readUUID()
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
