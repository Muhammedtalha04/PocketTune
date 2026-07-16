package com.pockettune.network.payload;

import com.pockettune.PocketTune;
import com.pockettune.util.SpeakerInstanceIdentity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record ChangeSpeakerTrackPayload(BlockPos pos, UUID speakerInstanceId, int direction)
        implements CustomPacketPayload {
    public static final Type<ChangeSpeakerTrackPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PocketTune.MOD_ID, "change_speaker_track"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ChangeSpeakerTrackPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
                buffer.writeUUID(payload.speakerInstanceId());
                buffer.writeByte(payload.direction());
            },
            buffer -> new ChangeSpeakerTrackPayload(
                    BlockPos.STREAM_CODEC.decode(buffer),
                    buffer.readUUID(),
                    buffer.readByte()
            )
    );

    public ChangeSpeakerTrackPayload {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(speakerInstanceId, "speakerInstanceId");
        if (!SpeakerInstanceIdentity.isValid(speakerInstanceId) || (direction != -1 && direction != 1)) {
            throw new IllegalArgumentException("Invalid PocketTune track-change payload");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
