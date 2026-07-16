package com.pockettune.network.payload;

import com.pockettune.PocketTune;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record PortableSpeakerPickupResultPayload(
        BlockPos pos,
        UUID speakerId,
        UUID requestId,
        boolean success,
        boolean serverSpeakerPresent,
        String reason
) implements CustomPacketPayload {
    public static final int MAX_REASON_LENGTH = 160;
    public static final Type<PortableSpeakerPickupResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PocketTune.MOD_ID, "portable_speaker_pickup_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PortableSpeakerPickupResultPayload> STREAM_CODEC =
            StreamCodec.of(PortableSpeakerPickupResultPayload::encode, PortableSpeakerPickupResultPayload::decode);

    public PortableSpeakerPickupResultPayload {
        reason = reason == null ? "" : reason.strip();
        if (reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH);
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, PortableSpeakerPickupResultPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
        buffer.writeUUID(payload.speakerId());
        buffer.writeUUID(payload.requestId());
        buffer.writeBoolean(payload.success());
        buffer.writeBoolean(payload.serverSpeakerPresent());
        buffer.writeUtf(payload.reason(), MAX_REASON_LENGTH);
    }

    private static PortableSpeakerPickupResultPayload decode(RegistryFriendlyByteBuf buffer) {
        return new PortableSpeakerPickupResultPayload(
                BlockPos.STREAM_CODEC.decode(buffer),
                buffer.readUUID(),
                buffer.readUUID(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readUtf(MAX_REASON_LENGTH)
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
