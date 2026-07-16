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

public record AddSpeakerUrlPayload(BlockPos pos, UUID speakerInstanceId, String url)
        implements CustomPacketPayload {
    public static final Type<AddSpeakerUrlPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PocketTune.MOD_ID, "add_speaker_url"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AddSpeakerUrlPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
                buffer.writeUUID(payload.speakerInstanceId());
                buffer.writeUtf(payload.url(), SetSpeakerUrlPayload.MAX_URL_LENGTH);
            },
            buffer -> new AddSpeakerUrlPayload(
                    BlockPos.STREAM_CODEC.decode(buffer),
                    buffer.readUUID(),
                    buffer.readUtf(SetSpeakerUrlPayload.MAX_URL_LENGTH)
            )
    );

    public AddSpeakerUrlPayload {
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
