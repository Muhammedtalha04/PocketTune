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

public record SetSpeakerUrlPayload(BlockPos pos, UUID speakerInstanceId, String url)
        implements CustomPacketPayload {
    public static final int MAX_URL_LENGTH = 2_048;
    public static final Type<SetSpeakerUrlPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PocketTune.MOD_ID, "set_speaker_url"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetSpeakerUrlPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
                buffer.writeUUID(payload.speakerInstanceId());
                buffer.writeUtf(payload.url(), MAX_URL_LENGTH);
            },
            buffer -> new SetSpeakerUrlPayload(
                    BlockPos.STREAM_CODEC.decode(buffer),
                    buffer.readUUID(),
                    buffer.readUtf(MAX_URL_LENGTH)
            )
    );

    public SetSpeakerUrlPayload {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(speakerInstanceId, "speakerInstanceId");
        Objects.requireNonNull(url, "url");
        if (!SpeakerInstanceIdentity.isValid(speakerInstanceId)) {
            throw new IllegalArgumentException("speakerInstanceId must be assigned");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
