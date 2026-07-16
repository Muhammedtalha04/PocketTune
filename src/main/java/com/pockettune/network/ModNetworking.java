package com.pockettune.network;

import com.mojang.logging.LogUtils;
import com.pockettune.audio.ExternalProcessException;
import com.pockettune.audio.PlaybackFailureMessages;
import com.pockettune.audio.YtDlpResolver;
import com.pockettune.block.entity.SpeakerBlockEntity;
import com.pockettune.network.payload.ChangeSpeakerTrackPayload;
import com.pockettune.network.payload.AddSpeakerUrlPayload;
import com.pockettune.network.payload.SetSpeakerUrlPayload;
import com.pockettune.network.payload.SpeakerControlPayload;
import com.pockettune.network.payload.SpeakerOperationFeedbackPayload;
import com.pockettune.network.payload.SpeakerQueueActionPayload;
import com.pockettune.network.payload.SpeakerStateSyncPayload;
import com.pockettune.network.payload.SpeakerTrackResultPayload;
import com.pockettune.network.payload.PreparePortableSpeakerPickupPayload;
import com.pockettune.network.payload.PickupPortableSpeakerPayload;
import com.pockettune.network.payload.PortableSpeakerPickupResultPayload;
import com.pockettune.network.payload.BlockInteractionTracePayload;
import com.pockettune.model.PortableSpeakerState;
import com.pockettune.registry.ModDataComponents;
import com.pockettune.registry.ModItems;
import com.pockettune.registry.ModBlocks;
import com.pockettune.service.PlaylistResolutionService;
import com.pockettune.util.SpeakerInstanceIdentity;
import com.pockettune.client.audio.PortableSpeakerPlaybackManager;
import com.pockettune.client.debug.BlockInteractionDebugTracker;
import com.pockettune.client.ClientSpeakerFeedback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ModNetworking {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NETWORK_VERSION = "8";
    private static final double MAX_EDIT_DISTANCE_SQUARED = 64.0D * 64.0D;
    private static final double MAX_REPORT_DISTANCE_SQUARED = 128.0D * 128.0D;
    private static final int PENDING_PICKUP_TIMEOUT_TICKS = 10;
    private static final long DEBUG_LOG_REQUEST_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final Map<UUID, PendingPickupRequest> PENDING_PICKUPS = new HashMap<>();
    private static final ServerDebugLogGate<UUID> DEBUG_LOG_GATE =
            new ServerDebugLogGate<>(DEBUG_LOG_REQUEST_COOLDOWN_NANOS);
    private static final Set<PickupDebugKey> APPROVED_PICKUP_LOGS = new HashSet<>();

    private ModNetworking() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);
        registrar.playToServer(
                SetSpeakerUrlPayload.TYPE,
                SetSpeakerUrlPayload.STREAM_CODEC,
                ModNetworking::handleSetSpeakerUrl
        );
        registrar.playToServer(
                ChangeSpeakerTrackPayload.TYPE,
                ChangeSpeakerTrackPayload.STREAM_CODEC,
                ModNetworking::handleChangeSpeakerTrack
        );
        registrar.playToServer(
                AddSpeakerUrlPayload.TYPE,
                AddSpeakerUrlPayload.STREAM_CODEC,
                ModNetworking::handleAddSpeakerUrl
        );
        registrar.playToServer(
                SpeakerControlPayload.TYPE,
                SpeakerControlPayload.STREAM_CODEC,
                ModNetworking::handleSpeakerControl
        );
        registrar.playToServer(
                SpeakerQueueActionPayload.TYPE,
                SpeakerQueueActionPayload.STREAM_CODEC,
                ModNetworking::handleSpeakerQueueAction
        );
        registrar.playToServer(
                SpeakerTrackResultPayload.TYPE,
                SpeakerTrackResultPayload.STREAM_CODEC,
                ModNetworking::handleSpeakerTrackResult
        );
        registrar.playToServer(
                PickupPortableSpeakerPayload.TYPE,
                PickupPortableSpeakerPayload.STREAM_CODEC,
                ModNetworking::handlePickupPortableSpeaker
        );
        registrar.playToClient(
                SpeakerStateSyncPayload.TYPE,
                SpeakerStateSyncPayload.STREAM_CODEC,
                ModNetworking::handleSpeakerStateSync
        );
        registrar.playToClient(
                SpeakerOperationFeedbackPayload.TYPE,
                SpeakerOperationFeedbackPayload.STREAM_CODEC,
                ModNetworking::handleSpeakerOperationFeedback
        );
        registrar.playToClient(
                PreparePortableSpeakerPickupPayload.TYPE,
                PreparePortableSpeakerPickupPayload.STREAM_CODEC,
                ModNetworking::handlePreparePortableSpeakerPickup
        );
        registrar.playToClient(
                PortableSpeakerPickupResultPayload.TYPE,
                PortableSpeakerPickupResultPayload.STREAM_CODEC,
                ModNetworking::handlePortableSpeakerPickupResult
        );
        registrar.playToClient(
                BlockInteractionTracePayload.TYPE,
                BlockInteractionTracePayload.STREAM_CODEC,
                ModNetworking::handleBlockInteractionTrace
        );
    }

    private static void handleSetSpeakerUrl(SetSpeakerUrlPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        SpeakerBlockEntity speaker = findEditableSpeaker(player, payload.pos(), payload.speakerInstanceId());
        if (speaker == null) {
            sendOperationFeedback(player, payload.pos(), SpeakerOperationFeedbackPayload.State.ERROR,
                    "Hoparlör artık erişilebilir değil.");
            return;
        }

        String url = payload.url().trim();
        if (url.isEmpty()) {
            speaker.stopFromServer();
            sendOperationFeedback(player, payload.pos(), SpeakerOperationFeedbackPayload.State.SUCCESS,
                    "Hoparlör durduruldu.");
            return;
        }

        try {
            url = YtDlpResolver.validateYoutubeUrl(url);
        } catch (ExternalProcessException exception) {
            sendOperationFeedback(player, payload.pos(), SpeakerOperationFeedbackPayload.State.ERROR,
                    PlaybackFailureMessages.forUrlInput(exception));
            return;
        }

        sendOperationFeedback(player, payload.pos(), SpeakerOperationFeedbackPayload.State.PENDING,
                "Şarkı veya playlist bilgileri alınıyor…");
        PlaylistResolutionService.resolveAndApply(player, speaker, url);
    }

    private static void handleChangeSpeakerTrack(ChangeSpeakerTrackPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        SpeakerBlockEntity speaker = findEditableSpeaker(player, payload.pos(), payload.speakerInstanceId());
        if (speaker == null || !speaker.changeTrackFromServer(payload.direction())) {
            sendOperationFeedback(player, payload.pos(), SpeakerOperationFeedbackPayload.State.ERROR,
                    "Parça değiştirilemedi.");
        }
    }

    private static void handleAddSpeakerUrl(AddSpeakerUrlPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        SpeakerBlockEntity speaker = findEditableSpeaker(player, payload.pos(), payload.speakerInstanceId());
        if (speaker == null) {
            sendOperationFeedback(player, payload.pos(), SpeakerOperationFeedbackPayload.State.ERROR,
                    "Hoparlör artık erişilebilir değil.");
            return;
        }
        String url;
        try {
            url = YtDlpResolver.validateYoutubeUrl(payload.url());
        } catch (ExternalProcessException exception) {
            sendOperationFeedback(player, payload.pos(), SpeakerOperationFeedbackPayload.State.ERROR,
                    PlaybackFailureMessages.forUrlInput(exception));
            return;
        }
        sendOperationFeedback(player, payload.pos(), SpeakerOperationFeedbackPayload.State.PENDING,
                "Yeni parçalar sıraya ekleniyor…");
        PlaylistResolutionService.resolveAndAppend(player, speaker, url);
    }

    private static void handleSpeakerControl(SpeakerControlPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        SpeakerBlockEntity speaker = findEditableSpeaker(player, payload.pos(), payload.speakerInstanceId());
        if (speaker != null) {
            speaker.applyControl(payload.action(), payload.value());
        }
    }

    private static void handleSpeakerQueueAction(SpeakerQueueActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        SpeakerBlockEntity speaker = findEditableSpeaker(player, payload.pos(), payload.speakerInstanceId());
        if (speaker != null && !speaker.applyQueueAction(payload.action(), payload.fromIndex(), payload.toIndex())) {
            sendOperationFeedback(player, payload.pos(), SpeakerOperationFeedbackPayload.State.ERROR,
                    "Playlist işlemi uygulanamadı.");
        }
    }

    private static void handleSpeakerTrackResult(SpeakerTrackResultPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        SpeakerBlockEntity speaker = findSpeakerWithin(player, payload.pos(), MAX_REPORT_DISTANCE_SQUARED);
        if (speaker == null || !SpeakerInstanceIdentity.matches(
                speaker.getSpeakerInstanceId(),
                payload.speakerInstanceId()
        )) {
            return;
        }

        if (payload.result() == SpeakerTrackResultPayload.Result.UNAVAILABLE) {
            long verificationOperationId = speaker.beginUnavailableVerification(
                    payload.playlistIndex(), payload.trackSequence(), payload.videoId());
            if (verificationOperationId != 0L) {
                PlaylistResolutionService.verifyReportedUnavailable(
                        player,
                        speaker,
                        payload.playlistIndex(),
                        payload.trackSequence(),
                        payload.videoId(),
                        verificationOperationId
                );
            }
            return;
        }

        SpeakerBlockEntity.TrackResultDecision decision = speaker.acceptTrackResult(
                payload.playlistIndex(),
                payload.trackSequence(),
                payload.videoId(),
                payload.result()
        );
        if (decision == SpeakerBlockEntity.TrackResultDecision.SKIPPED_UNAVAILABLE) {
            sendOperationFeedback(player, payload.pos(), SpeakerOperationFeedbackPayload.State.ERROR,
                    "Kullanılamayan parça atlandı; sıradaki parça açılıyor.");
        } else if (decision == SpeakerBlockEntity.TrackResultDecision.STOPPED_ALL_UNAVAILABLE) {
            sendOperationFeedback(player, payload.pos(), SpeakerOperationFeedbackPayload.State.ERROR,
                    "Sıradaki hiçbir parça oynatılamadığı için hoparlör durduruldu.");
        }
    }

    private static void handlePickupPortableSpeaker(
            PickupPortableSpeakerPayload payload,
            IPayloadContext context
    ) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        preparePickupDebugLog(player, payload);
        SpeakerBlockEntity requestedSpeaker = findEditableSpeaker(
                player,
                payload.pos(),
                payload.speakerInstanceId()
        );
        tracePickup(
                player,
                payload,
                BlockInteractionTracePayload.Stage.SERVER_PACKET_RECEIVED,
                requestedSpeaker != null,
                requestedSpeaker == null
                        ? "İstek loaded-chunk, örnek kimliği veya vanilla etkileşim denetiminde reddedildi"
                        : "İstek doğrulandı; takip eden vanilla RightClickBlock paketi bekleniyor"
        );
        if (requestedSpeaker == null) {
            sendPickupResult(
                    player,
                    payload,
                    PortableSpeakerState.UNASSIGNED_ID,
                    false,
                    false,
                    "Hoparlör yüklü değil, değiştirilmiş veya vanilla etkileşim denetimi işlemi reddetti."
            );
            return;
        }
        PendingPickupRequest previous = PENDING_PICKUPS.put(
                player.getUUID(),
                new PendingPickupRequest(
                        payload,
                        player.serverLevel().getGameTime() + PENDING_PICKUP_TIMEOUT_TICKS
                )
        );
        if (previous != null) {
            sendPickupResult(
                    player,
                    previous.payload(),
                    PortableSpeakerState.UNASSIGNED_ID,
                    false,
                    isAuthorizedLoadedSpeakerPresent(player, previous.payload()),
                    "Yeni pickup isteği önceki bekleyen işlemin yerini aldı."
            );
        }
    }

    public static boolean consumePendingPickupRightClick(
            ServerPlayer player,
            net.minecraft.core.BlockPos pos,
            boolean alreadyCanceled
    ) {
        PendingPickupRequest pending = PENDING_PICKUPS.get(player.getUUID());
        if (pending == null || !pending.payload().pos().equals(pos)) {
            return false;
        }
        PENDING_PICKUPS.remove(player.getUUID());
        SpeakerBlockEntity pendingSpeaker = findEditableSpeaker(
                player,
                pending.payload().pos(),
                pending.payload().speakerInstanceId()
        );
        if (pendingSpeaker == null) {
            sendPickupResult(
                    player,
                    pending.payload(),
                    PortableSpeakerState.UNASSIGNED_ID,
                    false,
                    false,
                    "Bekleyen hoparlör artık yüklü, erişilebilir veya aynı blok örneği değil."
            );
            return true;
        }
        if (alreadyCanceled) {
            tracePickup(
                    player,
                    pending.payload(),
                    BlockInteractionTracePayload.Stage.SERVER_RIGHT_CLICK_EVENT,
                    false,
                    "RightClickBlock eventi başka bir handler tarafından daha önce iptal edildi"
            );
            sendPickupResult(
                    player,
                    pending.payload(),
                    PortableSpeakerState.UNASSIGNED_ID,
                    false,
                    true,
                    "RightClickBlock eventi başka bir mod veya event handler tarafından iptal edildi."
            );
            return true;
        }
        tracePickup(
                player,
                pending.payload(),
                BlockInteractionTracePayload.Stage.SERVER_RIGHT_CLICK_EVENT,
                true,
                "Trailing ServerboundUseItemOnPacket iptal edildi; item yeniden yerleştirilemez"
        );
        executePortableSpeakerPickup(player, pending.payload());
        return true;
    }

    public static void expirePendingPickup(ServerPlayer player) {
        PendingPickupRequest pending = PENDING_PICKUPS.get(player.getUUID());
        if (pending == null || player.serverLevel().getGameTime() < pending.deadlineGameTime()) {
            return;
        }
        PENDING_PICKUPS.remove(player.getUUID());
        tracePickup(
                player,
                pending.payload(),
                BlockInteractionTracePayload.Stage.SERVER_RIGHT_CLICK_EVENT,
                false,
                "Vanilla RightClickBlock paketi zaman aşımına uğradı"
        );
        sendPickupResult(
                player,
                pending.payload(),
                PortableSpeakerState.UNASSIGNED_ID,
                false,
                isAuthorizedLoadedSpeakerPresent(player, pending.payload()),
                "Vanilla RightClickBlock eventi zamanında alınamadı."
        );
    }

    public static void clearPendingPickup(ServerPlayer player) {
        PendingPickupRequest pending = PENDING_PICKUPS.remove(player.getUUID());
        if (pending != null) {
            APPROVED_PICKUP_LOGS.remove(new PickupDebugKey(player.getUUID(), pending.payload().requestId()));
        }
    }

    private static void executePortableSpeakerPickup(
            ServerPlayer player,
            PickupPortableSpeakerPayload payload
    ) {
        ServerLevel level = player.serverLevel();
        SpeakerBlockEntity speaker = findEditableSpeaker(player, payload.pos(), payload.speakerInstanceId());
        if (speaker == null) {
            tracePickup(
                    player,
                    payload,
                    BlockInteractionTracePayload.Stage.SERVER_BLOCK_ENTITY_FOUND,
                    false,
                    "Yüklü/erişilebilir SpeakerBlockEntity bulunamadı"
            );
            sendPickupResult(
                    player,
                    payload,
                    PortableSpeakerState.UNASSIGNED_ID,
                    false,
                    false,
                    "SpeakerBlockEntity yüklü değil, örnek kimliği değişti veya vanilla erişim reddedildi."
            );
            return;
        }
        tracePickup(
                player,
                payload,
                BlockInteractionTracePayload.Stage.SERVER_BLOCK_ENTITY_FOUND,
                true,
                "SpeakerBlockEntity bulundu; speakerId=" + speaker.getPortableSpeakerId()
        );
        tracePickup(
                player,
                payload,
                BlockInteractionTracePayload.Stage.SERVER_INTERACTION_VALIDATED,
                true,
                "Erişim, dünya sınırı ve oyun modu kontrolleri geçti"
        );

        PortableSpeakerState portableState = speaker.createPortableState(level.getGameTime());
        PacketDistributor.sendToPlayer(
                player,
                new PreparePortableSpeakerPickupPayload(
                        payload.pos(),
                        portableState.speakerId(),
                        payload.requestId()
                )
        );
        tracePickup(
                player,
                payload,
                BlockInteractionTracePayload.Stage.SERVER_PREPARE_SENT,
                true,
                "Controller transfer kilidi hazırlanıyor"
        );
        ItemStack portableSpeaker = new ItemStack(ModItems.SPEAKER.get());
        portableSpeaker.set(ModDataComponents.PORTABLE_SPEAKER_STATE.get(), portableState);
        boolean removed = level.destroyBlock(payload.pos(), false, player);
        tracePickup(
                player,
                payload,
                BlockInteractionTracePayload.Stage.SERVER_DESTROY_CALLBACK,
                removed,
                "ServerLevel.destroyBlock returned " + removed
        );
        boolean serverSpeakerPresent = level.getBlockState(payload.pos()).is(ModBlocks.SPEAKER);
        tracePickup(
                player,
                payload,
                BlockInteractionTracePayload.Stage.SERVER_BLOCK_STATE_AFTER,
                !serverSpeakerPresent,
                "state=" + BuiltInRegistries.BLOCK.getKey(level.getBlockState(payload.pos()).getBlock())
        );
        boolean serverBlockEntityPresent = level.getBlockEntity(payload.pos()) instanceof SpeakerBlockEntity;
        tracePickup(
                player,
                payload,
                BlockInteractionTracePayload.Stage.SERVER_BLOCK_ENTITY_REMOVED,
                !serverBlockEntityPresent,
                "SpeakerBlockEntity present=" + serverBlockEntityPresent
        );
        if (!removed || serverSpeakerPresent) {
            sendPickupResult(
                    player,
                    payload,
                    portableState.speakerId(),
                    false,
                    serverSpeakerPresent,
                    !removed
                            ? "ServerLevel.destroyBlock callback'i false döndürdü."
                            : "Kaldırma callback'inden sonra sunucuda hoparlör hâlâ mevcut."
            );
            return;
        }
        boolean addedToInventory = player.getInventory().add(portableSpeaker);
        if (!addedToInventory) {
            player.drop(portableSpeaker, false);
            tracePickup(
                    player,
                    payload,
                    BlockInteractionTracePayload.Stage.SERVER_ITEM_DROPPED,
                    true,
                    "Envanter dolu; durumlu item dünyaya bırakıldı"
            );
        } else {
            tracePickup(
                    player,
                    payload,
                    BlockInteractionTracePayload.Stage.SERVER_ITEM_GRANTED,
                    true,
                    "Durumlu item oyuncu envanterine eklendi"
            );
        }
        sendPickupResult(
                player,
                payload,
                portableState.speakerId(),
                true,
                false,
                addedToInventory ? "Hoparlör envantere aktarıldı." : "Envanter dolu; hoparlör dünyaya bırakıldı."
        );
    }

    private static void handleSpeakerStateSync(SpeakerStateSyncPayload payload, IPayloadContext context) {
        if (context.player().level().getBlockEntity(payload.pos()) instanceof SpeakerBlockEntity speaker) {
            speaker.applySyncedState(
                    payload.speakerInstanceId(),
                    payload.currentUrl(),
                    payload.playlistQueue(),
                    payload.playlistIndex(),
                    payload.trackSequence(),
                    payload.elapsedMillis(),
                    payload.playing(),
                    payload.paused(),
                    payload.settings()
            );
        }
    }

    private static void handleSpeakerOperationFeedback(
            SpeakerOperationFeedbackPayload payload,
            IPayloadContext context
    ) {
        ClientSpeakerFeedback.handle(payload, context.player());
    }

    private static void handlePreparePortableSpeakerPickup(
            PreparePortableSpeakerPickupPayload payload,
            IPayloadContext context
    ) {
        BlockInteractionDebugTracker.recordLocal(
                payload.requestId(),
                payload.pos(),
                BlockInteractionTracePayload.Stage.CLIENT_PREPARE_RECEIVED,
                true,
                "PreparePortableSpeakerPickupPayload"
        );
        if (context.player().level().getBlockEntity(payload.pos()) instanceof SpeakerBlockEntity speaker) {
            speaker.preparePortablePickupOnClient(payload.speakerId(), payload.requestId());
        } else {
            BlockInteractionDebugTracker.recordLocal(
                    payload.requestId(),
                    payload.pos(),
                    BlockInteractionTracePayload.Stage.CLIENT_PREPARE_RECEIVED,
                    false,
                    "Prepare geldiğinde istemci BlockEntity bulunamadı"
            );
        }
    }

    private static void handlePortableSpeakerPickupResult(
            PortableSpeakerPickupResultPayload payload,
            IPayloadContext context
    ) {
        BlockInteractionDebugTracker.recordLocal(
                payload.requestId(),
                payload.pos(),
                BlockInteractionTracePayload.Stage.CLIENT_RESULT_RECEIVED,
                payload.success(),
                payload.reason()
        );
        PortableSpeakerPlaybackManager.completePickup(
                payload.pos(),
                payload.speakerId(),
                payload.requestId(),
                payload.success()
        );
        BlockInteractionDebugTracker.expectBlockState(
                payload.requestId(),
                payload.pos(),
                payload.serverSpeakerPresent()
        );
    }

    private static void handleBlockInteractionTrace(
            BlockInteractionTracePayload payload,
            IPayloadContext context
    ) {
        BlockInteractionDebugTracker.acceptServerTrace(payload);
    }

    private static void sendPickupResult(
            ServerPlayer player,
            PickupPortableSpeakerPayload request,
            java.util.UUID speakerId,
            boolean success,
            boolean serverSpeakerPresent,
            String reason
    ) {
        tracePickup(
                player,
                request,
                BlockInteractionTracePayload.Stage.SERVER_RESULT_SENT,
                success,
                reason
        );
        try {
            PacketDistributor.sendToPlayer(
                    player,
                    new PortableSpeakerPickupResultPayload(
                            request.pos(),
                            speakerId,
                            request.requestId(),
                            success,
                            serverSpeakerPresent,
                            reason
                    )
            );
        } finally {
            APPROVED_PICKUP_LOGS.remove(new PickupDebugKey(player.getUUID(), request.requestId()));
        }
    }

    private static void tracePickup(
            ServerPlayer player,
            PickupPortableSpeakerPayload request,
            BlockInteractionTracePayload.Stage stage,
            boolean successful,
            String detail
    ) {
        if (!request.debugRequested()) {
            return;
        }
        BlockInteractionTracePayload trace = new BlockInteractionTracePayload(
                request.requestId(),
                request.pos(),
                stage,
                successful,
                detail
        );
        PacketDistributor.sendToPlayer(player, trace);
        if (APPROVED_PICKUP_LOGS.contains(new PickupDebugKey(player.getUUID(), request.requestId()))) {
            LOGGER.info(
                    "[PocketTune block-debug][{}][{}][{}] {}",
                    request.requestId().toString().substring(0, 8),
                    request.pos().toShortString(),
                    stage.sideLabel(),
                    stage.displayName() + ": " + detail
            );
        }
    }

    public static void sendOperationFeedback(
            ServerPlayer player,
            net.minecraft.core.BlockPos pos,
            SpeakerOperationFeedbackPayload.State state,
            String message
    ) {
        String safeMessage = message == null || message.isBlank() ? "İşlem tamamlanamadı." : message.strip();
        if (safeMessage.length() > SpeakerOperationFeedbackPayload.MAX_MESSAGE_LENGTH) {
            safeMessage = safeMessage.substring(0, SpeakerOperationFeedbackPayload.MAX_MESSAGE_LENGTH);
        }
        PacketDistributor.sendToPlayer(
                player,
                new SpeakerOperationFeedbackPayload(pos, state, safeMessage)
        );
    }

    private static SpeakerBlockEntity findEditableSpeaker(
            ServerPlayer player,
            net.minecraft.core.BlockPos pos,
            UUID speakerInstanceId
    ) {
        ServerLevel level = player.serverLevel();
        ChunkPos chunkPos = new ChunkPos(pos);
        if (!level.hasChunk(chunkPos.x, chunkPos.z)
                || player.distanceToSqr(
                        pos.getX() + 0.5D,
                        pos.getY() + 0.5D,
                        pos.getZ() + 0.5D
                ) > MAX_EDIT_DISTANCE_SQUARED
                || !player.canInteractWithBlock(pos, 1.0D)
                || !level.mayInteract(player, pos)
                || player.blockActionRestricted(level, pos, player.gameMode.getGameModeForPlayer())) {
            return null;
        }
        if (!(level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker)
                || !SpeakerInstanceIdentity.matches(speaker.getSpeakerInstanceId(), speakerInstanceId)) {
            return null;
        }
        return speaker;
    }

    private static boolean isAuthorizedLoadedSpeakerPresent(
            ServerPlayer player,
            PickupPortableSpeakerPayload payload
    ) {
        return findEditableSpeaker(player, payload.pos(), payload.speakerInstanceId()) != null;
    }

    private static void preparePickupDebugLog(ServerPlayer player, PickupPortableSpeakerPayload payload) {
        PickupDebugKey key = new PickupDebugKey(player.getUUID(), payload.requestId());
        APPROVED_PICKUP_LOGS.remove(key);
        boolean approved = DEBUG_LOG_GATE.tryAuthorize(
                player.getUUID(),
                payload.debugRequested() && payload.logRequested(),
                player.createCommandSourceStack().hasPermission(2),
                System.nanoTime()
        );
        if (approved) {
            APPROVED_PICKUP_LOGS.add(key);
        }
    }

    private static SpeakerBlockEntity findSpeakerWithin(
            ServerPlayer player,
            net.minecraft.core.BlockPos pos,
            double maximumDistanceSquared
    ) {
        ServerLevel level = player.serverLevel();
        ChunkPos chunkPos = new ChunkPos(pos);
        if (!level.hasChunk(chunkPos.x, chunkPos.z)
                || player.distanceToSqr(
                        pos.getX() + 0.5D,
                        pos.getY() + 0.5D,
                        pos.getZ() + 0.5D
                ) > maximumDistanceSquared) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker ? speaker : null;
    }

    public static void syncSpeakerState(SpeakerBlockEntity speaker) {
        if (!(speaker.getLevel() instanceof ServerLevel level)) {
            return;
        }
        PacketDistributor.sendToPlayersTrackingChunk(
                level,
                new ChunkPos(speaker.getBlockPos()),
                speaker.createSyncPayload()
        );
    }

    private record PendingPickupRequest(
            PickupPortableSpeakerPayload payload,
            long deadlineGameTime
    ) {
    }

    private record PickupDebugKey(UUID playerId, UUID requestId) {
    }
}
