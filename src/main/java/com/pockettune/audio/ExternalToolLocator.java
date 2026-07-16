package com.pockettune.audio;

import com.pockettune.config.PocketTuneCommonConfig;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

public final class ExternalToolLocator {
    public static final String YT_DLP_CONFIG_KEY = "externalTools.ytDlpPathOverride";
    public static final String MPV_CONFIG_KEY = "externalTools.mpvPathOverride";

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");

    private static final ToolDescriptor YT_DLP = new ToolDescriptor(
            "yt-dlp",
            YT_DLP_CONFIG_KEY,
            List.of("yt-dlp.exe"),
            "yt-dlp.yt-dlp_"
    );
    private static final ToolDescriptor MPV = new ToolDescriptor(
            "mpv",
            MPV_CONFIG_KEY,
            List.of("mpv.exe", "mpv.com"),
            "mpv-player.mpv-CI.MSVC_"
    );

    private ExternalToolLocator() {
    }

    public static Optional<Path> findYtDlp() {
        return find(YT_DLP);
    }

    public static Optional<Path> findMpv() {
        return find(MPV);
    }

    public static ResolvedExecutable resolveConfiguredYtDlp() throws ExternalProcessException {
        return resolve(YT_DLP, PocketTuneCommonConfig.YT_DLP_PATH_OVERRIDE.get());
    }

    public static ResolvedExecutable resolveConfiguredMpv() throws ExternalProcessException {
        return resolve(MPV, PocketTuneCommonConfig.MPV_PATH_OVERRIDE.get());
    }

    static ResolvedExecutable resolveYtDlp(
            String override,
            Path workingDirectory,
            Optional<Path> automaticallyDetected,
            boolean windows,
            ExecutablePathValidator validator
    ) throws ExternalProcessException {
        return resolve(YT_DLP, override, workingDirectory, automaticallyDetected, windows, validator);
    }

    static ResolvedExecutable resolveMpv(
            String override,
            Path workingDirectory,
            Optional<Path> automaticallyDetected,
            boolean windows,
            ExecutablePathValidator validator
    ) throws ExternalProcessException {
        return resolve(MPV, override, workingDirectory, automaticallyDetected, windows, validator);
    }

    private static ResolvedExecutable resolve(ToolDescriptor tool, String override)
            throws ExternalProcessException {
        String normalizedOverride = override == null ? "" : override.trim();
        return resolve(
                tool,
                normalizedOverride,
                FMLPaths.GAMEDIR.get().toAbsolutePath().normalize(),
                normalizedOverride.isEmpty() ? find(tool) : Optional.empty(),
                WINDOWS,
                ExternalToolLocator::validateExecutablePath
        );
    }

    /**
     * Deterministic resolution seam: the caller supplies automatic detection and filesystem
     * validation. This keeps override precedence and error policy unit-testable without starting
     * a process or depending on the test machine's PATH.
     */
    private static ResolvedExecutable resolve(
            ToolDescriptor tool,
            String rawOverride,
            Path workingDirectory,
            Optional<Path> automaticallyDetected,
            boolean windows,
            ExecutablePathValidator validator
    ) throws ExternalProcessException {
        String override = rawOverride == null ? "" : rawOverride.trim();
        if (override.isEmpty()) {
            return automaticallyDetected
                    .map(path -> new ResolvedExecutable(
                            path.toAbsolutePath().normalize().toString(),
                            ResolutionSource.AUTOMATIC_PATH,
                            path.toAbsolutePath().normalize()
                    ))
                    .orElseGet(() -> new ResolvedExecutable(
                            tool.command(),
                            ResolutionSource.SYSTEM_COMMAND,
                            null
                    ));
        }

        final Path configuredPath;
        try {
            Path parsed = Path.of(override);
            configuredPath = (parsed.isAbsolute() ? parsed : workingDirectory.resolve(parsed))
                    .toAbsolutePath()
                    .normalize();
        } catch (InvalidPathException exception) {
            throw invalidOverride(tool, override, "işletim sistemi için geçerli bir dosya yolu değil", exception);
        }

        PathValidation validation = validator.validate(configuredPath, windows);
        if (!validation.usable()) {
            throw invalidOverride(tool, override, validation.reason(), null);
        }
        return new ResolvedExecutable(
                configuredPath.toString(),
                ResolutionSource.CONFIG_OVERRIDE,
                configuredPath
        );
    }

    private static PathValidation validateExecutablePath(Path path, boolean windows) {
        if (!Files.isRegularFile(path)) {
            return PathValidation.invalid("dosya bulunamadı veya normal bir dosya değil");
        }
        if (windows) {
            String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!fileName.endsWith(".exe") && !fileName.endsWith(".com")) {
                return PathValidation.invalid(
                        "Windows'ta shell kullanmadan çalıştırılabilen bir .exe veya .com dosyası değil"
                );
            }
        }
        if (!windows && !Files.isExecutable(path)) {
            return PathValidation.invalid("dosyanın çalıştırma izni yok");
        }
        return PathValidation.valid();
    }

    private static ExternalProcessException invalidOverride(
            ToolDescriptor tool,
            String configuredValue,
            String reason,
            Throwable cause
    ) {
        String message = "pockettune-common.toml içindeki '" + tool.configKey()
                + "' geçersiz: '" + configuredValue + "' (" + reason
                + "). Değeri düzeltin veya otomatik arama için boş bırakın.";
        return cause == null
                ? new ExternalProcessException(message, ExternalProcessException.FailureKind.TOOL_MISSING)
                : new ExternalProcessException(
                        message,
                        cause,
                        ExternalProcessException.FailureKind.TOOL_MISSING
                );
    }

    private static Optional<Path> find(ToolDescriptor tool) {
        Optional<Path> pathExecutable = findOnPath(tool.command(), tool.windowsFileNames());
        if (pathExecutable.isPresent() || !WINDOWS) {
            return pathExecutable;
        }

        Path localAppData = localAppData();
        Optional<Path> wingetLink = findNamedFile(
                localAppData.resolve("Microsoft").resolve("WinGet").resolve("Links"),
                tool.windowsFileNames(),
                1
        );
        if (wingetLink.isPresent()) {
            return wingetLink;
        }

        Path packages = localAppData.resolve("Microsoft").resolve("WinGet").resolve("Packages");
        if (!Files.isDirectory(packages)) {
            return Optional.empty();
        }

        try (Stream<Path> directories = Files.list(packages)) {
            List<Path> matchingPackages = directories
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(tool.wingetPackagePrefix()))
                    .sorted(Comparator.comparingLong(ExternalToolLocator::lastModified).reversed())
                    .toList();
            for (Path packageDirectory : matchingPackages) {
                Optional<Path> executable = findNamedFile(packageDirectory, tool.windowsFileNames(), 4);
                if (executable.isPresent()) {
                    return executable;
                }
            }
        } catch (IOException ignored) {
            // WinGet klasörü okunamazsa standart eksik araç hatası gösterilir.
        }
        return Optional.empty();
    }

    private static Optional<Path> findOnPath(String command, List<String> fileNames) {
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return Optional.empty();
        }

        List<String> candidates = new ArrayList<>(fileNames);
        if (!WINDOWS) {
            candidates.addFirst(command);
        }
        for (String directory : pathValue.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            for (String candidate : candidates) {
                try {
                    Path executable = Path.of(directory).resolve(candidate);
                    if (Files.isRegularFile(executable) && (WINDOWS || Files.isExecutable(executable))) {
                        return Optional.of(executable.toAbsolutePath().normalize());
                    }
                } catch (InvalidPathException ignored) {
                    // Malformed entries do not make the rest of PATH unusable.
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> findNamedFile(Path root, List<String> fileNames, int maxDepth) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        for (String fileName : fileNames) {
            try (Stream<Path> paths = Files.find(root, maxDepth, (path, attributes) ->
                    attributes.isRegularFile()
                            && fileName.equalsIgnoreCase(path.getFileName().toString()))) {
                Optional<Path> match = paths.findFirst().map(path -> path.toAbsolutePath().normalize());
                if (match.isPresent()) {
                    return match;
                }
            } catch (IOException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Path localAppData() {
        String value = System.getenv("LOCALAPPDATA");
        if (value != null && !value.isBlank()) {
            try {
                return Path.of(value);
            } catch (InvalidPathException ignored) {
                // Fall through to the standard user-home location.
            }
        }
        return Path.of(System.getProperty("user.home", "."), "AppData", "Local");
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    public record ResolvedExecutable(String command, ResolutionSource source, Path path) {
    }

    public enum ResolutionSource {
        CONFIG_OVERRIDE,
        AUTOMATIC_PATH,
        SYSTEM_COMMAND
    }

    @FunctionalInterface
    interface ExecutablePathValidator {
        PathValidation validate(Path path, boolean windows);
    }

    record PathValidation(boolean usable, String reason) {
        static PathValidation valid() {
            return new PathValidation(true, "");
        }

        static PathValidation invalid(String reason) {
            return new PathValidation(false, reason);
        }
    }

    private record ToolDescriptor(
            String command,
            String configKey,
            List<String> windowsFileNames,
            String wingetPackagePrefix
    ) {
    }
}
