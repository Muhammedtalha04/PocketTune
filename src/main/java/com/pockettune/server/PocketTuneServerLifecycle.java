package com.pockettune.server;

import com.mojang.logging.LogUtils;
import com.pockettune.audio.ExternalProcessException;
import com.pockettune.audio.PlaybackFailureMessages;
import com.pockettune.diagnostics.ExternalToolDiagnostics;
import com.pockettune.service.PlaylistResolutionService;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/** Registers server lifecycle diagnostics and resolver cleanup; it exposes no player commands. */
public final class PocketTuneServerLifecycle {
    private static final Logger LOGGER = LogUtils.getLogger();

    private PocketTuneServerLifecycle() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(PocketTuneServerLifecycle::onServerStarted);
        NeoForge.EVENT_BUS.addListener(PocketTuneServerLifecycle::onServerStopping);
        NeoForge.EVENT_BUS.addListener(PocketTuneServerLifecycle::onServerStopped);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        ExternalToolDiagnostics.checkServerAsync().thenAccept(ytDlp -> {
            if (ytDlp.available()) {
                LOGGER.info("PocketTune server resolver ready. yt-dlp={}", ytDlp.version());
                return;
            }
            String reason = ytDlp.errorMessage().isBlank()
                    ? "yt-dlp bulunamadı."
                    : ytDlp.errorMessage();
            String safeSummary = PlaybackFailureMessages.safeLogSummary(
                    new ExternalProcessException(reason)
            );
            LOGGER.error(
                    "PocketTune server cannot resolve YouTube media: {} Kurulum: {}",
                    safeSummary,
                    ExternalToolDiagnostics.YT_DLP_INSTALL_URL
            );
        });
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        PlaylistResolutionService.shutdown(event.getServer());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        PlaylistResolutionService.forgetStoppedServer(event.getServer());
    }
}
