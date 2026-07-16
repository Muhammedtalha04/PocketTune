package com.pockettune.client.audio;

import com.mojang.logging.LogUtils;
import com.pockettune.PocketTune;
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
import com.pockettune.block.entity.SpeakerBlockEntity;
import com.pockettune.client.debug.BlockInteractionDebugTracker;
import com.pockettune.client.ClientSpeakerFeedback;
import com.pockettune.model.PortableSpeakerState;
import com.pockettune.model.SpeakerSettings;
import com.pockettune.model.TrackMetadata;
import com.pockettune.network.payload.BlockInteractionTracePayload;
import com.pockettune.registry.ModDataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@EventBusSubscriber(modid = PocketTune.MOD_ID, value = Dist.CLIENT)
public final class PortableSpeakerPlaybackManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TRANSFER_GRACE_TICKS = 40;
    private static final Path RUNTIME_DIRECTORY = FMLPaths.GAMEDIR.get().resolve("pockettune-runtime");
    private static final BoundedStartupExecutor STARTUP_EXECUTOR =
            new BoundedStartupExecutor(2, 16, "PocketTune portable startup");
    private static final ExecutorService CONTROL_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PocketTune portable control");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private PortableSpeakerPlaybackManager() {
    }

    public static void adoptFromBlock(
            PortableSpeakerState state,
            MpvController controller,
            String trackId,
            long trackSequence,
            UUID requestId
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        long gameTime = minecraft.level == null ? state.serverGameTime() : minecraft.level.getGameTime();
        Session previous = SESSIONS.remove(state.speakerId());
        if (previous != null) {
            previous.close();
        }
        Session session = new Session(state, gameTime);
        session.adopt(controller, trackId, trackSequence);
        session.beginPickup(requestId);
        SESSIONS.put(state.speakerId(), session);
    }

    public static void completePickup(
            BlockPos sourcePos,
            UUID speakerId,
            UUID requestId,
            boolean success
    ) {
        Session session = SESSIONS.get(speakerId);
        if (session == null || !session.completePickup(requestId, success)) {
            BlockInteractionDebugTracker.recordLocal(
                    requestId,
                    sourcePos,
                    BlockInteractionTracePayload.Stage.CLIENT_ROLLBACK,
                    false,
                    "Eşleşen taşınabilir oturum bulunamadı"
            );
            return;
        }
        if (success) {
            return;
        }
        BlockInteractionDebugTracker.recordLocal(
                requestId,
                sourcePos,
                BlockInteractionTracePayload.Stage.CLIENT_ROLLBACK,
                true,
                "Controller mevcut bloğa geri bağlanıyor"
        );
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null
                && minecraft.level.getBlockEntity(sourcePos) instanceof SpeakerBlockEntity speaker) {
            tryAttachToBlock(speaker);
        }
    }

    public static AttachmentState tryAttachToBlock(SpeakerBlockEntity speaker) {
        UUID speakerId = speaker.getPortableSpeakerId();
        Session session = SESSIONS.get(speakerId);
        if (session == null) {
            return AttachmentState.NO_SESSION;
        }
        if (!session.canAttachToBlock()) {
            session.reportBlockedAttach(speaker.getBlockPos());
            return AttachmentState.TRANSFER_PENDING;
        }
        TransferredController transferred = session.detachForBlock();
        boolean controllerAccepted = true;
        if (transferred.controller() != null) {
            controllerAccepted = speaker.acceptPortableController(
                    speakerId,
                    transferred.controller(),
                    transferred.trackId(),
                    transferred.trackSequence()
            );
            if (!controllerAccepted) {
                closeAsync(transferred.controller());
            }
        }
        BlockInteractionDebugTracker.recordLocal(
                session.pickupRequestId,
                speaker.getBlockPos(),
                BlockInteractionTracePayload.Stage.CLIENT_CONTROLLER_ATTACHED,
                transferred.controller() == null || controllerAccepted,
                transferred.controller() == null
                        ? "Canlı controller yok; blok gerektiğinde yeniden başlatacak"
                        : controllerAccepted
                                ? "Canlı mpv controller yeni BlockEntity'ye aktarıldı"
                                : "Controller devri reddedildi; blok güvenli biçimde yeniden başlatacak"
        );
        SESSIONS.remove(speakerId);
        return AttachmentState.ATTACHED;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            clear();
            return;
        }
        long gameTime = minecraft.level.getGameTime();
        Set<UUID> observed = new HashSet<>();
        for (int slot = 0; slot < minecraft.player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = minecraft.player.getInventory().getItem(slot);
            PortableSpeakerState state = stack.get(ModDataComponents.PORTABLE_SPEAKER_STATE.get());
            if (state == null || PortableSpeakerState.UNASSIGNED_ID.equals(state.speakerId())) {
                continue;
            }
            observed.add(state.speakerId());
            Session session = SESSIONS.computeIfAbsent(state.speakerId(), ignored -> new Session(state, gameTime));
            session.update(state, gameTime);
        }

        for (Map.Entry<UUID, Session> entry : SESSIONS.entrySet()) {
            entry.getValue().observeItem(observed.contains(entry.getKey()));
        }

        for (var iterator = SESSIONS.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<UUID, Session> entry = iterator.next();
            Session session = entry.getValue();
            if (!observed.contains(entry.getKey()) && gameTime - session.lastObservedGameTime > TRANSFER_GRACE_TICKS) {
                session.close();
                iterator.remove();
            }
        }
    }

    public static Optional<OverlaySnapshot> currentOverlaySnapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || SESSIONS.isEmpty()) {
            return Optional.empty();
        }
        PortableSpeakerState selectedState = minecraft.player.getMainHandItem()
                .get(ModDataComponents.PORTABLE_SPEAKER_STATE.get());
        Session session = selectedState == null ? null : SESSIONS.get(selectedState.speakerId());
        if (session == null || !session.state.playing()) {
            session = SESSIONS.values().stream()
                    .filter(value -> value.state.playing() && value.state.activeTrack() != null)
                    .min(Comparator.comparing(value -> value.state.speakerId().toString()))
                    .orElse(null);
        }
        return session == null
                ? Optional.empty()
                : Optional.ofNullable(session.overlaySnapshot(minecraft.level.getGameTime()));
    }

    public static void clear() {
        for (Session session : SESSIONS.values()) {
            session.close();
        }
        SESSIONS.clear();
    }

    private static final class Session {
        private volatile PortableSpeakerState state;
        private volatile MpvController controller;
        private CancellableStartupLease<MpvController> startupLease;
        private ExternalProcessCancellation startupCancellation;
        private BoundedStartupExecutor.TaskHandle startupTask;
        private String requestedTrackId = "";
        private long requestedTrackSequence = -1L;
        private volatile long generation;
        private volatile long lastObservedGameTime;
        private volatile SpeakerSettings appliedSettings;
        private volatile boolean appliedPaused;
        private volatile String startupError = "";
        private final Object ownershipLock = new Object();
        private final PlaybackStartupGate playbackStartup = new PlaybackStartupGate();
        private final TerminalErrorAnnouncementGate terminalErrorAnnouncements =
                new TerminalErrorAnnouncementGate();
        private final PortablePickupTransferGate transferGate = new PortablePickupTransferGate();
        private UUID pickupRequestId;
        private boolean attachBlockedReported;

        private Session(PortableSpeakerState state, long gameTime) {
            this.state = state;
            this.lastObservedGameTime = gameTime;
        }

        private void adopt(MpvController adopted, String trackId, long sequence) {
            MpvController previous;
            StartupCancellation cancelledStartup;
            boolean adoptedReady;
            synchronized (ownershipLock) {
                cancelledStartup = cancelStartupLocked();
                generation++;
                previous = controller;
                controller = adopted != null && adopted.isAlive() ? adopted : null;
                adoptedReady = controller != null;
                requestedTrackId = adoptedReady ? trackId : "";
                requestedTrackSequence = adoptedReady ? sequence : -1L;
                appliedSettings = null;
                startupError = "";
                if (adoptedReady) {
                    playbackStartup.adoptReady(trackId, sequence);
                } else {
                    playbackStartup.cancel();
                }
            }
            cancelStartup(cancelledStartup);
            if (previous != null && previous != controller) {
                closeAsync(previous);
            }
            if (adoptedReady) {
                applySettings();
            }
        }

        private void beginPickup(UUID requestId) {
            pickupRequestId = requestId;
            attachBlockedReported = false;
            transferGate.begin(requestId);
        }

        private boolean completePickup(UUID requestId, boolean success) {
            return transferGate.complete(requestId, success);
        }

        private void observeItem(boolean present) {
            transferGate.observeItem(present);
        }

        private boolean canAttachToBlock() {
            return transferGate.canAttachToBlock();
        }

        private void reportBlockedAttach(BlockPos attemptedPos) {
            if (attachBlockedReported) {
                return;
            }
            attachBlockedReported = true;
            BlockInteractionDebugTracker.recordLocal(
                    pickupRequestId,
                    attemptedPos,
                    BlockInteractionTracePayload.Stage.CLIENT_ATTACH_BLOCKED,
                    true,
                    transferGate.pending()
                            ? "Pickup sonucu beklenirken kaynak BE geri bağlanamadı"
                            : "Item hâlâ envanterde veya commit henüz gözlenmedi"
            );
        }

        private void update(PortableSpeakerState nextState, long gameTime) {
            state = nextState;
            lastObservedGameTime = gameTime;
            TrackMetadata active = state.activeTrack();
            if (!state.playing() || active == null) {
                stopController();
                return;
            }
            boolean controllerReady;
            synchronized (ownershipLock) {
                MpvController activeController = controller;
                controllerReady = activeController != null
                        && activeController.isAlive()
                        && active.videoId().equals(requestedTrackId)
                        && state.trackSequence() == requestedTrackSequence;
                PlaybackStartupGate.Decision decision =
                        playbackStartup.tick(active.videoId(), state.trackSequence(), controllerReady);
                if (decision.shouldStart()) {
                    restart(
                            active.videoId(),
                            state.trackSequence(),
                            state.elapsedAt(gameTime),
                            decision.token()
                    );
                    return;
                }
            }
            if (controllerReady) {
                applySettings();
            }
        }

        private void restart(String videoId, long sequence, long seekMillis, long startupToken) {
            long requestGeneration;
            long requestSessionEpoch;
            boolean pausedSnapshot;
            MpvController previous;
            StartupCancellation cancelledStartup;
            CancellableStartupLease<MpvController> requestLease = new CancellableStartupLease<>();
            ExternalProcessCancellation requestCancellation = new ExternalProcessCancellation();
            synchronized (ownershipLock) {
                requestedTrackId = videoId;
                requestedTrackSequence = sequence;
                startupError = "";
                cancelledStartup = cancelStartupLocked();
                startupLease = requestLease;
                startupCancellation = requestCancellation;
                startupTask = null;
                requestGeneration = ++generation;
                requestSessionEpoch = MpvProcessRegistry.currentSessionEpoch();
                pausedSnapshot = state.paused();
                previous = controller;
                controller = null;
                appliedSettings = null;
            }
            cancelStartup(cancelledStartup);
            closeAsync(previous);
            Runnable startupWork = () -> {
                MpvController started = null;
                boolean published = false;
                try {
                    synchronized (ownershipLock) {
                        if (!PlaybackStartupOwnership.isCurrent(
                                requestGeneration,
                                generation,
                                requestSessionEpoch,
                                MpvProcessRegistry.currentSessionEpoch(),
                                false
                        ) || startupLease != requestLease
                                || startupCancellation != requestCancellation
                                || requestCancellation.isCancelled()) {
                            return;
                        }
                    }
                    String streamUrl = new YtDlpResolver().resolveAudioStream(
                            YtDlpResolver.videoUrl(videoId),
                            requestCancellation
                    );
                    long synchronizedSeekMillis = Math.max(
                            seekMillis,
                            state.elapsedAt(lastObservedGameTime)
                    );
                    synchronized (ownershipLock) {
                        if (!PlaybackStartupOwnership.isCurrent(
                                requestGeneration,
                                generation,
                                requestSessionEpoch,
                                MpvProcessRegistry.currentSessionEpoch(),
                                false
                        ) || startupLease != requestLease
                                || startupCancellation != requestCancellation
                                || requestCancellation.isCancelled()) {
                            return;
                        }
                    }
                    started = MpvController.startPrepared(
                            streamUrl,
                            RUNTIME_DIRECTORY,
                            0.0D,
                            false,
                            true,
                            synchronizedSeekMillis / 1_000.0D,
                            pausedSnapshot,
                            requestSessionEpoch,
                            requestLease
                    );
                    synchronized (ownershipLock) {
                        if (requestGeneration != generation
                                || requestSessionEpoch != MpvProcessRegistry.currentSessionEpoch()
                                || startupLease != requestLease
                                || startupCancellation != requestCancellation) {
                            return;
                        }
                    }
                    long latestSeekMillis = Math.max(
                            synchronizedSeekMillis,
                            state.elapsedAt(lastObservedGameTime)
                    );
                    started.ensurePlaybackProgressAfterResume(
                            latestSeekMillis / 1_000.0D,
                            state.paused()
                    );
                    synchronized (ownershipLock) {
                        published = requestGeneration == generation
                                && requestSessionEpoch == MpvProcessRegistry.currentSessionEpoch()
                                && startupLease == requestLease
                                && startupCancellation == requestCancellation
                                && requestLease.publish(started)
                                && playbackStartup.completeSuccess(videoId, sequence, startupToken);
                        if (published) {
                            startupLease = null;
                            startupCancellation = null;
                            startupTask = null;
                            controller = started;
                            appliedSettings = null;
                            startupError = "";
                            terminalErrorAnnouncements.markHealthy(videoId, sequence);
                        }
                    }
                    if (!published) {
                        return;
                    }
                    applySettings();
                } catch (ExternalProcessException exception) {
                    PlaybackStartupGate.FailureResult failure;
                    synchronized (ownershipLock) {
                        if (requestGeneration != generation
                                || requestSessionEpoch != MpvProcessRegistry.currentSessionEpoch()) {
                            failure = PlaybackStartupGate.FailureResult.STALE;
                        } else {
                            failure = playbackStartup.completeFailure(
                                    videoId,
                                    sequence,
                                    startupToken
                            );
                            if (failure != PlaybackStartupGate.FailureResult.STALE) {
                                startupError = failure == PlaybackStartupGate.FailureResult.TERMINAL
                                        ? PlaybackFailureMessages.forPlayback(exception)
                                        : "Oynatma hazırlanamadı; otomatik yeniden deneniyor.";
                            }
                        }
                    }
                    if (failure == PlaybackStartupGate.FailureResult.TERMINAL) {
                        LOGGER.warn(
                                "PocketTune portable playback attempt failed ({}): {}",
                                failure,
                                PlaybackFailureMessages.safeLogSummary(exception)
                        );
                        if (terminalErrorAnnouncements.claim(videoId, sequence)) {
                            ClientSpeakerFeedback.showPortableTerminalError(
                                    PlaybackFailureMessages.forPlayback(exception)
                            );
                        }
                    }
                } finally {
                    if (!published) {
                        synchronized (ownershipLock) {
                            if (startupLease == requestLease) {
                                startupLease = null;
                                startupCancellation = null;
                                startupTask = null;
                            }
                        }
                        requestCancellation.requestCancellation();
                        AudioCleanupCoordinator.cancelStartupLease(requestLease);
                        AudioCleanupCoordinator.terminate(started);
                    }
                }
            };
            BoundedStartupExecutor.TaskHandle submittedTask = STARTUP_EXECUTOR.submit(
                    startupWork,
                    () -> rejectStartupAttempt(
                            videoId,
                            sequence,
                            startupToken,
                            requestGeneration,
                            requestSessionEpoch,
                            requestLease,
                            requestCancellation
                    )
            );
            if (submittedTask != null) {
                boolean stillOwned;
                synchronized (ownershipLock) {
                    stillOwned = requestGeneration == generation
                            && startupLease == requestLease
                            && startupCancellation == requestCancellation;
                    if (stillOwned) {
                        startupTask = submittedTask;
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
                long requestGeneration,
                long requestSessionEpoch,
                CancellableStartupLease<MpvController> requestLease,
                ExternalProcessCancellation requestCancellation
        ) {
            ExternalProcessException rejection = new ExternalProcessException(
                    "Oynatma başlatma kuyruğu dolu; istek yeniden denenecek."
            );
            PlaybackStartupGate.FailureResult failure = PlaybackStartupGate.FailureResult.STALE;
            synchronized (ownershipLock) {
                if (requestGeneration == generation
                        && requestSessionEpoch == MpvProcessRegistry.currentSessionEpoch()
                        && startupLease == requestLease
                        && startupCancellation == requestCancellation) {
                    startupLease = null;
                    startupCancellation = null;
                    startupTask = null;
                    failure = playbackStartup.completeFailure(videoId, sequence, startupToken);
                    if (failure != PlaybackStartupGate.FailureResult.STALE) {
                        startupError = failure == PlaybackStartupGate.FailureResult.TERMINAL
                                ? PlaybackFailureMessages.forPlayback(rejection)
                                : "Oynatma kuyruğu yoğun; otomatik yeniden deneniyor.";
                    }
                }
            }
            requestCancellation.requestCancellation();
            AudioCleanupCoordinator.cancelStartupLease(requestLease);
            if (failure == PlaybackStartupGate.FailureResult.TERMINAL
                    && terminalErrorAnnouncements.claim(videoId, sequence)) {
                ClientSpeakerFeedback.showPortableTerminalError(
                        PlaybackFailureMessages.forPlayback(rejection)
                );
            }
        }

        private void applySettings() {
            MpvController activeController;
            SpeakerSettings snapshot;
            boolean pauseSnapshot;
            long controlGeneration;
            synchronized (ownershipLock) {
                activeController = controller;
                if (activeController == null || !activeController.isAlive()) {
                    return;
                }
                snapshot = state.settings();
                pauseSnapshot = state.paused();
                if (snapshot.equals(appliedSettings) && pauseSnapshot == appliedPaused) {
                    return;
                }
                appliedSettings = snapshot;
                appliedPaused = pauseSnapshot;
                controlGeneration = generation;
            }
            CONTROL_EXECUTOR.execute(() -> {
                try {
                    if (ownsController(activeController, controlGeneration)) {
                        MpvProcessRegistry.applyPlaybackPauseNow(activeController, pauseSnapshot);
                        activeController.setEqualizer(snapshot.bassDb(), snapshot.midDb(), snapshot.trebleDb());
                        activeController.setVolume(snapshot.volumePercent());
                    }
                } catch (ExternalProcessException exception) {
                    boolean detached = false;
                    StartupCancellation cancelledStartup = null;
                    synchronized (ownershipLock) {
                        if (generation == controlGeneration && controller == activeController) {
                            startupError = PlaybackFailureMessages.forPlayback(exception);
                            cancelledStartup = cancelStartupLocked();
                            generation++;
                            playbackStartup.cancel();
                            controller = null;
                            appliedSettings = null;
                            detached = true;
                        }
                    }
                    cancelStartup(cancelledStartup);
                    if (detached) {
                        AudioCleanupCoordinator.terminate(activeController);
                    }
                }
            });
        }

        private boolean ownsController(MpvController candidate, long expectedGeneration) {
            synchronized (ownershipLock) {
                return generation == expectedGeneration
                        && controller == candidate
                        && candidate.isAlive();
            }
        }

        private TransferredController detachForBlock() {
            StartupCancellation cancelledStartup;
            TransferredController transferredController;
            synchronized (ownershipLock) {
                cancelledStartup = cancelStartupLocked();
                generation++;
                playbackStartup.cancel();
                MpvController transferred = controller;
                controller = null;
                appliedSettings = null;
                startupError = "";
                terminalErrorAnnouncements.clear();
                transferredController = new TransferredController(
                        transferred,
                        requestedTrackId,
                        requestedTrackSequence
                );
            }
            cancelStartup(cancelledStartup);
            return transferredController;
        }

        private void stopController() {
            MpvController previous;
            StartupCancellation cancelledStartup;
            synchronized (ownershipLock) {
                cancelledStartup = cancelStartupLocked();
                generation++;
                playbackStartup.cancel();
                requestedTrackId = "";
                requestedTrackSequence = -1L;
                previous = controller;
                controller = null;
                appliedSettings = null;
                startupError = "";
                terminalErrorAnnouncements.clear();
            }
            cancelStartup(cancelledStartup);
            closeAsync(previous);
        }

        private StartupCancellation cancelStartupLocked() {
            CancellableStartupLease<MpvController> lease = startupLease;
            ExternalProcessCancellation cancellation = startupCancellation;
            BoundedStartupExecutor.TaskHandle task = startupTask;
            if (lease == null && cancellation == null && task == null) {
                return null;
            }
            startupLease = null;
            startupCancellation = null;
            startupTask = null;
            return new StartupCancellation(lease, cancellation, task);
        }

        private void close() {
            stopController();
        }

        private OverlaySnapshot overlaySnapshot(long gameTime) {
            TrackMetadata active = state.activeTrack();
            if (active == null || !state.playing()) {
                return null;
            }
            MpvController activeController = controller;
            OverlayPlaybackState playbackState;
            if (playbackStartup.terminalFailure()) {
                playbackState = OverlayPlaybackState.ERROR;
            } else if (playbackStartup.inFlight()
                    || playbackStartup.retryPending()
                    || activeController == null
                    || !activeController.isAlive()) {
                playbackState = OverlayPlaybackState.BUFFERING;
            } else if (state.paused() || MpvProcessRegistry.isPauseRequested()) {
                playbackState = OverlayPlaybackState.PAUSED;
            } else {
                playbackState = OverlayPlaybackState.PLAYING;
            }
            return new OverlaySnapshot(
                    active,
                    state.elapsedAt(gameTime),
                    active.durationMillis(),
                    playbackState
            );
        }
    }

    private static void closeAsync(MpvController controller) {
        AudioCleanupCoordinator.terminate(controller);
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

    public enum AttachmentState {
        NO_SESSION,
        TRANSFER_PENDING,
        ATTACHED
    }

    public enum OverlayPlaybackState {
        PLAYING,
        PAUSED,
        BUFFERING,
        ERROR
    }

    public record OverlaySnapshot(
            TrackMetadata track,
            long elapsedMillis,
            long durationMillis,
            OverlayPlaybackState playbackState
    ) {
    }

    private record StartupCancellation(
            CancellableStartupLease<MpvController> lease,
            ExternalProcessCancellation cancellation,
            BoundedStartupExecutor.TaskHandle task
    ) {
    }

    private record TransferredController(
            MpvController controller,
            String trackId,
            long trackSequence
    ) {
    }
}
