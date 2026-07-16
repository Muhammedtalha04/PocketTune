package com.pockettune.diagnostics;

import com.pockettune.audio.MpvController;
import com.pockettune.audio.YtDlpResolver;

import java.util.concurrent.CompletableFuture;

public final class ExternalToolDiagnostics {
    public static final String YT_DLP_INSTALL_URL = "https://github.com/yt-dlp/yt-dlp#installation";
    public static final String MPV_INSTALL_URL = "https://mpv.io/installation/";

    private ExternalToolDiagnostics() {
    }

    public static ToolStatus check() {
        YtDlpResolver.ToolVersion ytDlp = new YtDlpResolver().probe();
        YtDlpResolver.ToolVersion mpv = MpvController.probe();
        return new ToolStatus(
                ytDlp.available(),
                ytDlp.version(),
                ytDlp.errorMessage(),
                mpv.available(),
                mpv.version(),
                mpv.errorMessage()
        );
    }

    public static CompletableFuture<ToolStatus> checkAsync() {
        return CompletableFuture.supplyAsync(ExternalToolDiagnostics::check);
    }

    public static CompletableFuture<YtDlpResolver.ToolVersion> checkServerAsync() {
        return CompletableFuture.supplyAsync(() -> new YtDlpResolver().probe());
    }

    public record ToolStatus(
            boolean ytDlpAvailable,
            String ytDlpVersion,
            String ytDlpError,
            boolean mpvAvailable,
            String mpvVersion,
            String mpvError
    ) {
        public ToolStatus(boolean ytDlpAvailable, String ytDlpVersion, boolean mpvAvailable, String mpvVersion) {
            this(ytDlpAvailable, ytDlpVersion, "", mpvAvailable, mpvVersion, "");
        }

        public ToolStatus {
            ytDlpVersion = safe(ytDlpVersion);
            ytDlpError = safe(ytDlpError);
            mpvVersion = safe(mpvVersion);
            mpvError = safe(mpvError);
        }

        public boolean ready() {
            return ytDlpAvailable && mpvAvailable;
        }

        public String missingToolsMessage() {
            if (ready()) {
                return "";
            }
            if (!ytDlpAvailable && !mpvAvailable) {
                return failureMessage("yt-dlp", ytDlpError, YT_DLP_INSTALL_URL)
                        + " | "
                        + failureMessage("mpv", mpvError, MPV_INSTALL_URL);
            }
            if (!ytDlpAvailable) {
                return failureMessage("yt-dlp", ytDlpError, YT_DLP_INSTALL_URL);
            }
            return failureMessage("mpv", mpvError, MPV_INSTALL_URL);
        }

        private static String failureMessage(String tool, String details, String installUrl) {
            String reason = details.isBlank() ? tool + " was not found." : details;
            return reason + " Installation: " + installUrl;
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }
}
