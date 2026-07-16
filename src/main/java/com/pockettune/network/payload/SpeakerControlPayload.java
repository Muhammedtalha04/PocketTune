package com.pockettune.network.payload;

import com.pockettune.PocketTune;
import com.pockettune.util.SpeakerInstanceIdentity;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record SpeakerControlPayload(BlockPos pos, UUID speakerInstanceId, Action action, double value)
        implements CustomPacketPayload {
    public static final Type<SpeakerControlPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PocketTune.MOD_ID, "speaker_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerControlPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
                buffer.writeUUID(payload.speakerInstanceId());
                buffer.writeByte(payload.action().ordinal());
                buffer.writeDouble(payload.value());
            },
            buffer -> new SpeakerControlPayload(
                    BlockPos.STREAM_CODEC.decode(buffer),
                    buffer.readUUID(),
                    decodeAction(buffer.readUnsignedByte()),
                    buffer.readDouble()
            )
    );

    public SpeakerControlPayload {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(speakerInstanceId, "speakerInstanceId");
        Objects.requireNonNull(action, "action");
        if (!SpeakerInstanceIdentity.isValid(speakerInstanceId) || !Double.isFinite(value)) {
            throw new IllegalArgumentException("Invalid PocketTune speaker control payload");
        }
    }

    private static Action decodeAction(int ordinal) {
        Action[] values = Action.values();
        if (ordinal >= values.length) {
            throw new DecoderException("Unknown PocketTune control action: " + ordinal);
        }
        return values[ordinal];
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public enum Action {
        PLAY_PAUSE,
        STOP,
        SEEK,
        SET_VOLUME,
        SET_RANGE,
        CYCLE_FADE,
        TOGGLE_OCCLUSION,
        SET_BASS,
        SET_MID,
        SET_TREBLE,
        TOGGLE_SHUFFLE,
        CYCLE_REPEAT
    }
}
