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

public record SpeakerTrackResultPayload(
        BlockPos pos,
        UUID speakerInstanceId,
        int playlistIndex,
        long trackSequence,
        String videoId,
        Result result
) implements CustomPacketPayload {
    public static final Type<SpeakerTrackResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PocketTune.MOD_ID, "speaker_track_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerTrackResultPayload> STREAM_CODEC = StreamCodec.of(
            SpeakerTrackResultPayload::encode,
            SpeakerTrackResultPayload::decode
    );

    public SpeakerTrackResultPayload {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(speakerInstanceId, "speakerInstanceId");
        Objects.requireNonNull(videoId, "videoId");
        Objects.requireNonNull(result, "result");
        if (!SpeakerInstanceIdentity.isValid(speakerInstanceId)) {
            throw new IllegalArgumentException("speakerInstanceId must be assigned");
        }
        if (playlistIndex < 0 || trackSequence < 0L || !YtDlpResolver.isValidVideoId(videoId)) {
            throw new IllegalArgumentException("Invalid PocketTune track identity");
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, SpeakerTrackResultPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
        buffer.writeUUID(payload.speakerInstanceId());
        buffer.writeVarInt(payload.playlistIndex());
        buffer.writeVarLong(payload.trackSequence());
        buffer.writeUtf(payload.videoId(), YtDlpResolver.MAX_VIDEO_ID_LENGTH);
        buffer.writeByte(payload.result().ordinal());
    }

    private static SpeakerTrackResultPayload decode(RegistryFriendlyByteBuf buffer) {
        try {
            return new SpeakerTrackResultPayload(
                    BlockPos.STREAM_CODEC.decode(buffer),
                    buffer.readUUID(),
                    buffer.readVarInt(),
                    buffer.readVarLong(),
                    buffer.readUtf(YtDlpResolver.MAX_VIDEO_ID_LENGTH),
                    decodeResult(buffer.readUnsignedByte())
            );
        } catch (IllegalArgumentException exception) {
            throw new DecoderException("Invalid PocketTune track-result payload", exception);
        }
    }

    private static Result decodeResult(int ordinal) {
        Result[] values = Result.values();
        if (ordinal >= values.length) {
            throw new DecoderException("Unknown PocketTune track result: " + ordinal);
        }
        return values[ordinal];
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public enum Result {
        FINISHED,
        UNAVAILABLE
    }
}
