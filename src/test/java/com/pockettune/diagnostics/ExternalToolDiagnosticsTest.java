package com.pockettune.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalToolDiagnosticsTest {
    @Test
    void explicitOverrideFailureRemainsVisibleToThePlayer() {
        String configError = "The 'externalTools.mpvPathOverride' value in pockettune-common.toml is invalid: "
                + "'C:/missing/mpv.exe' (file not found).";
        ExternalToolDiagnostics.ToolStatus status = new ExternalToolDiagnostics.ToolStatus(
                true,
                "2026.01.01",
                "",
                false,
                "",
                configError
        );

        String message = status.missingToolsMessage();

        assertTrue(message.contains("externalTools.mpvPathOverride"));
        assertTrue(message.contains("C:/missing/mpv.exe"));
        assertTrue(message.contains(ExternalToolDiagnostics.MPV_INSTALL_URL));
    }

    @Test
    void readyStatusHasNoErrorMessage() {
        ExternalToolDiagnostics.ToolStatus status = new ExternalToolDiagnostics.ToolStatus(
                true,
                "yt-version",
                true,
                "mpv-version"
        );

        assertTrue(status.ready());
        assertEquals("", status.missingToolsMessage());
    }
}
