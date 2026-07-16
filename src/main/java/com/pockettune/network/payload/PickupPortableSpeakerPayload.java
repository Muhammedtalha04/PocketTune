package com.pockettune.network.payload;

import com.pockettune.PocketTune;
import com.pockettune.util.SpeakerInstanceIdentity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;
import java.util.Objects;

public record PickupPortableSpeakerPayload(
        BlockPos pos,
        UUID speakerInstanceId,
        UUID requestId,
        boolean debugRequested,
        boolean logRequested
)
        implements CustomPacketPayload {
    public static final Type<PickupPortableSpeakerPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PocketTune.MOD_ID, "pickup_portable_speaker"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PickupPortableSpeakerPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
                        buffer.writeUUID(payload.speakerInstanceId());
                        buffer.writeUUID(payload.requestId());
                        buffer.writeBoolean(payload.debugRequested());
                        buffer.writeBoolean(payload.logRequested());
                    },
                    buffer -> new PickupPortableSpeakerPayload(
                            BlockPos.STREAM_CODEC.decode(buffer),
                            buffer.readUUID(),
                            buffer.readUUID(),
                            buffer.readBoolean(),
                            buffer.readBoolean()
                    )
            );

    public PickupPortableSpeakerPayload {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(speakerInstanceId, "speakerInstanceId");
        Objects.requireNonNull(requestId, "requestId");
        if (!SpeakerInstanceIdentity.isValid(speakerInstanceId)
                || requestId.equals(SpeakerInstanceIdentity.UNASSIGNED)) {
            throw new IllegalArgumentException("Invalid PocketTune pickup payload identity");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
