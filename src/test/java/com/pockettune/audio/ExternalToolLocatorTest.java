package com.pockettune.audio;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalToolLocatorTest {
    private static final Path WORKING_DIRECTORY = Path.of("sandbox").toAbsolutePath().normalize();

    @Test
    void blankOverrideUsesAutomaticallyDetectedExecutable() throws Exception {
        Path detected = WORKING_DIRECTORY.resolve("tools/yt-dlp.exe");

        ExternalToolLocator.ResolvedExecutable result = ExternalToolLocator.resolveYtDlp(
                "  ",
                WORKING_DIRECTORY,
                Optional.of(detected),
                true,
                (path, windows) -> {
                    throw new AssertionError("Automatic paths must not be reinterpreted as config overrides");
                }
        );

        assertEquals(detected.toString(), result.command());
        assertEquals(detected, result.path());
        assertEquals(ExternalToolLocator.ResolutionSource.AUTOMATIC_PATH, result.source());
    }

    @Test
    void blankOverrideFallsBackToBareSystemCommandWhenDetectionFindsNothing() throws Exception {
        ExternalToolLocator.ResolvedExecutable result = ExternalToolLocator.resolveMpv(
                "",
                WORKING_DIRECTORY,
                Optional.empty(),
                false,
                (path, windows) -> {
                    throw new AssertionError("Validator must not run without an explicit override");
                }
        );

        assertEquals("mpv", result.command());
        assertEquals(ExternalToolLocator.ResolutionSource.SYSTEM_COMMAND, result.source());
        assertNull(result.path());
    }

    @Test
    void validRelativeOverrideWinsAndIsResolvedFromWorkingDirectory() throws Exception {
        AtomicReference<Path> validatedPath = new AtomicReference<>();

        ExternalToolLocator.ResolvedExecutable result = ExternalToolLocator.resolveYtDlp(
                "tools/yt-dlp",
                WORKING_DIRECTORY,
                Optional.of(WORKING_DIRECTORY.resolve("automatic/yt-dlp")),
                false,
                (path, windows) -> {
                    validatedPath.set(path);
                    assertEquals(false, windows);
                    return ExternalToolLocator.PathValidation.valid();
                }
        );

        Path expected = WORKING_DIRECTORY.resolve("tools/yt-dlp").normalize();
        assertEquals(expected, validatedPath.get());
        assertEquals(expected.toString(), result.command());
        assertEquals(ExternalToolLocator.ResolutionSource.CONFIG_OVERRIDE, result.source());
    }

    @Test
    void invalidNonBlankOverrideNeverFallsBackToAutomaticDetection() {
        String configuredPath = "missing/yt-dlp.exe";

        ExternalProcessException exception = assertThrows(
                ExternalProcessException.class,
                () -> ExternalToolLocator.resolveYtDlp(
                        configuredPath,
                        WORKING_DIRECTORY,
                        Optional.of(WORKING_DIRECTORY.resolve("automatic/yt-dlp.exe")),
                        true,
                        (path, windows) -> ExternalToolLocator.PathValidation.invalid("dosya bulunamadı")
                )
        );

        assertTrue(exception.getMessage().contains(ExternalToolLocator.YT_DLP_CONFIG_KEY));
        assertTrue(exception.getMessage().contains(configuredPath));
        assertTrue(exception.getMessage().contains("dosya bulunamadı"));
        assertTrue(exception.getMessage().contains("boş bırakın"));
        assertEquals(ExternalProcessException.FailureKind.TOOL_MISSING, exception.kind());
    }

    @Test
    void mpvOverrideErrorNamesTheMpvConfigKeyAndConfiguredPath() {
        String configuredPath = "not-an-executable";

        ExternalProcessException exception = assertThrows(
                ExternalProcessException.class,
                () -> ExternalToolLocator.resolveMpv(
                        configuredPath,
                        WORKING_DIRECTORY,
                        Optional.empty(),
                        false,
                        (path, windows) -> ExternalToolLocator.PathValidation.invalid("çalıştırma izni yok")
                )
        );

        assertTrue(exception.getMessage().contains(ExternalToolLocator.MPV_CONFIG_KEY));
        assertTrue(exception.getMessage().contains(configuredPath));
        assertTrue(exception.getMessage().contains("çalıştırma izni yok"));
        assertEquals(ExternalProcessException.FailureKind.TOOL_MISSING, exception.kind());
    }

    @Test
    void malformedOverrideIsReportedWithoutInvokingValidator() {
        ExternalProcessException exception = assertThrows(
                ExternalProcessException.class,
                () -> ExternalToolLocator.resolveYtDlp(
                        "bad\0path",
                        WORKING_DIRECTORY,
                        Optional.empty(),
                        true,
                        (path, windows) -> {
                            throw new AssertionError("Malformed paths must fail before filesystem validation");
                        }
                )
        );

        assertTrue(exception.getMessage().contains(ExternalToolLocator.YT_DLP_CONFIG_KEY));
        assertTrue(exception.getMessage().contains("geçerli bir dosya yolu değil"));
        assertEquals(ExternalProcessException.FailureKind.TOOL_MISSING, exception.kind());
    }
}
