package com.pockettune.block.entity;

import com.mojang.logging.LogUtils;
import com.pockettune.audio.AudioCleanupCoordinator;
import com.pockettune.audio.BoundedStartupExecutor;
import com.pockettune.audio.ExternalProcessException;
import com.pockettune.audio.ExternalProcessCancellation;
import com.pockettune.audio.CancellableStartupLease;
import com.pockettune.audio.MpvController;
import com.pockettune.audio.MpvProcessRegistry;
import com.pockettune.audio.PlaybackStartupGate;
import com.pockettune.audio.PlaybackStartupOwnership;
import com.pockettune.audio.PlaybackFailureMessages;
import com.pockettune.audio.YtDlpResolver;
import com.pockettune.audio.SpatialAudioMath;
import com.pockettune.config.PocketTuneClientConfig;
import com.pockettune.config.PocketTuneServerConfig;
import com.pockettune.network.ModNetworking;
import com.pockettune.network.payload.SpeakerStateSyncPayload;
import com.pockettune.network.payload.SpeakerOperationFeedbackPayload;
import com.pockettune.network.payload.SpeakerTrackResultPayload;
import com.pockettune.network.payload.SpeakerControlPayload;
import com.pockettune.network.payload.SpeakerQueueActionPayload;
import com.pockettune.model.SpeakerSettings;
import com.pockettune.model.TrackMetadata;
import com.pockettune.model.PlaybackTiming;
import com.pockettune.model.PortableSpeakerState;
import com.pockettune.client.audio.PortableSpeakerPlaybackManager;
import com.pockettune.client.ClientSpeakerFeedback;
import com.pockettune.client.debug.BlockInteractionDebugTracker;
import com.pockettune.network.payload.BlockInteractionTracePayload;
import com.pockettune.registry.ModBlockEntities;
import com.pockettune.util.OperationIdSequence;
import com.pockettune.util.SpeakerInstanceIdentity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;

public final class SpeakerBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String URL_TAG = "CurrentUrl";
    private static final String PLAYLIST_QUEUE_TAG = "PlaylistQueue";
    private static final String PLAYLIST_INDEX_TAG = "PlaylistIndex";
    private static final String TRACK_SEQUENCE_TAG = "TrackSequence";
    private static final String ELAPSED_MILLIS_TAG = "ElapsedMillis";
    private static final String PLAYING_TAG = "Playing";
    private static final String PAUSED_TAG = "Paused";
    private static final String TITLE_TAG = "Title";
    private static final String ARTIST_TAG = "Artist";
    private static final String DURATION_TAG = "DurationMillis";
    private static final String VIDEO_ID_TAG = "VideoId";
    private static final String VOLUME_TAG = "VolumePercent";
    private static final String RANGE_TAG = "RangeBlocks";
    private static final String FADE_TAG = "FadeType";
    private static final String OCCLUSION_TAG = "WallOcclusion";
    private static final String BASS_TAG = "BassDb";
    private static final String MID_TAG = "MidDb";
    private static final String TREBLE_TAG = "TrebleDb";
    private static final String SHUFFLE_TAG = "Shuffle";
    private static final String REPEAT_TAG = "RepeatMode";
    private static final String PORTABLE_ID_TAG = "PortableSpeakerId";
    private static final String INSTANCE_ID_TAG = "SpeakerInstanceId";
    private static final double DRIFT_CORRECTION_THRESHOLD_SECONDS = 2.5D;
    private static final int VOLUME_UPDATE_INTERVAL_TICKS = 5;
    private static final int TRACK_STATUS_INTERVAL_TICKS = 5;
    private static final int PERSIST_INTERVAL_TICKS = 20;
    private static final int STATE_SYNC_INTERVAL_TICKS = 100;
    private static final BoundedStartupExecutor STARTUP_EXECUTOR =
            new BoundedStartupExecutor(2, 16, "PocketTune speaker startup");
    private static final ExecutorService CONTROL_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PocketTune speaker control");
        thread.setDaemon(true);
        return thread;
    });
    private static final Path RUNTIME_DIRECTORY = FMLPaths.GAMEDIR.get().resolve("pockettune-runtime");
    private static final OperationIdSequence RESOLUTION_OPERATION_IDS = new OperationIdSequence();

    private String currentUrl = "";
    private List<TrackMetadata> playlistQueue = List.of();
    private int playlistIndex;
    private long trackSequence;
    private volatile long elapsedMillis;
    private boolean playing;
    private volatile boolean paused;
    private SpeakerSettings settings;
    private int persistCountdown = PERSIST_INTERVAL_TICKS;
    private int stateSyncCountdown = STATE_SYNC_INTERVAL_TICKS;
    private long playlistResolutionToken;
    private long unavailableVerificationSequence = -1L;
    private long unavailableVerificationOperationId;
    private long unavailableVerificationCompletedSequence = -1L;
    private final Set<Integer> unavailableTrackIndices = new HashSet<>();
    private UUID portableSpeakerId = PortableSpeakerState.UNASSIGNED_ID;
    private UUID speakerInstanceId = SpeakerInstanceIdentity.create();
    private long operationFeedbackSequence;
    private SpeakerOperationFeedbackPayload.State operationFeedbackState =
            SpeakerOperationFeedbackPayload.State.SUCCESS;
    private String operationFeedbackMessage = "";

    private volatile MpvController controller;
    private CancellableStartupLease<MpvController> audioStartupLease;
    private ExternalProcessCancellation audioStartupCancellation;
    private BoundedStartupExecutor.TaskHandle audioStartupTask;
    private volatile String startupError = "";
    private volatile boolean audioDisposed;
    private volatile long audioGeneration;
    private volatile boolean volumeUpdateInFlight;
    private volatile boolean driftSyncRequested;
    private volatile boolean driftSyncInFlight;
    private volatile boolean trackStatusInFlight;
    private volatile boolean trackResultReported;
    private volatile boolean audioSettingsDirty = true;
    private volatile boolean audioSettingsInFlight;
    private volatile int audioSettingsRetryCountdown;
    private volatile double lastAppliedVolume = -1.0D;
    private final Object audioOwnershipLock = new Object();
    private final PlaybackStartupGate playbackStartup = new PlaybackStartupGate();
    private String requestedTrackId = "";
    private long requestedTrackSequence = -1L;
    private volatile boolean feedbackShown;
    private int volumeUpdateCountdown;
    private int trackStatusCountdown;
    private int outOfRangeTicks;
    private double occlusionSmoothed = 1.0D;

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPEAKER.get(), pos, state);
        settings = PocketTuneServerConfig.defaultSpeakerSettings();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SpeakerBlockEntity speaker) {
        SpeakerSettings constrainedSettings = PocketTuneServerConfig.constrain(speaker.settings);
        if (!constrainedSettings.equals(speaker.settings)) {
            speaker.settings = constrainedSettings;
            speaker.publishServerState();
        }
        if (speaker.playing && !speaker.paused && !speaker.playlistQueue.isEmpty()) {
            speaker.elapsedMillis = Math.min(Long.MAX_VALUE - 50L, speaker.elapsedMillis) + 50L;
            TrackMetadata activeTrack = speaker.getActiveTrack();
            if (activeTrack != null
                    && PlaybackTiming.hasReachedKnownEnd(speaker.elapsedMillis, activeTrack.durationMillis())) {
                speaker.unavailableTrackIndices.clear();
                speaker.finishCurrentTrack();
                return;
            }
            if (--speaker.persistCountdown <= 0) {
                speaker.persistCountdown = PERSIST_INTERVAL_TICKS;
                speaker.setChanged();
            }
        }
        if (--speaker.stateSyncCountdown <= 0) {
            speaker.stateSyncCountdown = STATE_SYNC_INTERVAL_TICKS;
            ModNetworking.syncSpeakerState(speaker);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, SpeakerBlockEntity speaker) {
        speaker.clientTick(level, pos);
    }

    public String getCurrentUrl() {
        return currentUrl;
    }

    public int getPlaylistIndex() {
        return playlistIndex;
    }

    public int getPlaylistSize() {
        return playlistQueue.size();
    }

    public List<TrackMetadata> getPlaylistQueue() {
        return playlistQueue;
    }

    public TrackMetadata getActiveTrack() {
        return playlistQueue.isEmpty() ? null : playlistQueue.get(playlistIndex);
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isPaused() {
        return paused;
    }

    public SpeakerSettings getSettings() {
        return settings;
    }

    public UUID getPortableSpeakerId() {
        return portableSpeakerId;
    }

    public UUID getSpeakerInstanceId() {
        return speakerInstanceId;
    }

    public long getOperationFeedbackSequence() {
        return operationFeedbackSequence;
    }

    public SpeakerOperationFeedbackPayload.State getOperationFeedbackState() {
        return operationFeedbackState;
    }

    public String getOperationFeedbackMessage() {
        return operationFeedbackMessage;
    }

    public void applyOperationFeedback(SpeakerOperationFeedbackPayload.State state, String message) {
        operationFeedbackState = state;
        operationFeedbackMessage = message;
        operationFeedbackSequence++;
    }

    public String getPlaybackStatus() {
        if (!playing || playlistQueue.isEmpty()) {
            return "Stopped";
        }
        if (paused) {
            return "Paused";
        }
        if (!startupError.isBlank()) {
            return "Error";
        }
        return controller == null ? "Buffering" : "Playing";
    }

    public PortableSpeakerState createPortableState(long gameTime) {
        if (PortableSpeakerState.UNASSIGNED_ID.equals(portableSpeakerId)) {
            portableSpeakerId = UUID.randomUUID();
        }
        return new PortableSpeakerState(
                portableSpeakerId,
                currentUrl,
                playlistQueue,
                playlistIndex,
                trackSequence,
                elapsedMillis,
                gameTime,
                playing,
                paused,
                settings
        );
    }

    public void applyPortableStateFromServer(PortableSpeakerState portableState) {
        PortableSpeakerState state = portableState.ensureIdentity();
        portableSpeakerId = state.speakerId();
        currentUrl = state.sourceUrl();
        playlistQueue = state.playlistQueue();
        playlistIndex = state.playlistIndex();
        trackSequence = state.trackSequence();
        elapsedMillis = state.elapsedMillis();
        playing = state.playing();
        paused = state.paused();
        settings = PocketTuneServerConfig.constrain(state.settings());
        unavailableTrackIndices.clear();
        invalidatePlaylistResolution();
        publishServerState();
    }

    public void preparePortablePickupOnClient(UUID speakerId, UUID requestId) {
        if (level == null || !level.isClientSide()) {
            return;
        }
        BlockInteractionDebugTracker.recordLocal(
                requestId,
                worldPosition,
                BlockInteractionTracePayload.Stage.CLIENT_PREPARE_RECEIVED,
                true,
                "Prepare payload handled by the BlockEntity"
        );
        portableSpeakerId = speakerId;
        PortableSpeakerState portableState = createPortableState(level.getGameTime());
        MpvController transferredController;
        StartupCancellation cancelledStartup;
        String transferredTrackId;
        long transferredSequence;
        synchronized (audioOwnershipLock) {
            transferredController = controller;
            transferredTrackId = requestedTrackId;
            transferredSequence = requestedTrackSequence;
            controller = null;
            requestedTrackId = "";
            requestedTrackSequence = -1L;
            playbackStartup.cancel();
            cancelledStartup = cancelAudioStartupLocked();
            ++audioGeneration;
        }
        cancelStartup(cancelledStartup);
        PortableSpeakerPlaybackManager.adoptFromBlock(
                portableState,
                transferredController,
                transferredTrackId,
                transferredSequence,
                requestId
        );
        BlockInteractionDebugTracker.recordLocal(
                requestId,
                worldPosition,
                BlockInteractionTracePayload.Stage.CLIENT_CONTROLLER_DETACHED,
                true,
                transferredController == null
                        ? "No controller; the item session will start it when needed"
                        : "Live controller detached from the source BlockEntity"
        );
    }

    public boolean acceptPortableController(
            UUID speakerId,
            MpvController transferredController,
            String transferredTrackId,
            long transferredSequence
    ) {
        if (transferredController == null || !transferredController.isAlive()) {
            return false;
        }
        MpvController replacement;
        StartupCancellation cancelledStartup;
        synchronized (audioOwnershipLock) {
            if (audioDisposed
                    || !speakerId.equals(portableSpeakerId)
                    || !transferredController.isAlive()) {
                return false;
            }
            cancelledStartup = cancelAudioStartupLocked();
            ++audioGeneration;
            replacement = controller;
            controller = transferredController;
            requestedTrackId = transferredTrackId;
            requestedTrackSequence = transferredSequence;
            playbackStartup.adoptReady(transferredTrackId, transferredSequence);
            startupError = "";
            feedbackShown = true;
            audioSettingsDirty = true;
            lastAppliedVolume = -1.0D;
        }
        cancelStartup(cancelledStartup);
        if (replacement != transferredController) {
            closeAsync(replacement);
        }
        return true;
    }

    public long beginPlaylistResolution() {
        playlistResolutionToken = RESOLUTION_OPERATION_IDS.next();
        return playlistResolutionToken;
    }

    public boolean completePlaylistResolution(long token, String sourceUrl, List<TrackMetadata> tracks) {
        if (token != playlistResolutionToken
                || tracks.isEmpty()
                || tracks.size() > YtDlpResolver.MAX_PLAYLIST_ENTRIES) {
            return false;
        }
        currentUrl = sourceUrl;
        playlistQueue = List.copyOf(tracks);
        playlistIndex = 0;
        advanceTrackSequence();
        elapsedMillis = 0L;
        playing = true;
        paused = false;
        unavailableTrackIndices.clear();
        publishServerState();
        return true;
    }

    public boolean cancelPlaylistResolution(long token) {
        if (token != playlistResolutionToken) {
            return false;
        }
        invalidatePlaylistResolution();
        return true;
    }

    public void stopFromServer() {
        invalidatePlaylistResolution();
        currentUrl = "";
        playlistQueue = List.of();
        playlistIndex = 0;
        advanceTrackSequence();
        elapsedMillis = 0L;
        playing = false;
        paused = false;
        unavailableTrackIndices.clear();
        publishServerState();
    }

    public boolean changeTrackFromServer(int direction) {
        if ((direction != -1 && direction != 1) || playlistQueue.isEmpty()) {
            return false;
        }
        unavailableTrackIndices.clear();
        advancePlaylist(direction);
        return true;
    }

    public boolean completeAppendResolution(long token, String sourceUrl, List<TrackMetadata> tracks) {
        if (token != playlistResolutionToken
                || tracks.isEmpty()
                || playlistQueue.size() + tracks.size() > YtDlpResolver.MAX_PLAYLIST_ENTRIES) {
            return false;
        }
        boolean wasEmpty = playlistQueue.isEmpty();
        List<TrackMetadata> combined = new java.util.ArrayList<>(playlistQueue.size() + tracks.size());
        combined.addAll(playlistQueue);
        combined.addAll(tracks);
        playlistQueue = List.copyOf(combined);
        if (wasEmpty) {
            currentUrl = sourceUrl;
            playlistIndex = 0;
            elapsedMillis = 0L;
            playing = true;
            paused = false;
            advanceTrackSequence();
        }
        publishServerState();
        return true;
    }

    public boolean applyQueueAction(SpeakerQueueActionPayload.Action action, int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= playlistQueue.size()) {
            return false;
        }
        return switch (action) {
            case PLAY_NOW -> playTrack(fromIndex);
            case PLAY_NEXT -> moveTrack(fromIndex, Math.min(playlistQueue.size() - 1, playlistIndex + 1));
            case REMOVE -> removeTrack(fromIndex);
            case MOVE -> moveTrack(fromIndex, toIndex);
            case MOVE_TOP -> moveTrack(fromIndex, 0);
            case MOVE_BOTTOM -> moveTrack(fromIndex, playlistQueue.size() - 1);
        };
    }

    private boolean playTrack(int index) {
        playlistIndex = index;
        elapsedMillis = 0L;
        playing = true;
        paused = false;
        advanceTrackSequence();
        publishServerState();
        return true;
    }

    private boolean removeTrack(int index) {
        List<TrackMetadata> mutable = new java.util.ArrayList<>(playlistQueue);
        boolean removingActive = index == playlistIndex;
        mutable.remove(index);
        playlistQueue = List.copyOf(mutable);
        if (playlistQueue.isEmpty()) {
            stopFromServer();
            return true;
        }
        if (index < playlistIndex || playlistIndex >= playlistQueue.size()) {
            playlistIndex = Math.max(0, playlistIndex - 1);
        }
        if (removingActive) {
            elapsedMillis = 0L;
            advanceTrackSequence();
        }
        publishServerState();
        return true;
    }

    private boolean moveTrack(int fromIndex, int toIndex) {
        if (toIndex < 0 || toIndex >= playlistQueue.size()) {
            return false;
        }
        if (fromIndex == toIndex) {
            return true;
        }
        List<TrackMetadata> mutable = new java.util.ArrayList<>(playlistQueue);
        TrackMetadata moved = mutable.remove(fromIndex);
        mutable.add(toIndex, moved);
        if (fromIndex == playlistIndex) {
            playlistIndex = toIndex;
        } else if (fromIndex < playlistIndex && toIndex >= playlistIndex) {
            playlistIndex--;
        } else if (fromIndex > playlistIndex && toIndex <= playlistIndex) {
            playlistIndex++;
        }
        playlistQueue = List.copyOf(mutable);
        publishServerState();
        return true;
    }

    public boolean applyControl(SpeakerControlPayload.Action action, double value) {
        if (requiresFiniteValue(action) && !Double.isFinite(value)) {
            return false;
        }
        SpeakerSettings next = settings;
        switch (action) {
            case PLAY_PAUSE -> {
                if (playlistQueue.isEmpty()) {
                    return false;
                }
                if (!playing) {
                    playing = true;
                    paused = false;
                    elapsedMillis = 0L;
                    advanceTrackSequence();
                } else {
                    paused = !paused;
                }
            }
            case STOP -> {
                if (playlistQueue.isEmpty()) {
                    return false;
                }
                playing = false;
                paused = false;
                elapsedMillis = 0L;
            }
            case SEEK -> {
                if (!playing || playlistQueue.isEmpty() || !Double.isFinite(value)) {
                    return false;
                }
                long maximum = getActiveTrack().durationMillis() > 0L
                        ? getActiveTrack().durationMillis()
                        : TrackMetadata.MAX_DURATION_MILLIS;
                elapsedMillis = Math.max(0L, Math.min(maximum, Math.round(value)));
                advanceTrackSequence();
            }
            case SET_VOLUME -> next = copySettings(value, settings.rangeBlocks(), settings.fadeType(),
                    settings.wallOcclusion(), settings.bassDb(), settings.midDb(), settings.trebleDb(),
                    settings.shuffle(), settings.repeatMode());
            case SET_RANGE -> next = copySettings(settings.volumePercent(), (int) Math.round(value), settings.fadeType(),
                    settings.wallOcclusion(), settings.bassDb(), settings.midDb(), settings.trebleDb(),
                    settings.shuffle(), settings.repeatMode());
            case CYCLE_FADE -> next = copySettings(settings.volumePercent(), settings.rangeBlocks(),
                    cycle(settings.fadeType()), settings.wallOcclusion(), settings.bassDb(), settings.midDb(),
                    settings.trebleDb(), settings.shuffle(), settings.repeatMode());
            case TOGGLE_OCCLUSION -> next = copySettings(settings.volumePercent(), settings.rangeBlocks(),
                    settings.fadeType(), !settings.wallOcclusion(), settings.bassDb(), settings.midDb(),
                    settings.trebleDb(), settings.shuffle(), settings.repeatMode());
            case SET_BASS -> next = copySettings(settings.volumePercent(), settings.rangeBlocks(), settings.fadeType(),
                    settings.wallOcclusion(), value, settings.midDb(), settings.trebleDb(), settings.shuffle(),
                    settings.repeatMode());
            case SET_MID -> next = copySettings(settings.volumePercent(), settings.rangeBlocks(), settings.fadeType(),
                    settings.wallOcclusion(), settings.bassDb(), value, settings.trebleDb(), settings.shuffle(),
                    settings.repeatMode());
            case SET_TREBLE -> next = copySettings(settings.volumePercent(), settings.rangeBlocks(), settings.fadeType(),
                    settings.wallOcclusion(), settings.bassDb(), settings.midDb(), value, settings.shuffle(),
                    settings.repeatMode());
            case TOGGLE_SHUFFLE -> next = copySettings(settings.volumePercent(), settings.rangeBlocks(),
                    settings.fadeType(), settings.wallOcclusion(), settings.bassDb(), settings.midDb(),
                    settings.trebleDb(), !settings.shuffle(), settings.repeatMode());
            case CYCLE_REPEAT -> next = copySettings(settings.volumePercent(), settings.rangeBlocks(),
                    settings.fadeType(), settings.wallOcclusion(), settings.bassDb(), settings.midDb(),
                    settings.trebleDb(), settings.shuffle(), cycle(settings.repeatMode()));
        }
        settings = next;
        publishServerState();
        return true;
    }

    private static boolean requiresFiniteValue(SpeakerControlPayload.Action action) {
        return switch (action) {
            case SEEK, SET_VOLUME, SET_RANGE, SET_BASS, SET_MID, SET_TREBLE -> true;
            default -> false;
        };
    }

    private static SpeakerSettings copySettings(
            double volume, int range, SpeakerSettings.FadeType fade, boolean occlusion,
            double bass, double mid, double treble, boolean shuffle, SpeakerSettings.RepeatMode repeat
    ) {
        return PocketTuneServerConfig.constrain(
                new SpeakerSettings(volume, range, fade, occlusion, bass, mid, treble, shuffle, repeat)
        );
    }

    private static SpeakerSettings.FadeType cycle(SpeakerSettings.FadeType value) {
        SpeakerSettings.FadeType[] values = SpeakerSettings.FadeType.values();
        return values[(value.ordinal() + 1) % values.length];
    }

    private static SpeakerSettings.RepeatMode cycle(SpeakerSettings.RepeatMode value) {
        SpeakerSettings.RepeatMode[] values = SpeakerSettings.RepeatMode.values();
        return values[(value.ordinal() + 1) % values.length];
    }

    public TrackResultDecision acceptTrackResult(
            int reportedIndex,
            long reportedSequence,
            String reportedVideoId,
            SpeakerTrackResultPayload.Result result
    ) {
        if (!matchesActiveTrack(reportedIndex, reportedSequence, reportedVideoId)) {
            return TrackResultDecision.REJECTED;
        }

        if (result == SpeakerTrackResultPayload.Result.FINISHED) {
            if (!isReasonableTrackEnd()) {
                return TrackResultDecision.REJECTED;
            }
            unavailableTrackIndices.clear();
            finishCurrentTrack();
            return TrackResultDecision.ADVANCED;
        }

        return TrackResultDecision.REJECTED;
    }

    public long beginUnavailableVerification(int index, long sequence, String videoId) {
        if (!matchesActiveTrack(index, sequence, videoId)
                || unavailableVerificationSequence == sequence
                || unavailableVerificationCompletedSequence == sequence) {
            return 0L;
        }
        unavailableVerificationSequence = sequence;
        unavailableVerificationOperationId = RESOLUTION_OPERATION_IDS.next();
        return unavailableVerificationOperationId;
    }

    public TrackResultDecision completeUnavailableVerification(
            int index,
            long sequence,
            String videoId,
            long verificationOperationId,
            boolean serverConfirmedUnavailable
    ) {
        if (unavailableVerificationSequence != sequence
                || unavailableVerificationOperationId != verificationOperationId) {
            return TrackResultDecision.REJECTED;
        }
        unavailableVerificationSequence = -1L;
        unavailableVerificationOperationId = 0L;
        unavailableVerificationCompletedSequence = sequence;
        if (!serverConfirmedUnavailable || !matchesActiveTrack(index, sequence, videoId)) {
            return TrackResultDecision.REJECTED;
        }

        unavailableTrackIndices.add(playlistIndex);
        if (unavailableTrackIndices.size() >= playlistQueue.size()) {
            playing = false;
            elapsedMillis = 0L;
            publishServerState();
            return TrackResultDecision.STOPPED_ALL_UNAVAILABLE;
        }
        advancePlaylist(1);
        return TrackResultDecision.SKIPPED_UNAVAILABLE;
    }

    public void cancelUnavailableVerification(
            int index,
            long sequence,
            String videoId,
            long verificationOperationId
    ) {
        if (unavailableVerificationSequence == sequence
                && unavailableVerificationOperationId == verificationOperationId
                && matchesActiveTrack(index, sequence, videoId)) {
            unavailableVerificationSequence = -1L;
            unavailableVerificationOperationId = 0L;
        }
    }

    private boolean matchesActiveTrack(int index, long sequence, String videoId) {
        return playing
                && !playlistQueue.isEmpty()
                && index == playlistIndex
                && sequence == trackSequence
                && getActiveVideoId().equals(videoId);
    }

    private boolean isReasonableTrackEnd() {
        long authoritativeDuration = getActiveTrack().durationMillis();
        return PlaybackTiming.isServerKnownEndReportPlausible(elapsedMillis, authoritativeDuration);
    }

    private void advancePlaylist(int direction) {
        playlistIndex = Math.floorMod(playlistIndex + direction, playlistQueue.size());
        advanceTrackSequence();
        elapsedMillis = 0L;
        playing = true;
        publishServerState();
    }

    private void finishCurrentTrack() {
        if (settings.repeatMode() == SpeakerSettings.RepeatMode.ONE) {
            elapsedMillis = 0L;
            advanceTrackSequence();
            publishServerState();
            return;
        }
        if (settings.shuffle() && playlistQueue.size() > 1) {
            int next = java.util.concurrent.ThreadLocalRandom.current().nextInt(playlistQueue.size() - 1);
            playlistIndex = next >= playlistIndex ? next + 1 : next;
            elapsedMillis = 0L;
            advanceTrackSequence();
            publishServerState();
            return;
        }
        if (playlistIndex == playlistQueue.size() - 1
                && settings.repeatMode() == SpeakerSettings.RepeatMode.OFF) {
            playing = false;
            paused = false;
            elapsedMillis = getActiveTrack().durationMillis();
            publishServerState();
            return;
        }
        advancePlaylist(1);
    }

    private void advanceTrackSequence() {
        trackSequence = trackSequence == Long.MAX_VALUE ? 0L : trackSequence + 1L;
        unavailableVerificationSequence = -1L;
        unavailableVerificationOperationId = 0L;
        unavailableVerificationCompletedSequence = -1L;
    }

    private void invalidatePlaylistResolution() {
        playlistResolutionToken = RESOLUTION_OPERATION_IDS.next();
    }

    private void publishServerState() {
        persistCountdown = PERSIST_INTERVAL_TICKS;
        stateSyncCountdown = STATE_SYNC_INTERVAL_TICKS;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            ModNetworking.syncSpeakerState(this);
        }
    }

    public void applySyncedState(
            UUID syncedInstanceId,
            String url,
            List<TrackMetadata> queue,
            int index,
            long sequence,
            long elapsed,
            boolean isPlaying,
            boolean isPaused,
            SpeakerSettings syncedSettings
    ) {
        speakerInstanceId = SpeakerInstanceIdentity.restoreOrCreate(syncedInstanceId);
        List<TrackMetadata> safeQueue = queue.size() <= YtDlpResolver.MAX_PLAYLIST_ENTRIES
                ? List.copyOf(queue)
                : List.of();
        int safeIndex = safeQueue.isEmpty() ? 0 : Math.floorMod(index, safeQueue.size());
        String nextTrackId = safeQueue.isEmpty() ? "" : safeQueue.get(safeIndex).videoId();
        boolean sameActiveTrack = requestedTrackId.equals(nextTrackId)
                && requestedTrackSequence == sequence
                && isPlaying;
        long previousElapsed = elapsedMillis;

        boolean pauseChanged = paused != (isPaused && isPlaying);
        currentUrl = url;
        playlistQueue = safeQueue;
        playlistIndex = safeIndex;
        trackSequence = Math.max(0L, sequence);
        long syncedElapsed = Math.max(0L, elapsed);
        elapsedMillis = sameActiveTrack && !isPaused
                ? Math.max(previousElapsed, syncedElapsed)
                : syncedElapsed;
        playing = isPlaying && !safeQueue.isEmpty() && !url.isBlank();
        paused = isPaused && playing;
        if (pauseChanged) {
            audioSettingsDirty = true;
        }
        if (!settings.equals(syncedSettings)) {
            settings = PocketTuneServerConfig.constrain(syncedSettings);
            audioSettingsDirty = true;
        }
        if (sameActiveTrack) {
            driftSyncRequested = true;
        }
        setChanged();
    }

    public SpeakerStateSyncPayload createSyncPayload() {
        return new SpeakerStateSyncPayload(
                worldPosition,
                speakerInstanceId,
                currentUrl,
                playlistQueue,
                playlistIndex,
                trackSequence,
                elapsedMillis,
                playing,
                paused,
                settings
        );
    }

    private String getActiveVideoId() {
        return playlistQueue.isEmpty() ? "" : playlistQueue.get(playlistIndex).videoId();
    }

    private void clientTick(Level level, BlockPos pos) {
        if (!PortableSpeakerState.UNASSIGNED_ID.equals(portableSpeakerId)) {
            PortableSpeakerPlaybackManager.AttachmentState attachmentState =
                    PortableSpeakerPlaybackManager.tryAttachToBlock(this);
            if (attachmentState == PortableSpeakerPlaybackManager.AttachmentState.TRANSFER_PENDING) {
                return;
            }
        }
        if (playing && !paused && !MpvProcessRegistry.isPauseRequested() && !playlistQueue.isEmpty()) {
            elapsedMillis = Math.min(Long.MAX_VALUE - 50L, elapsedMillis) + 50L;
        }
        String activeTrackId = getActiveVideoId();
        if (!playing || activeTrackId.isBlank()) {
            stopAudioForState();
            return;
        }
        Player listener = localPlayer(level);
        boolean outsideAudibleRange = listener == null || listener.distanceToSqr(
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D
        ) >= (double) settings.rangeBlocks() * settings.rangeBlocks();
        if (outsideAudibleRange) {
            outOfRangeTicks++;
            if ((controller == null && requestedTrackId.isEmpty())
                    || outOfRangeTicks >= PocketTuneClientConfig.OUT_OF_RANGE_GRACE_SECONDS.get() * 20) {
                stopAudioForState();
                return;
            }
        } else {
            outOfRangeTicks = 0;
        }
        MpvController activeController = controller;
        if (activeController != null && !activeController.isAlive()) {
            startupError = "Playback stopped unexpectedly; preparing it again.";
            detachAndClose(activeController);
            feedbackShown = false;
            activeController = null;
        }
        PlaybackStartupGate.Decision startupDecision;
        synchronized (audioOwnershipLock) {
            activeController = controller;
            boolean controllerReady = activeController != null
                    && activeTrackId.equals(requestedTrackId)
                    && requestedTrackSequence == trackSequence;
            startupDecision = playbackStartup.tick(activeTrackId, trackSequence, controllerReady);
        }
        if (startupDecision.shouldStart()) {
            restartAudioAsync(
                    activeTrackId,
                    playlistIndex,
                    trackSequence,
                    elapsedMillis,
                    startupDecision.token()
            );
            activeController = null;
        }

        announceStartupResult(level, pos);
        if (activeController == null) {
            return;
        }

        if (audioSettingsRetryCountdown > 0) {
            audioSettingsRetryCountdown--;
        }
        if (audioSettingsDirty && !audioSettingsInFlight && audioSettingsRetryCountdown <= 0) {
            scheduleAudioSettings(activeController);
        }

        if (volumeUpdateCountdown-- <= 0) {
            volumeUpdateCountdown = VOLUME_UPDATE_INTERVAL_TICKS;
            scheduleVolumeUpdate(level, pos, activeController);
        }
        if (!MpvProcessRegistry.isPauseRequested() && trackStatusCountdown-- <= 0) {
            trackStatusCountdown = TRACK_STATUS_INTERVAL_TICKS;
            scheduleTrackStatus(activeController, playlistIndex, trackSequence, activeTrackId);
        }
        if (!MpvProcessRegistry.isPauseRequested() && driftSyncRequested && !driftSyncInFlight) {
            scheduleDriftCorrection(activeController, elapsedMillis);
        }
    }

    private void restartAudioAsync(
            String videoId,
            int index,
            long sequence,
            long seekMillis,
            long startupToken
    ) {
        long generation;
        long sessionEpoch;
        boolean pausedSnapshot;
        MpvController previousController;
        StartupCancellation cancelledStartup;
        CancellableStartupLease<MpvController> requestLease = new CancellableStartupLease<>();
        ExternalProcessCancellation requestCancellation = new ExternalProcessCancellation();
        synchronized (audioOwnershipLock) {
            if (audioDisposed) {
                playbackStartup.cancel();
                return;
            }
            requestedTrackId = videoId;
            requestedTrackSequence = sequence;
            startupError = "";
            feedbackShown = false;
            driftSyncRequested = false;
            trackResultReported = false;
            lastAppliedVolume = -1.0D;
            cancelledStartup = cancelAudioStartupLocked();
            audioStartupLease = requestLease;
            audioStartupCancellation = requestCancellation;
            audioStartupTask = null;
            generation = ++audioGeneration;
            sessionEpoch = MpvProcessRegistry.currentSessionEpoch();
            pausedSnapshot = paused;
            previousController = controller;
            controller = null;
        }
        cancelStartup(cancelledStartup);
        closeAsync(previousController);

        Runnable startupWork = () -> {
            MpvController startedController = null;
            boolean published = false;
            try {
                synchronized (audioOwnershipLock) {
                    if (!PlaybackStartupOwnership.isCurrent(
                            generation,
                            audioGeneration,
                            sessionEpoch,
                            MpvProcessRegistry.currentSessionEpoch(),
                            audioDisposed
                    ) || audioStartupLease != requestLease
                            || audioStartupCancellation != requestCancellation
                            || requestCancellation.isCancelled()) {
                        return;
                    }
                }
                String videoUrl = YtDlpResolver.videoUrl(videoId);
                String streamUrl = new YtDlpResolver().resolveAudioStream(videoUrl, requestCancellation);
                long synchronizedSeekMillis = Math.max(seekMillis, elapsedMillis);
                synchronized (audioOwnershipLock) {
                    if (!PlaybackStartupOwnership.isCurrent(
                            generation,
                            audioGeneration,
                            sessionEpoch,
                            MpvProcessRegistry.currentSessionEpoch(),
                            audioDisposed
                    ) || audioStartupLease != requestLease
                            || audioStartupCancellation != requestCancellation
                            || requestCancellation.isCancelled()) {
                        return;
                    }
                }
                startedController = MpvController.startPrepared(
                        streamUrl,
                        RUNTIME_DIRECTORY,
                        0.0D,
                        false,
                        true,
                        synchronizedSeekMillis / 1_000.0D,
                        pausedSnapshot,
                        sessionEpoch,
                        requestLease
                );

                synchronized (audioOwnershipLock) {
                    if (audioDisposed
                            || generation != audioGeneration
                            || sessionEpoch != MpvProcessRegistry.currentSessionEpoch()
                            || audioStartupLease != requestLease
                            || audioStartupCancellation != requestCancellation) {
                        return;
                    }
                }
                long latestSeekMillis = Math.max(synchronizedSeekMillis, elapsedMillis);
                startedController.ensurePlaybackProgressAfterResume(
                        latestSeekMillis / 1_000.0D,
                        paused
                );

                boolean accepted;
                synchronized (audioOwnershipLock) {
                    accepted = !audioDisposed
                            && generation == audioGeneration
                            && sessionEpoch == MpvProcessRegistry.currentSessionEpoch()
                            && audioStartupLease == requestLease
                            && audioStartupCancellation == requestCancellation
                            && requestLease.publish(startedController)
                            && playbackStartup.completeSuccess(videoId, sequence, startupToken);
                    if (accepted) {
                        audioStartupLease = null;
                        audioStartupCancellation = null;
                        audioStartupTask = null;
                        controller = startedController;
                        audioSettingsDirty = true;
                        published = true;
                    }
                }
                if (!accepted) {
                    return;
                }
                LOGGER.info("PocketTune speaker playback started at {}", getBlockPos());
            } catch (ExternalProcessException exception) {
                PlaybackStartupGate.FailureResult failure = PlaybackStartupGate.FailureResult.STALE;
                synchronized (audioOwnershipLock) {
                    if (!audioDisposed
                            && generation == audioGeneration
                            && sessionEpoch == MpvProcessRegistry.currentSessionEpoch()) {
                        failure = playbackStartup.completeFailure(videoId, sequence, startupToken);
                        if (failure != PlaybackStartupGate.FailureResult.STALE) {
                            startupError = failure == PlaybackStartupGate.FailureResult.TERMINAL
                                    ? PlaybackFailureMessages.forPlayback(exception)
                                    : "Playback could not be prepared; retrying automatically.";
                        }
                    }
                }
                if (failure == PlaybackStartupGate.FailureResult.TERMINAL) {
                    LOGGER.warn(
                            "PocketTune speaker playback attempt failed at {} ({}): {}",
                            getBlockPos(),
                            failure,
                            PlaybackFailureMessages.safeLogSummary(exception)
                    );
                    if (PlaybackFailureMessages.category(exception)
                            == PlaybackFailureMessages.FailureCategory.MEDIA_UNAVAILABLE) {
                        reportTrackResult(
                                index,
                                sequence,
                                videoId,
                                SpeakerTrackResultPayload.Result.UNAVAILABLE
                        );
                    }
                }
            } finally {
                if (!published) {
                    synchronized (audioOwnershipLock) {
                        if (audioStartupLease == requestLease) {
                            audioStartupLease = null;
                            audioStartupCancellation = null;
                            audioStartupTask = null;
                        }
                    }
                    requestCancellation.requestCancellation();
                    AudioCleanupCoordinator.cancelStartupLease(requestLease);
                    AudioCleanupCoordinator.terminate(startedController);
                }
            }
        };
        BoundedStartupExecutor.TaskHandle submittedTask = STARTUP_EXECUTOR.submit(
                startupWork,
                () -> rejectStartupAttempt(
                        videoId,
                        sequence,
                        startupToken,
                        generation,
                        sessionEpoch,
                        requestLease,
                        requestCancellation
                )
        );
        if (submittedTask != null) {
            boolean stillOwned;
            synchronized (audioOwnershipLock) {
                stillOwned = generation == audioGeneration
                        && audioStartupLease == requestLease
                        && audioStartupCancellation == requestCancellation;
                if (stillOwned) {
                    audioStartupTask = submittedTask;
                }
            }
            if (!stillOwned) {
                submittedTask.cancelAndRemove();
            }
        }
    }

    private void rejectStartupAttempt(
            String videoId,
            long sequence,
            long startupToken,
            long generation,
            long sessionEpoch,
            CancellableStartupLease<MpvController> requestLease,
            ExternalProcessCancellation requestCancellation
    ) {
        ExternalProcessException rejection = new ExternalProcessException(
                "The playback startup queue is full; the request will be retried."
        );
        PlaybackStartupGate.FailureResult failure = PlaybackStartupGate.FailureResult.STALE;
        synchronized (audioOwnershipLock) {
            if (!audioDisposed
                    && generation == audioGeneration
                    && sessionEpoch == MpvProcessRegistry.currentSessionEpoch()
                    && audioStartupLease == requestLease
                    && audioStartupCancellation == requestCancellation) {
                audioStartupLease = null;
                audioStartupCancellation = null;
                audioStartupTask = null;
                failure = playbackStartup.completeFailure(videoId, sequence, startupToken);
                if (failure != PlaybackStartupGate.FailureResult.STALE) {
                    startupError = failure == PlaybackStartupGate.FailureResult.TERMINAL
                            ? PlaybackFailureMessages.forPlayback(rejection)
                            : "The playback queue is busy; retrying automatically.";
                }
            }
        }
        requestCancellation.requestCancellation();
        AudioCleanupCoordinator.cancelStartupLease(requestLease);
        if (failure == PlaybackStartupGate.FailureResult.TERMINAL) {
            LOGGER.warn("PocketTune speaker startup queue remained saturated at {}.", getBlockPos());
        }
    }

    private void scheduleTrackStatus(
            MpvController activeController,
            int index,
            long sequence,
            String videoId
    ) {
        if (trackStatusInFlight || trackResultReported) {
            return;
        }
        trackStatusInFlight = true;
        CONTROL_EXECUTOR.execute(() -> {
            try {
                if (controller != activeController || !activeController.isAlive()) {
                    return;
                }
                double duration = activeController.getDurationSeconds();
                double elapsed = activeController.getTimeSeconds();
                if (duration > 0.0D && activeController.isEofReached()) {
                    reportTrackResult(
                            index,
                            sequence,
                            videoId,
                            SpeakerTrackResultPayload.Result.FINISHED
                    );
                }
            } catch (ExternalProcessException exception) {
                if (activeController.isAlive()) {
                    LOGGER.debug(
                            "PocketTune track status check failed at {}: {}",
                            getBlockPos(),
                            PlaybackFailureMessages.safeLogSummary(exception)
                    );
                }
            } finally {
                trackStatusInFlight = false;
            }
        });
    }

    private void reportTrackResult(
            int index,
            long sequence,
            String videoId,
            SpeakerTrackResultPayload.Result result
    ) {
        if (trackResultReported) {
            return;
        }
        trackResultReported = true;
        PacketDistributor.sendToServer(new SpeakerTrackResultPayload(
                worldPosition,
                speakerInstanceId,
                index,
                sequence,
                videoId,
                result
        ));
    }

    private void stopAudioForState() {
        MpvController previousController;
        StartupCancellation cancelledStartup;
        synchronized (audioOwnershipLock) {
            if (requestedTrackId.isEmpty()
                    && controller == null
                    && audioStartupLease == null
                    && audioStartupCancellation == null
                    && audioStartupTask == null
                    && !playbackStartup.inFlight()) {
                return;
            }
            requestedTrackId = "";
            requestedTrackSequence = -1L;
            startupError = "";
            feedbackShown = false;
            driftSyncRequested = false;
            trackResultReported = false;
            playbackStartup.cancel();
            cancelledStartup = cancelAudioStartupLocked();
            ++audioGeneration;
            previousController = controller;
            controller = null;
        }
        cancelStartup(cancelledStartup);
        closeAsync(previousController);
    }

    private void announceStartupResult(Level level, BlockPos pos) {
        if (feedbackShown
                || playbackStartup.inFlight()
                || playbackStartup.retryPending()
                || (controller == null && startupError.isBlank())) {
            return;
        }
        Player player = localPlayer(level);
        if (player == null) {
            return;
        }

        ClientSpeakerFeedback.handle(
                new SpeakerOperationFeedbackPayload(
                        pos,
                        startupError.isBlank()
                                ? SpeakerOperationFeedbackPayload.State.SUCCESS
                                : SpeakerOperationFeedbackPayload.State.ERROR,
                        startupError.isBlank()
                                ? "Speaker playback started."
                                : "Speaker error: " + startupError
                ),
                player
        );
        feedbackShown = true;
    }

    private void scheduleVolumeUpdate(Level level, BlockPos pos, MpvController activeController) {
        if (volumeUpdateInFlight) {
            return;
        }
        Player player = localPlayer(level);
        double distance = player == null ? Double.POSITIVE_INFINITY : Math.sqrt(player.distanceToSqr(
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D
        ));
        if (player != null && distance < settings.rangeBlocks()) {
            // Duvar arkasına girip çıkarken ses sert kesilmek yerine kademeli olarak yumuşar.
            double occlusionTarget = SpatialAudioMath.sampleOcclusion(
                    level,
                    pos,
                    player,
                    settings,
                    PocketTuneClientConfig.OCCLUSION_MAX_REDUCTION_PERCENT.get() / 100.0D
            ).multiplier();
            double smoothing = PocketTuneClientConfig.OCCLUSION_SMOOTHING_PERCENT.get() / 100.0D;
            occlusionSmoothed += (occlusionTarget - occlusionSmoothed) * smoothing;
        }
        final double targetVolume = player == null
                ? 0.0D
                : SpatialAudioMath.volumeForDistance(
                        distance,
                        settings,
                        PocketTuneClientConfig.FULL_VOLUME_DISTANCE.get()
                ) * occlusionSmoothed;
        if (Math.abs(targetVolume - lastAppliedVolume) < 0.5D) {
            return;
        }

        volumeUpdateInFlight = true;
        CONTROL_EXECUTOR.execute(() -> {
            try {
                if (controller == activeController && activeController.isAlive()) {
                    activeController.setVolume(targetVolume);
                    lastAppliedVolume = targetVolume;
                }
            } catch (ExternalProcessException exception) {
                startupError = PlaybackFailureMessages.forPlayback(exception);
                feedbackShown = false;
                LOGGER.warn(
                        "PocketTune volume update failed at {}: {}",
                        getBlockPos(),
                        PlaybackFailureMessages.safeLogSummary(exception)
                );
                detachAndClose(activeController);
            } finally {
                volumeUpdateInFlight = false;
            }
        });
    }

    private void scheduleAudioSettings(MpvController activeController) {
        audioSettingsDirty = false;
        audioSettingsInFlight = true;
        SpeakerSettings snapshot = settings;
        boolean pauseSnapshot = paused;
        CONTROL_EXECUTOR.execute(() -> {
            try {
                if (controller == activeController && activeController.isAlive()) {
                    MpvProcessRegistry.applyPlaybackPauseNow(activeController, pauseSnapshot);
                    activeController.setEqualizer(snapshot.bassDb(), snapshot.midDb(), snapshot.trebleDb());
                    lastAppliedVolume = -1.0D;
                }
            } catch (ExternalProcessException exception) {
                audioSettingsDirty = true;
                audioSettingsRetryCountdown = STATE_SYNC_INTERVAL_TICKS;
                LOGGER.debug(
                        "PocketTune audio settings update failed at {}: {}",
                        getBlockPos(),
                        PlaybackFailureMessages.safeLogSummary(exception)
                );
            } finally {
                audioSettingsInFlight = false;
            }
        });
    }

    private void scheduleDriftCorrection(MpvController activeController, long targetMillis) {
        driftSyncRequested = false;
        driftSyncInFlight = true;
        CONTROL_EXECUTOR.execute(() -> {
            try {
                if (controller != activeController || !activeController.isAlive()) {
                    return;
                }
                double target = Math.max(0.0D, targetMillis / 1_000.0D);
                double current = activeController.getTimeSeconds();
                if (target - current > DRIFT_CORRECTION_THRESHOLD_SECONDS) {
                    activeController.seek(target);
                }
            } catch (ExternalProcessException exception) {
                LOGGER.debug(
                        "PocketTune drift correction failed at {}: {}",
                        getBlockPos(),
                        PlaybackFailureMessages.safeLogSummary(exception)
                );
            } finally {
                driftSyncInFlight = false;
            }
        });
    }

    private static Player localPlayer(Level level) {
        for (Player player : level.players()) {
            if (player.isLocalPlayer()) {
                return player;
            }
        }
        return null;
    }

    private void detachAndClose(MpvController activeController) {
        synchronized (audioOwnershipLock) {
            if (controller == activeController) {
                controller = null;
            }
        }
        closeAsync(activeController);
    }

    private static void closeAsync(MpvController activeController) {
        AudioCleanupCoordinator.terminate(activeController);
    }

    private StartupCancellation cancelAudioStartupLocked() {
        CancellableStartupLease<MpvController> lease = audioStartupLease;
        ExternalProcessCancellation cancellation = audioStartupCancellation;
        BoundedStartupExecutor.TaskHandle task = audioStartupTask;
        if (lease == null && cancellation == null && task == null) {
            return null;
        }
        audioStartupLease = null;
        audioStartupCancellation = null;
        audioStartupTask = null;
        return new StartupCancellation(lease, cancellation, task);
    }

    private static void cancelStartup(StartupCancellation startup) {
        if (startup == null) {
            return;
        }
        if (startup.task() != null) {
            startup.task().cancelAndRemove();
        }
        if (startup.cancellation() != null) {
            startup.cancellation().requestCancellation();
        }
        if (startup.lease() != null) {
            startup.lease().requestCancellation();
            AudioCleanupCoordinator.cancelStartupLease(startup.lease());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        speakerInstanceId = SpeakerInstanceIdentity.restoreOrCreate(
                tag.hasUUID(INSTANCE_ID_TAG) ? tag.getUUID(INSTANCE_ID_TAG) : null
        );
        currentUrl = YtDlpResolver.sanitizeStoredYoutubeUrl(tag.getString(URL_TAG));
        Tag rawQueue = tag.get(PLAYLIST_QUEUE_TAG);
        ListTag queueTag = rawQueue instanceof ListTag list ? list : new ListTag();
        List<TrackMetadata> loadedQueue = new java.util.ArrayList<>(queueTag.size());
        for (int index = 0; index < queueTag.size() && index < YtDlpResolver.MAX_PLAYLIST_ENTRIES; index++) {
            Tag entry = queueTag.get(index);
            if (entry instanceof CompoundTag trackTag) {
                String videoId = trackTag.getString(VIDEO_ID_TAG);
                if (YtDlpResolver.isValidVideoId(videoId)) {
                    loadedQueue.add(new TrackMetadata(
                            videoId,
                            trackTag.getString(TITLE_TAG),
                            trackTag.getString(ARTIST_TAG),
                            trackTag.getLong(DURATION_TAG)
                    ));
                }
            } else if (entry.getId() == Tag.TAG_STRING) {
                String videoId = entry.getAsString();
                if (YtDlpResolver.isValidVideoId(videoId)) {
                    loadedQueue.add(TrackMetadata.fallback(videoId));
                }
            }
        }
        if (loadedQueue.isEmpty() && !currentUrl.isBlank()) {
            YtDlpResolver.extractVideoId(currentUrl).map(TrackMetadata::fallback).ifPresent(loadedQueue::add);
        }
        playlistQueue = List.copyOf(loadedQueue);
        playlistIndex = playlistQueue.isEmpty()
                ? 0
                : Math.floorMod(tag.getInt(PLAYLIST_INDEX_TAG), playlistQueue.size());
        trackSequence = Math.max(0L, tag.getLong(TRACK_SEQUENCE_TAG));
        elapsedMillis = Math.max(0L, tag.getLong(ELAPSED_MILLIS_TAG));
        playing = tag.getBoolean(PLAYING_TAG) && !currentUrl.isBlank() && !playlistQueue.isEmpty();
        paused = tag.getBoolean(PAUSED_TAG) && playing;
        SpeakerSettings defaults = PocketTuneServerConfig.defaultSpeakerSettings();
        settings = PocketTuneServerConfig.constrain(new SpeakerSettings(
                tag.contains(VOLUME_TAG) ? tag.getDouble(VOLUME_TAG) : defaults.volumePercent(),
                tag.contains(RANGE_TAG) ? tag.getInt(RANGE_TAG) : defaults.rangeBlocks(),
                enumOrDefault(SpeakerSettings.FadeType.values(), tag.getInt(FADE_TAG), defaults.fadeType()),
                tag.getBoolean(OCCLUSION_TAG),
                tag.contains(BASS_TAG) ? tag.getDouble(BASS_TAG) : defaults.bassDb(),
                tag.contains(MID_TAG) ? tag.getDouble(MID_TAG) : defaults.midDb(),
                tag.contains(TREBLE_TAG) ? tag.getDouble(TREBLE_TAG) : defaults.trebleDb(),
                tag.getBoolean(SHUFFLE_TAG),
                enumOrDefault(SpeakerSettings.RepeatMode.values(), tag.getInt(REPEAT_TAG), defaults.repeatMode())
        ));
        portableSpeakerId = tag.hasUUID(PORTABLE_ID_TAG)
                ? tag.getUUID(PORTABLE_ID_TAG)
                : PortableSpeakerState.UNASSIGNED_ID;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(URL_TAG, currentUrl);
        ListTag queueTag = new ListTag();
        for (TrackMetadata track : playlistQueue) {
            CompoundTag trackTag = new CompoundTag();
            trackTag.putString(VIDEO_ID_TAG, track.videoId());
            trackTag.putString(TITLE_TAG, track.title());
            trackTag.putString(ARTIST_TAG, track.artist());
            trackTag.putLong(DURATION_TAG, track.durationMillis());
            queueTag.add(trackTag);
        }
        tag.put(PLAYLIST_QUEUE_TAG, queueTag);
        tag.putInt(PLAYLIST_INDEX_TAG, playlistIndex);
        tag.putLong(TRACK_SEQUENCE_TAG, trackSequence);
        tag.putLong(ELAPSED_MILLIS_TAG, elapsedMillis);
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
        tag.putUUID(PORTABLE_ID_TAG, portableSpeakerId);
        tag.putUUID(INSTANCE_ID_TAG, speakerInstanceId);
    }

    private static <T> T enumOrDefault(T[] values, int ordinal, T fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        synchronized (audioOwnershipLock) {
            audioDisposed = false;
        }
        if (level != null && level.isClientSide()) {
            BlockInteractionDebugTracker.recordLifecycle(
                    worldPosition,
                    BlockInteractionTracePayload.Stage.CLIENT_BLOCK_ENTITY_LOADED,
                    "speakerId=" + portableSpeakerId
            );
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide()) {
            BlockInteractionDebugTracker.recordLifecycle(
                    worldPosition,
                    BlockInteractionTracePayload.Stage.CLIENT_BLOCK_ENTITY_REMOVED,
                    "speakerId=" + portableSpeakerId + ", controller=" + (controller != null)
            );
        }
        super.setRemoved();
        MpvController activeController;
        StartupCancellation cancelledStartup;
        synchronized (audioOwnershipLock) {
            audioDisposed = true;
            playbackStartup.cancel();
            cancelledStartup = cancelAudioStartupLocked();
            ++audioGeneration;
            invalidatePlaylistResolution();
            unavailableVerificationSequence = -1L;
            unavailableVerificationOperationId = 0L;
            activeController = controller;
            controller = null;
        }
        cancelStartup(cancelledStartup);
        AudioCleanupCoordinator.terminate(activeController);
    }

    private record StartupCancellation(
            CancellableStartupLease<MpvController> lease,
            ExternalProcessCancellation cancellation,
            BoundedStartupExecutor.TaskHandle task
    ) {
    }

    public enum TrackResultDecision {
        REJECTED,
        ADVANCED,
        SKIPPED_UNAVAILABLE,
        STOPPED_ALL_UNAVAILABLE
    }
}
