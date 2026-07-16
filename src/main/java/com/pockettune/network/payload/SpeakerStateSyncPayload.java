package com.pockettune.network.payload;

import com.pockettune.PocketTune;
import com.pockettune.audio.YtDlpResolver;
import com.pockettune.model.SpeakerSettings;
import com.pockettune.model.TrackMetadata;
import com.pockettune.util.SpeakerInstanceIdentity;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SpeakerStateSyncPayload(
        BlockPos pos,
        UUID speakerInstanceId,
        String currentUrl,
        List<TrackMetadata> playlistQueue,
        int playlistIndex,
        long trackSequence,
        long elapsedMillis,
        boolean playing,
        boolean paused,
        SpeakerSettings settings
) implements CustomPacketPayload {
    public static final Type<SpeakerStateSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PocketTune.MOD_ID, "speaker_state_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerStateSyncPayload> STREAM_CODEC = StreamCodec.of(
            SpeakerStateSyncPayload::encode,
            SpeakerStateSyncPayload::decode
    );

    public SpeakerStateSyncPayload {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(speakerInstanceId, "speakerInstanceId");
        Objects.requireNonNull(currentUrl, "currentUrl");
        Objects.requireNonNull(playlistQueue, "playlistQueue");
        Objects.requireNonNull(settings, "settings");
        if (!SpeakerInstanceIdentity.isValid(speakerInstanceId)) {
            throw new IllegalArgumentException("speakerInstanceId must be assigned");
        }
        playlistQueue = List.copyOf(playlistQueue);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, SpeakerStateSyncPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
        buffer.writeUUID(payload.speakerInstanceId());
        buffer.writeUtf(payload.currentUrl(), SetSpeakerUrlPayload.MAX_URL_LENGTH);
        buffer.writeVarInt(payload.playlistQueue().size());
        for (TrackMetadata track : payload.playlistQueue()) {
            buffer.writeUtf(track.videoId(), YtDlpResolver.MAX_VIDEO_ID_LENGTH);
            buffer.writeUtf(track.title(), TrackMetadata.MAX_TITLE_LENGTH);
            buffer.writeUtf(track.artist(), TrackMetadata.MAX_ARTIST_LENGTH);
            buffer.writeVarLong(track.durationMillis());
        }
        buffer.writeVarInt(payload.playlistIndex());
        buffer.writeVarLong(payload.trackSequence());
        buffer.writeVarLong(payload.elapsedMillis());
        buffer.writeBoolean(payload.playing());
        buffer.writeBoolean(payload.paused());
        writeSettings(buffer, payload.settings());
    }

    private static SpeakerStateSyncPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = BlockPos.STREAM_CODEC.decode(buffer);
        UUID speakerInstanceId = buffer.readUUID();
        if (!SpeakerInstanceIdentity.isValid(speakerInstanceId)) {
            throw new DecoderException("Invalid PocketTune speaker instance identity");
        }
        String currentUrl = buffer.readUtf(SetSpeakerUrlPayload.MAX_URL_LENGTH);
        int queueSize = buffer.readVarInt();
        if (queueSize < 0 || queueSize > YtDlpResolver.MAX_PLAYLIST_ENTRIES) {
            throw new DecoderException("Invalid PocketTune playlist size: " + queueSize);
        }
        List<TrackMetadata> queue = new ArrayList<>(queueSize);
        for (int index = 0; index < queueSize; index++) {
            queue.add(new TrackMetadata(
                    buffer.readUtf(YtDlpResolver.MAX_VIDEO_ID_LENGTH),
                    buffer.readUtf(TrackMetadata.MAX_TITLE_LENGTH),
                    buffer.readUtf(TrackMetadata.MAX_ARTIST_LENGTH),
                    buffer.readVarLong()
            ));
        }
        return new SpeakerStateSyncPayload(
                pos,
                speakerInstanceId,
                currentUrl,
                queue,
                buffer.readVarInt(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                readSettings(buffer)
        );
    }

    private static void writeSettings(RegistryFriendlyByteBuf buffer, SpeakerSettings settings) {
        buffer.writeDouble(settings.volumePercent());
        buffer.writeVarInt(settings.rangeBlocks());
        buffer.writeByte(settings.fadeType().ordinal());
        buffer.writeBoolean(settings.wallOcclusion());
        buffer.writeDouble(settings.bassDb());
        buffer.writeDouble(settings.midDb());
        buffer.writeDouble(settings.trebleDb());
        buffer.writeBoolean(settings.shuffle());
        buffer.writeByte(settings.repeatMode().ordinal());
    }

    private static SpeakerSettings readSettings(RegistryFriendlyByteBuf buffer) {
        double volume = buffer.readDouble();
        int range = buffer.readVarInt();
        SpeakerSettings.FadeType fade = enumValue(
                SpeakerSettings.FadeType.values(), buffer.readUnsignedByte(), "fade type");
        boolean wallOcclusion = buffer.readBoolean();
        double bass = buffer.readDouble();
        double mid = buffer.readDouble();
        double treble = buffer.readDouble();
        boolean shuffle = buffer.readBoolean();
        SpeakerSettings.RepeatMode repeat = enumValue(
                SpeakerSettings.RepeatMode.values(), buffer.readUnsignedByte(), "repeat mode");
        return new SpeakerSettings(volume, range, fade, wallOcclusion, bass, mid, treble, shuffle, repeat);
    }

    private static <T> T enumValue(T[] values, int ordinal, String name) {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new DecoderException("Invalid PocketTune " + name + ": " + ordinal);
        }
        return values[ordinal];
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
