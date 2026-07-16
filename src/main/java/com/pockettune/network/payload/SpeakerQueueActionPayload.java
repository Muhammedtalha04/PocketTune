package com.pockettune.network.payload;

import com.pockettune.PocketTune;
import com.pockettune.audio.YtDlpResolver;
import com.pockettune.util.SpeakerInstanceIdentity;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record SpeakerQueueActionPayload(
        BlockPos pos,
        UUID speakerInstanceId,
        Action action,
        int fromIndex,
        int toIndex
)
        implements CustomPacketPayload {
    public static final Type<SpeakerQueueActionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PocketTune.MOD_ID, "speaker_queue_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerQueueActionPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
                buffer.writeUUID(payload.speakerInstanceId());
                buffer.writeByte(payload.action().ordinal());
                buffer.writeVarInt(payload.fromIndex());
                buffer.writeVarInt(payload.toIndex());
            },
            buffer -> new SpeakerQueueActionPayload(
                    BlockPos.STREAM_CODEC.decode(buffer),
                    buffer.readUUID(),
                    decodeAction(buffer.readUnsignedByte()),
                    buffer.readVarInt(),
                    buffer.readVarInt()
            )
    );

    public SpeakerQueueActionPayload {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(speakerInstanceId, "speakerInstanceId");
        Objects.requireNonNull(action, "action");
        if (!SpeakerInstanceIdentity.isValid(speakerInstanceId)
                || fromIndex < 0
                || fromIndex >= YtDlpResolver.MAX_PLAYLIST_ENTRIES
                || toIndex < 0
                || toIndex >= YtDlpResolver.MAX_PLAYLIST_ENTRIES) {
            throw new IllegalArgumentException("Invalid PocketTune queue-action payload");
        }
    }

    private static Action decodeAction(int ordinal) {
        Action[] values = Action.values();
        if (ordinal >= values.length) {
            throw new DecoderException("Unknown PocketTune queue action: " + ordinal);
        }
        return values[ordinal];
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public enum Action {
        PLAY_NOW,
        PLAY_NEXT,
        REMOVE,
        MOVE,
        MOVE_TOP,
        MOVE_BOTTOM
    }
}
