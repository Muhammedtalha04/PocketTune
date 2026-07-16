package com.pockettune.service;

import com.mojang.logging.LogUtils;
import com.pockettune.audio.ExternalProcessCancellation;
import com.pockettune.audio.ExternalProcessException;
import com.pockettune.audio.PlaybackFailureMessages;
import com.pockettune.audio.YtDlpResolver;
import com.pockettune.block.entity.SpeakerBlockEntity;
import com.pockettune.model.TrackMetadata;
import com.pockettune.network.ModNetworking;
import com.pockettune.network.payload.SpeakerOperationFeedbackPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PlaylistResolutionService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RESOLVER_WORKERS = 2;
    private static final int RESOLVER_QUEUE_CAPACITY = 16;
    private static final int MAXIMUM_REQUESTS_PER_PLAYER = 4;
    private static final long PLAYER_RATE_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final long BUSY_FEEDBACK_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final String QUEUE_FULL_MESSAGE =
            "Sunucunun medya çözümleme kuyruğu dolu. Lütfen biraz sonra tekrar deneyin.";
    private static final String UNEXPECTED_FAILURE_MESSAGE =
            "Medya çözümleme işlemi beklenmeyen bir hatayla durdu. Lütfen tekrar deneyin.";

    private static final SessionRuntimeRegistry<MinecraftServer, ServerRuntime> RUNTIMES =
            new SessionRuntimeRegistry<>(ServerRuntime::new, ServerRuntime::shutdown);

    private PlaylistResolutionService() {
    }

    public static void resolveAndApply(ServerPlayer requester, SpeakerBlockEntity speaker, String sourceUrl) {
        resolve(requester, speaker, sourceUrl, false);
    }

    public static void resolveAndAppend(ServerPlayer requester, SpeakerBlockEntity speaker, String sourceUrl) {
        resolve(requester, speaker, sourceUrl, true);
    }

    public static void verifyReportedUnavailable(
            ServerPlayer requester,
            SpeakerBlockEntity speaker,
            int playlistIndex,
            long trackSequence,
            String videoId,
            long verificationOperationId
    ) {
        MinecraftServer server = requester.getServer();
        ServerRuntime runtime = runtimeFor(server);
        ResourceKey<Level> dimension = requester.serverLevel().dimension();
        BlockPos pos = speaker.getBlockPos().immutable();
        UUID requesterId = requester.getUUID();
        SpeakerResolutionKey key = new SpeakerResolutionKey(dimension, pos);
        ResolutionAdmissionPolicy.Ticket<SpeakerResolutionKey> ticket = null;
        boolean submitted = false;

        try {
            ticket = admit(runtime, requester, key, pos);
            if (ticket == null) {
                return;
            }

            ResolutionAdmissionPolicy.Ticket<SpeakerResolutionKey> admittedTicket = ticket;
            submitted = runtime.submit(
                    admittedTicket,
                    cancellation -> runUnavailableVerification(
                            runtime,
                            server,
                            dimension,
                            pos,
                            requesterId,
                            playlistIndex,
                            trackSequence,
                            videoId,
                            verificationOperationId,
                            admittedTicket,
                            cancellation
                    )
            );
            if (!submitted) {
                reportQueueRejection(runtime, requester, pos, "availability verification");
            }
        } finally {
            if (!submitted) {
                runtime.release(ticket);
                speaker.cancelUnavailableVerification(
                        playlistIndex,
                        trackSequence,
                        videoId,
                        verificationOperationId
                );
            }
        }
    }

    public static void shutdown(MinecraftServer server) {
        RUNTIMES.beginStopping(server);
    }

    /** Releases the stopped-server tombstone after NeoForge confirms the server is fully stopped. */
    public static void forgetStoppedServer(MinecraftServer server) {
        RUNTIMES.forget(server);
    }

    private static void resolve(
            ServerPlayer requester,
            SpeakerBlockEntity speaker,
            String sourceUrl,
            boolean append
    ) {
        MinecraftServer server = requester.getServer();
        ServerRuntime runtime = runtimeFor(server);
        ResourceKey<Level> dimension = requester.serverLevel().dimension();
        BlockPos pos = speaker.getBlockPos().immutable();
        UUID requesterId = requester.getUUID();
        SpeakerResolutionKey key = new SpeakerResolutionKey(dimension, pos);
        ResolutionAdmissionPolicy.Ticket<SpeakerResolutionKey> ticket = admit(runtime, requester, key, pos);
        if (ticket == null) {
            return;
        }

        long requestToken = speaker.beginPlaylistResolution();
        boolean submitted = false;
        try {
            ResolutionAdmissionPolicy.Ticket<SpeakerResolutionKey> admittedTicket = ticket;
            submitted = runtime.submit(
                    admittedTicket,
                    cancellation -> runPlaylistResolution(
                            runtime,
                            server,
                            dimension,
                            pos,
                            requesterId,
                            requestToken,
                            sourceUrl,
                            append,
                            admittedTicket,
                            cancellation
                    )
            );
            if (!submitted) {
                reportQueueRejection(
                        runtime,
                        requester,
                        pos,
                        append ? "playlist append" : "playlist replacement"
                );
            }
        } finally {
            if (!submitted) {
                runtime.release(ticket);
                speaker.cancelPlaylistResolution(requestToken);
            }
        }
    }

    private static void runPlaylistResolution(
            ServerRuntime runtime,
            MinecraftServer server,
            ResourceKey<Level> dimension,
            BlockPos pos,
            UUID requesterId,
            long requestToken,
            String sourceUrl,
            boolean append,
            ResolutionAdmissionPolicy.Ticket<SpeakerResolutionKey> ticket,
            ExternalProcessCancellation cancellation
    ) {
        Runnable completion;
        try {
            List<TrackMetadata> tracks = new YtDlpResolver().resolvePlaylistTracks(sourceUrl, cancellation);
            completion = () -> applyResolvedPlaylist(
                    server, dimension, pos, requesterId, requestToken, sourceUrl, tracks, append);
        } catch (ExternalProcessException exception) {
            completion = () -> reportResolutionFailure(
                    server,
                    dimension,
                    pos,
                    requesterId,
                    requestToken,
                    PlaybackFailureMessages.forPlaylistResolution(exception),
                    PlaybackFailureMessages.safeLogSummary(exception),
                    append
            );
        } catch (RuntimeException exception) {
            String logSummary = "unexpected " + exception.getClass().getSimpleName();
            completion = () -> reportResolutionFailure(
                    server,
                    dimension,
                    pos,
                    requesterId,
                    requestToken,
                    UNEXPECTED_FAILURE_MESSAGE,
                    logSummary,
                    append
            );
        }
        runtime.scheduleCompletion(server, ticket, completion);
    }

    private static void runUnavailableVerification(
            ServerRuntime runtime,
            MinecraftServer server,
            ResourceKey<Level> dimension,
            BlockPos pos,
            UUID requesterId,
            int playlistIndex,
            long trackSequence,
            String videoId,
            long verificationOperationId,
            ResolutionAdmissionPolicy.Ticket<SpeakerResolutionKey> ticket,
            ExternalProcessCancellation cancellation
    ) {
        Runnable completion;
        try {
            new YtDlpResolver().resolveAudioStream(YtDlpResolver.videoUrl(videoId), cancellation);
            completion = () -> completeUnavailableVerification(
                    server, dimension, pos, requesterId,
                    playlistIndex, trackSequence, videoId, verificationOperationId, false);
        } catch (ExternalProcessException exception) {
            boolean confirmedUnavailable = PlaybackFailureMessages.category(exception)
                    == PlaybackFailureMessages.FailureCategory.MEDIA_UNAVAILABLE;
            if (confirmedUnavailable) {
                completion = () -> completeUnavailableVerification(
                        server, dimension, pos, requesterId,
                        playlistIndex, trackSequence, videoId, verificationOperationId, true);
            } else {
                completion = () -> cancelUnavailableVerification(
                        server, dimension, pos, requesterId,
                        playlistIndex, trackSequence, videoId, verificationOperationId,
                        PlaybackFailureMessages.forPlaylistResolution(exception));
            }
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "PocketTune availability verification failed unexpectedly at {}: {}",
                    pos,
                    exception.getClass().getSimpleName()
            );
            completion = () -> cancelUnavailableVerification(
                    server, dimension, pos, requesterId,
                    playlistIndex, trackSequence, videoId, verificationOperationId,
                    UNEXPECTED_FAILURE_MESSAGE);
        }
        runtime.scheduleCompletion(server, ticket, completion);
    }

    private static ResolutionAdmissionPolicy.Ticket<SpeakerResolutionKey> admit(
            ServerRuntime runtime,
            ServerPlayer requester,
            SpeakerResolutionKey key,
            BlockPos pos
    ) {
        long nowNanos = System.nanoTime();
        ResolutionAdmissionPolicy.Decision<SpeakerResolutionKey> decision = runtime.tryAcquire(
                requester.getUUID(),
                key,
                nowNanos
        );
        if (decision.admitted()) {
            return decision.ticket();
        }

        String message;
        if (decision.rejectionReason() == ResolutionAdmissionPolicy.RejectionReason.SPEAKER_BUSY) {
            if (!runtime.shouldReportBusy(requester.getUUID(), nowNanos)) {
                return null;
            }
            message = "Bu hoparlör için başka bir medya çözümleme işlemi devam ediyor.";
        } else {
            long seconds = Math.max(
                    1L,
                    (decision.retryAfterNanos() + TimeUnit.SECONDS.toNanos(1L) - 1L)
                            / TimeUnit.SECONDS.toNanos(1L)
            );
            message = "Çok fazla medya çözümleme isteği gönderdiniz. "
                    + seconds + " saniye sonra tekrar deneyin.";
        }
        ModNetworking.sendOperationFeedback(
                requester,
                pos,
                SpeakerOperationFeedbackPayload.State.ERROR,
                message
        );
        return null;
    }

    private static void reportQueueRejection(
            ServerRuntime runtime,
            ServerPlayer requester,
            BlockPos pos,
            String operation
    ) {
        LOGGER.warn(
                "PocketTune resolver queue rejected {} at {} (queued={})",
                operation,
                pos,
                runtime.queuedTaskCount()
        );
        ModNetworking.sendOperationFeedback(
                requester,
                pos,
                SpeakerOperationFeedbackPayload.State.ERROR,
                QUEUE_FULL_MESSAGE
        );
    }

    private static void applyResolvedPlaylist(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            BlockPos pos,
            UUID requesterId,
            long requestToken,
            String sourceUrl,
            List<TrackMetadata> tracks,
            boolean append
    ) {
        SpeakerBlockEntity speaker = findLoadedSpeaker(server, dimension, pos);
        if (speaker == null || !(append
                ? speaker.completeAppendResolution(requestToken, sourceUrl, tracks)
                : speaker.completePlaylistResolution(requestToken, sourceUrl, tracks))) {
            return;
        }

        ServerPlayer requester = server.getPlayerList().getPlayer(requesterId);
        if (requester != null) {
            ModNetworking.sendOperationFeedback(
                    requester,
                    pos,
                    SpeakerOperationFeedbackPayload.State.SUCCESS,
                    tracks.size() + " parçalık sıra hazır; oynatma başladı."
            );
        }
    }

    private static void reportResolutionFailure(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            BlockPos pos,
            UUID requesterId,
            long requestToken,
            String error,
            String logSummary,
            boolean append
    ) {
        SpeakerBlockEntity speaker = findLoadedSpeaker(server, dimension, pos);
        if (speaker == null || !speaker.cancelPlaylistResolution(requestToken)) {
            return;
        }

        LOGGER.warn(
                "PocketTune playlist resolution failed at {} (append={}): {}",
                pos,
                append,
                logSummary
        );

        ServerPlayer requester = server.getPlayerList().getPlayer(requesterId);
        if (requester != null) {
            ModNetworking.sendOperationFeedback(
                    requester,
                    pos,
                    SpeakerOperationFeedbackPayload.State.ERROR,
                    error
            );
        }
    }

    private static void completeUnavailableVerification(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            BlockPos pos,
            UUID requesterId,
            int playlistIndex,
            long trackSequence,
            String videoId,
            long verificationOperationId,
            boolean confirmedUnavailable
    ) {
        SpeakerBlockEntity speaker = findLoadedSpeaker(server, dimension, pos);
        if (speaker == null) {
            return;
        }
        SpeakerBlockEntity.TrackResultDecision decision = speaker.completeUnavailableVerification(
                playlistIndex,
                trackSequence,
                videoId,
                verificationOperationId,
                confirmedUnavailable
        );
        ServerPlayer requester = server.getPlayerList().getPlayer(requesterId);
        if (requester == null) {
            return;
        }
        if (decision == SpeakerBlockEntity.TrackResultDecision.SKIPPED_UNAVAILABLE) {
            ModNetworking.sendOperationFeedback(requester, pos, SpeakerOperationFeedbackPayload.State.ERROR,
                    "Sunucu kullanılamayan parçayı doğruladı; sıradaki parça açılıyor.");
        } else if (decision == SpeakerBlockEntity.TrackResultDecision.STOPPED_ALL_UNAVAILABLE) {
            ModNetworking.sendOperationFeedback(requester, pos, SpeakerOperationFeedbackPayload.State.ERROR,
                    "Sunucu sıradaki hiçbir parçayı oynatamadığı için hoparlör durduruldu.");
        } else if (!confirmedUnavailable) {
            ModNetworking.sendOperationFeedback(requester, pos, SpeakerOperationFeedbackPayload.State.ERROR,
                    "Parça sunucuda kullanılabilir; yalnızca bu istemcide oynatma başarısız oldu.");
        }
    }

    private static void cancelUnavailableVerification(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            BlockPos pos,
            UUID requesterId,
            int playlistIndex,
            long trackSequence,
            String videoId,
            long verificationOperationId,
            String error
    ) {
        SpeakerBlockEntity speaker = findLoadedSpeaker(server, dimension, pos);
        if (speaker != null) {
            speaker.cancelUnavailableVerification(
                    playlistIndex,
                    trackSequence,
                    videoId,
                    verificationOperationId
            );
        }
        ServerPlayer requester = server.getPlayerList().getPlayer(requesterId);
        if (requester != null) {
            ModNetworking.sendOperationFeedback(
                    requester,
                    pos,
                    SpeakerOperationFeedbackPayload.State.ERROR,
                    "Parça doğrulanamadı; sıra değiştirilmedi. " + error
            );
        }
    }

    private static ServerRuntime runtimeFor(MinecraftServer server) {
        return RUNTIMES.runtimeFor(server);
    }

    private static SpeakerBlockEntity findLoadedSpeaker(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            BlockPos pos
    ) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker ? speaker : null;
    }

    private record SpeakerResolutionKey(ResourceKey<Level> dimension, BlockPos pos) {
    }

    @FunctionalInterface
    private interface ResolutionTask {
        void run(ExternalProcessCancellation cancellation);
    }

    private static final class ServerRuntime {
        private final BoundedResolutionExecutor executor = new BoundedResolutionExecutor(
                RESOLVER_WORKERS,
                RESOLVER_QUEUE_CAPACITY,
                "PocketTune playlist resolver"
        );
        private final ResolutionAdmissionPolicy<SpeakerResolutionKey, UUID> admission =
                new ResolutionAdmissionPolicy<>(MAXIMUM_REQUESTS_PER_PLAYER, PLAYER_RATE_WINDOW_NANOS);
        private final MonotonicCooldownGate<UUID> busyFeedback =
                new MonotonicCooldownGate<>(BUSY_FEEDBACK_COOLDOWN_NANOS);
        private final Set<ExternalProcessCancellation> activeCancellations =
                ConcurrentHashMap.newKeySet();
        private final AtomicBoolean stopped = new AtomicBoolean();

        private ResolutionAdmissionPolicy.Decision<SpeakerResolutionKey> tryAcquire(
                UUID requesterId,
                SpeakerResolutionKey key,
                long nowNanos
        ) {
            if (stopped.get()) {
                return ResolutionAdmissionPolicy.Decision.rejected(
                        ResolutionAdmissionPolicy.RejectionReason.SPEAKER_BUSY,
                        0L
                );
            }
            return admission.tryAcquire(requesterId, key, nowNanos);
        }

        private boolean shouldReportBusy(UUID requesterId, long nowNanos) {
            return busyFeedback.tryAcquire(requesterId, nowNanos);
        }

        private boolean submit(
                ResolutionAdmissionPolicy.Ticket<SpeakerResolutionKey> ticket,
                ResolutionTask task
        ) {
            if (stopped.get()) {
                return false;
            }
            return executor.submit(() -> {
                ExceptionalResourceGuard.releaseOnExceptionalExit(ticket, this::release, () -> {
                    ExternalProcessCancellation cancellation = registerCancellation();
                    if (cancellation == null) {
                        release(ticket);
                        return;
                    }
                    try {
                        task.run(cancellation);
                    } finally {
                        cancellation.onDrained(() -> activeCancellations.remove(cancellation));
                    }
                });
            }, () -> release(ticket));
        }

        private ExternalProcessCancellation registerCancellation() {
            ExternalProcessCancellation cancellation = new ExternalProcessCancellation();
            if (stopped.get()) {
                cancellation.cancel();
                return null;
            }
            activeCancellations.add(cancellation);
            if (stopped.get()) {
                activeCancellations.remove(cancellation);
                cancellation.cancel();
                return null;
            }
            return cancellation;
        }

        private void scheduleCompletion(
                MinecraftServer server,
                ResolutionAdmissionPolicy.Ticket<SpeakerResolutionKey> ticket,
                Runnable completion
        ) {
            if (stopped.get()) {
                release(ticket);
                return;
            }
            try {
                server.execute(() -> {
                    try {
                        if (!stopped.get()) {
                            completion.run();
                        }
                    } catch (RuntimeException exception) {
                        LOGGER.error(
                                "PocketTune resolution completion failed on the server thread: {}",
                                exception.getClass().getSimpleName()
                        );
                    } finally {
                        // The speaker reservation remains held until state commit and feedback finish.
                        release(ticket);
                    }
                });
            } catch (RuntimeException exception) {
                release(ticket);
                LOGGER.debug(
                        "PocketTune discarded a resolution result because the server executor stopped: {}",
                        exception.getClass().getSimpleName()
                );
            }
        }

        private void release(ResolutionAdmissionPolicy.Ticket<SpeakerResolutionKey> ticket) {
            admission.release(ticket);
        }

        private int queuedTaskCount() {
            return executor.queuedTaskCount();
        }

        private void shutdown() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            List<ExternalProcessCancellation> cancellations = List.copyOf(activeCancellations);
            activeCancellations.clear();
            cancellations.forEach(ExternalProcessCancellation::requestCancellation);
            executor.shutdownNow();
            admission.clear();
            busyFeedback.clear();
        }
    }
}
