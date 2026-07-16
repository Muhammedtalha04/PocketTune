package com.pockettune.model;

import com.pockettune.config.PocketTuneServerConfig;
import com.mojang.serialization.Codec;
import com.pockettune.audio.YtDlpResolver;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record PortableSpeakerState(
        UUID speakerId,
        String sourceUrl,
        List<TrackMetadata> playlistQueue,
        int playlistIndex,
        long trackSequence,
        long elapsedMillis,
        long serverGameTime,
        boolean playing,
        boolean paused,
        SpeakerSettings settings
) {
    private static final String ID_TAG = "SpeakerId";
    private static final String URL_TAG = "SourceUrl";
    private static final String QUEUE_TAG = "PlaylistQueue";
    private static final String INDEX_TAG = "PlaylistIndex";
    private static final String SEQUENCE_TAG = "TrackSequence";
    private static final String ELAPSED_TAG = "ElapsedMillis";
    private static final String GAME_TIME_TAG = "ServerGameTime";
    private static final String PLAYING_TAG = "Playing";
    private static final String PAUSED_TAG = "Paused";
    private static final String VIDEO_ID_TAG = "VideoId";
    private static final String TITLE_TAG = "Title";
    private static final String ARTIST_TAG = "Artist";
    private static final String DURATION_TAG = "DurationMillis";
    private static final String VOLUME_TAG = "VolumePercent";
    private static final String RANGE_TAG = "RangeBlocks";
    private static final String FADE_TAG = "FadeType";
    private static final String OCCLUSION_TAG = "WallOcclusion";
    private static final String BASS_TAG = "BassDb";
    private static final String MID_TAG = "MidDb";
    private static final String TREBLE_TAG = "TrebleDb";
    private static final String SHUFFLE_TAG = "Shuffle";
    private static final String REPEAT_TAG = "RepeatMode";

    public static final UUID UNASSIGNED_ID = new UUID(0L, 0L);
    public static final Codec<PortableSpeakerState> CODEC = CompoundTag.CODEC.xmap(
            PortableSpeakerState::fromTag,
            PortableSpeakerState::toTag
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, PortableSpeakerState> STREAM_CODEC = StreamCodec.of(
            (buffer, state) -> buffer.writeNbt(state.toTag()),
            buffer -> {
                CompoundTag tag = buffer.readNbt();
                return fromTag(tag == null ? new CompoundTag() : tag);
            }
    );

    public PortableSpeakerState {
        speakerId = speakerId == null ? UNASSIGNED_ID : speakerId;
        sourceUrl = YtDlpResolver.sanitizeStoredYoutubeUrl(sourceUrl);
        playlistQueue = playlistQueue == null
                ? List.of()
                : List.copyOf(playlistQueue.stream().limit(YtDlpResolver.MAX_PLAYLIST_ENTRIES).toList());
        playlistIndex = playlistQueue.isEmpty() ? 0 : Math.floorMod(playlistIndex, playlistQueue.size());
        trackSequence = Math.max(0L, trackSequence);
        elapsedMillis = Math.max(0L, Math.min(TrackMetadata.MAX_DURATION_MILLIS, elapsedMillis));
        serverGameTime = Math.max(0L, serverGameTime);
        playing = playing && !sourceUrl.isBlank() && !playlistQueue.isEmpty();
        paused = paused && playing;
        settings = PocketTuneServerConfig.constrain(
                settings == null ? PocketTuneServerConfig.defaultSpeakerSettings() : settings
        );
    }

    public PortableSpeakerState ensureIdentity() {
        return UNASSIGNED_ID.equals(speakerId)
                ? copyWith(UUID.randomUUID(), playlistIndex, trackSequence, elapsedMillis,
                        serverGameTime, playing, paused)
                : this;
    }

    public TrackMetadata activeTrack() {
        return playlistQueue.isEmpty() ? null : playlistQueue.get(playlistIndex);
    }

    public long elapsedAt(long gameTime) {
        if (!playing || paused) {
            return elapsedMillis;
        }
        long elapsedTicks = Math.max(0L, gameTime - serverGameTime);
        long addition = elapsedTicks > Long.MAX_VALUE / 50L ? Long.MAX_VALUE : elapsedTicks * 50L;
        long calculated = elapsedMillis > Long.MAX_VALUE - addition ? Long.MAX_VALUE : elapsedMillis + addition;
        TrackMetadata active = activeTrack();
        return active != null && active.durationMillis() > 0L
                ? Math.min(active.durationMillis(), calculated)
                : Math.min(TrackMetadata.MAX_DURATION_MILLIS, calculated);
    }

    public PortableSpeakerState advanceTo(long gameTime) {
        long safeGameTime = Math.max(0L, gameTime);
        if (!playing || paused || safeGameTime <= serverGameTime || playlistQueue.isEmpty()) {
            return copyWith(speakerId, playlistIndex, trackSequence, elapsedMillis,
                    safeGameTime, playing, paused);
        }

        long elapsedTicks = safeGameTime - serverGameTime;
        long addition = elapsedTicks > Long.MAX_VALUE / 50L ? Long.MAX_VALUE : elapsedTicks * 50L;
        long nextElapsed = elapsedMillis > Long.MAX_VALUE - addition ? Long.MAX_VALUE : elapsedMillis + addition;
        PortablePlaybackTimeline.Result result = PortablePlaybackTimeline.advance(
                playlistQueue,
                settings,
                playlistIndex,
                trackSequence,
                nextElapsed,
                playing,
                paused
        );
        return copyWith(
                speakerId,
                result.playlistIndex(),
                result.trackSequence(),
                result.elapsedMillis(),
                safeGameTime,
                result.playing(),
                result.paused()
        );
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(ID_TAG, speakerId);
        tag.putString(URL_TAG, sourceUrl);
        ListTag queue = new ListTag();
        for (TrackMetadata track : playlistQueue) {
            CompoundTag entry = new CompoundTag();
            entry.putString(VIDEO_ID_TAG, track.videoId());
            entry.putString(TITLE_TAG, track.title());
            entry.putString(ARTIST_TAG, track.artist());
            entry.putLong(DURATION_TAG, track.durationMillis());
            queue.add(entry);
        }
        tag.put(QUEUE_TAG, queue);
        tag.putInt(INDEX_TAG, playlistIndex);
        tag.putLong(SEQUENCE_TAG, trackSequence);
        tag.putLong(ELAPSED_TAG, elapsedMillis);
        tag.putLong(GAME_TIME_TAG, serverGameTime);
        tag.putBoolean(PLAYING_TAG, playing);
        tag.putBoolean(PAUSED_TAG, paused);
        tag.putDouble(VOLUME_TAG, settings.volumePercent());
        tag.putInt(RANGE_TAG, settings.rangeBlocks());
        tag.putInt(FADE_TAG, settings.fadeType().ordinal());
        tag.putBoolean(OCCLUSION_TAG, settings.wallOcclusion());
        tag.putDouble(BASS_TAG, settings.bassDb());
        tag.putDouble(MID_TAG, settings.midDb());
        tag.putDouble(TREBLE_TAG, settings.trebleDb());
        tag.putBoolean(SHUFFLE_TAG, settings.shuffle());
        tag.putInt(REPEAT_TAG, settings.repeatMode().ordinal());
        return tag;
    }

    public static PortableSpeakerState fromTag(CompoundTag tag) {
        UUID id = tag.hasUUID(ID_TAG) ? tag.getUUID(ID_TAG) : UNASSIGNED_ID;
        List<TrackMetadata> queue = new ArrayList<>();
        Tag rawQueue = tag.get(QUEUE_TAG);
        if (rawQueue instanceof ListTag list) {
            for (int index = 0; index < list.size() && index < YtDlpResolver.MAX_PLAYLIST_ENTRIES; index++) {
                if (!(list.get(index) instanceof CompoundTag entry)) {
                    continue;
                }
                String videoId = entry.getString(VIDEO_ID_TAG);
                if (YtDlpResolver.isValidVideoId(videoId)) {
                    queue.add(new TrackMetadata(
                            videoId,
                            entry.getString(TITLE_TAG),
                            entry.getString(ARTIST_TAG),
                            entry.getLong(DURATION_TAG)
                    ));
                }
            }
        }
        SpeakerSettings defaults = PocketTuneServerConfig.defaultSpeakerSettings();
        SpeakerSettings settings = new SpeakerSettings(
                tag.contains(VOLUME_TAG) ? tag.getDouble(VOLUME_TAG) : defaults.volumePercent(),
                tag.contains(RANGE_TAG) ? tag.getInt(RANGE_TAG) : defaults.rangeBlocks(),
                enumOrDefault(SpeakerSettings.FadeType.values(), tag.getInt(FADE_TAG), defaults.fadeType()),
                tag.getBoolean(OCCLUSION_TAG),
                tag.contains(BASS_TAG) ? tag.getDouble(BASS_TAG) : defaults.bassDb(),
                tag.contains(MID_TAG) ? tag.getDouble(MID_TAG) : defaults.midDb(),
                tag.contains(TREBLE_TAG) ? tag.getDouble(TREBLE_TAG) : defaults.trebleDb(),
                tag.getBoolean(SHUFFLE_TAG),
                enumOrDefault(SpeakerSettings.RepeatMode.values(), tag.getInt(REPEAT_TAG), defaults.repeatMode())
        );
        return new PortableSpeakerState(
                id,
                tag.getString(URL_TAG),
                queue,
                tag.getInt(INDEX_TAG),
                tag.getLong(SEQUENCE_TAG),
                tag.getLong(ELAPSED_TAG),
                tag.getLong(GAME_TIME_TAG),
                tag.getBoolean(PLAYING_TAG),
                tag.getBoolean(PAUSED_TAG),
                settings
        );
    }

    private PortableSpeakerState copyWith(
            UUID id,
            int index,
            long sequence,
            long elapsed,
            long gameTime,
            boolean isPlaying,
            boolean isPaused
    ) {
        return new PortableSpeakerState(
                id,
                sourceUrl,
                playlistQueue,
                index,
                sequence,
                elapsed,
                gameTime,
                isPlaying,
                isPaused,
                settings
        );
    }

    private static <T> T enumOrDefault(T[] values, int ordinal, T fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }
}
