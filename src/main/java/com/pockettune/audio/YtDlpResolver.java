package com.pockettune.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pockettune.model.TrackMetadata;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class YtDlpResolver {
    public static final String DEFAULT_EXECUTABLE = "yt-dlp";
    public static final int MAX_PLAYLIST_ENTRIES = 500;
    public static final int MAX_VIDEO_ID_LENGTH = 128;
    private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration PLAYLIST_RESOLVE_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_URL_LENGTH = 2_048;
    private static final int MAX_ERROR_LENGTH = 500;
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{6,128}");
    private static final Pattern PLAYLIST_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{2,200}");
    private static final Pattern INDEX_PATTERN = Pattern.compile("[0-9]{1,6}");
    private static final Pattern SECONDS_PATTERN = Pattern.compile("[0-9]{1,9}");
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(?:[0-9]{1,9}|[0-9]{1,5}h(?:[0-9]{1,5}m)?(?:[0-9]{1,9}s)?"
                    + "|[0-9]{1,5}m(?:[0-9]{1,9}s)?|[0-9]{1,9}s)"
    );
    private static final Set<String> ALLOWED_QUERY_PARAMETERS = Set.of(
            "v",
            "list",
            "index",
            "t",
            "start",
            "end",
            "time_continue",
            "start_radio"
    );

    private final String configuredExecutable;

    public YtDlpResolver() {
        this.configuredExecutable = null;
    }

    public YtDlpResolver(String executable) {
        this.configuredExecutable = executable;
    }

    public String resolveAudioStream(String rawUrl) throws ExternalProcessException {
        return resolveAudioStream(rawUrl, new ExternalProcessCancellation());
    }

    public String resolveAudioStream(
            String rawUrl,
            ExternalProcessCancellation cancellation
    ) throws ExternalProcessException {
        String url = validateYoutubeUrl(rawUrl);
        List<String> command = List.of(
                executable(),
                "--no-warnings",
                "--no-playlist",
                "-f",
                "bestaudio",
                "-g",
                "--",
                url
        );
        ExternalProcessRunner.ProcessResult result = ExternalProcessRunner.run(
                command,
                RESOLVE_TIMEOUT,
                cancellation
        );

        if (result.exitCode() != 0) {
            String details = result.stderr().isBlank()
                    ? "Bilinmeyen yt-dlp hatası."
                    : summarizeError(result.stderr());
            throw new ExternalProcessException(
                    "yt-dlp stream URL'sini çözemedi: " + details,
                    ExternalProcessException.FailureKind.MEDIA_UNAVAILABLE
            );
        }

        return Arrays.stream(result.stdout().split("\\R"))
                .map(String::trim)
                .filter(line -> line.startsWith("https://") || line.startsWith("http://"))
                .findFirst()
                .orElseThrow(() -> new ExternalProcessException(
                        "yt-dlp geçerli bir ses stream URL'si döndürmedi.",
                        ExternalProcessException.FailureKind.MEDIA_UNAVAILABLE
                ));
    }

    public List<String> resolvePlaylistVideoIds(String rawUrl) throws ExternalProcessException {
        return resolvePlaylistTracks(rawUrl).stream().map(TrackMetadata::videoId).toList();
    }

    public List<TrackMetadata> resolvePlaylistTracks(String rawUrl) throws ExternalProcessException {
        return resolvePlaylistTracks(rawUrl, new ExternalProcessCancellation());
    }

    public List<TrackMetadata> resolvePlaylistTracks(
            String rawUrl,
            ExternalProcessCancellation cancellation
    ) throws ExternalProcessException {
        String url = validateYoutubeUrl(rawUrl);
        List<String> command = List.of(
                executable(),
                "--no-warnings",
                "--flat-playlist",
                "--playlist-end",
                Integer.toString(MAX_PLAYLIST_ENTRIES + 1),
                "--dump-single-json",
                "--",
                url
        );
        ExternalProcessRunner.ProcessResult result = ExternalProcessRunner.run(
                command,
                PLAYLIST_RESOLVE_TIMEOUT,
                cancellation
        );
        if (result.exitCode() != 0) {
            String details = result.stderr().isBlank()
                    ? "Bilinmeyen yt-dlp hatası."
                    : summarizeError(result.stderr());
            throw new ExternalProcessException("yt-dlp playlist'i çözemedi: " + details);
        }

        List<TrackMetadata> tracks = parsePlaylistTracks(result.stdout());
        if (tracks.isEmpty()) {
            throw new ExternalProcessException("YouTube URL'sinde oynatılabilir parça bulunamadı.");
        }
        if (tracks.size() > MAX_PLAYLIST_ENTRIES) {
            throw new ExternalProcessException(
                    "Playlist en fazla " + MAX_PLAYLIST_ENTRIES + " parça içerebilir."
            );
        }
        return List.copyOf(tracks);
    }

    static List<TrackMetadata> parsePlaylistTracks(String output) throws ExternalProcessException {
        try {
            JsonObject root = JsonParser.parseString(output).getAsJsonObject();
            JsonArray entries = root.has("entries") && root.get("entries").isJsonArray()
                    ? root.getAsJsonArray("entries")
                    : null;
            List<TrackMetadata> tracks = new ArrayList<>();
            if (entries == null) {
                addTrack(root, tracks);
            } else {
                for (JsonElement entry : entries) {
                    if (entry != null && entry.isJsonObject()) {
                        addTrack(entry.getAsJsonObject(), tracks);
                    }
                }
            }
            return tracks;
        } catch (RuntimeException exception) {
            throw new ExternalProcessException("yt-dlp geçersiz playlist metadata çıktısı döndürdü.", exception);
        }
    }

    private static void addTrack(JsonObject entry, List<TrackMetadata> tracks) {
        String videoId = jsonString(entry, "id", "");
        if (!isValidVideoId(videoId)) {
            return;
        }
        String title = jsonString(entry, "title", "YouTube Video");
        String artist = jsonString(entry, "uploader", jsonString(entry, "channel", "YouTube"));
        double durationSeconds = entry.has("duration") && !entry.get("duration").isJsonNull()
                ? entry.get("duration").getAsDouble()
                : 0.0D;
        long durationMillis = Double.isFinite(durationSeconds) && durationSeconds > 0.0D
                ? Math.round(durationSeconds * 1_000.0D)
                : 0L;
        tracks.add(new TrackMetadata(videoId, title, artist, durationMillis));
    }

    private static String jsonString(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    static List<String> parsePlaylistIds(String output) {
        List<String> videoIds = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String videoId = line.trim();
            if (VIDEO_ID_PATTERN.matcher(videoId).matches()) {
                videoIds.add(videoId);
            }
        }
        return videoIds;
    }

    public static String videoUrl(String videoId) throws ExternalProcessException {
        if (!isValidVideoId(videoId)) {
            throw invalidInput("Geçersiz YouTube video kimliği.");
        }
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    public static boolean isValidVideoId(String videoId) {
        return videoId != null && VIDEO_ID_PATTERN.matcher(videoId).matches();
    }

    public static Optional<String> extractVideoId(String rawUrl) {
        try {
            URI uri = new URI(validateYoutubeUrl(rawUrl));
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (host.equals("youtu.be") || host.endsWith(".youtu.be")) {
                return firstValidPathSegment(path);
            }

            String[] pathSegments = path.split("/");
            if (pathSegments.length >= 3
                    && (pathSegments[1].equals("shorts")
                    || pathSegments[1].equals("embed")
                    || pathSegments[1].equals("live"))
                    && isValidVideoId(pathSegments[2])) {
                return Optional.of(pathSegments[2]);
            }

            String query = uri.getRawQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    int separator = pair.indexOf('=');
                    if (separator > 0 && pair.substring(0, separator).equals("v")) {
                        String videoId = URLDecoder.decode(
                                pair.substring(separator + 1), StandardCharsets.UTF_8);
                        if (isValidVideoId(videoId)) {
                            return Optional.of(videoId);
                        }
                    }
                }
            }
        } catch (ExternalProcessException | URISyntaxException | IllegalArgumentException ignored) {
            // Eski veya bozuk NBT verisi migration sırasında güvenle yok sayılır.
        }
        return Optional.empty();
    }

    private static Optional<String> firstValidPathSegment(String path) {
        for (String segment : path.split("/")) {
            if (isValidVideoId(segment)) {
                return Optional.of(segment);
            }
        }
        return Optional.empty();
    }

    public ToolVersion probe() {
        try {
            String executable = executable();
            ExternalProcessRunner.ProcessResult result = ExternalProcessRunner.run(
                    List.of(executable, "--version"), VERSION_TIMEOUT);
            if (result.exitCode() == 0 && !result.stdout().isBlank()) {
                return new ToolVersion(true, result.stdout().lines().findFirst().orElse("unknown"));
            }
            String details = result.stderr().isBlank()
                    ? "yt-dlp sürüm denetimi başarısız oldu (çıkış kodu " + result.exitCode() + ")."
                    : summarizeError(result.stderr());
            return new ToolVersion(false, "", details);
        } catch (ExternalProcessException exception) {
            return new ToolVersion(false, "", exception.getMessage());
        }
    }

    private String executable() throws ExternalProcessException {
        return configuredExecutable != null
                ? configuredExecutable
                : ExternalToolLocator.resolveConfiguredYtDlp().command();
    }

    public static String validateYoutubeUrl(String rawUrl) throws ExternalProcessException {
        if (rawUrl == null) {
            throw invalidInput("YouTube URL'si boş olamaz.");
        }

        String url = rawUrl.trim();
        if (url.isEmpty() || url.length() > MAX_URL_LENGTH) {
            throw invalidInput("YouTube URL'si boş veya çok uzun.");
        }

        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null || uri.getUserInfo() != null) {
                throw invalidInput("Geçerli bir YouTube URL'si girin.");
            }

            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            boolean validScheme = normalizedScheme.equals("https");
            boolean validHost = normalizedHost.equals("youtube.com")
                    || normalizedHost.endsWith(".youtube.com")
                    || normalizedHost.equals("youtu.be")
                    || normalizedHost.endsWith(".youtu.be")
                    || normalizedHost.equals("youtube-nocookie.com")
                    || normalizedHost.endsWith(".youtube-nocookie.com");
            boolean validPort = port == -1 || port == 443;

            if (!validScheme || !validHost || !validPort) {
                throw invalidInput("Yalnızca güvenli YouTube video ve playlist URL'leri destekleniyor.");
            }

            URI asciiUri = new URI(uri.toASCIIString());
            String rawPath = asciiUri.getRawPath();
            String rawQuery = sanitizeQuery(asciiUri.getRawQuery());
            if (!isSupportedMediaShape(normalizedHost, rawPath, rawQuery)) {
                throw invalidInput("Geçerli bir YouTube video veya playlist URL'si girin.");
            }
            StringBuilder sanitized = new StringBuilder("https://").append(normalizedHost);
            if (rawPath != null && !rawPath.isEmpty()) {
                sanitized.append(rawPath);
            }
            if (rawQuery != null && !rawQuery.isEmpty()) {
                sanitized.append('?').append(rawQuery);
            }
            // Fragmentler ve bilinen paylaşım/takip parametreleri kalıcı state'e hiç girmez.
            String asciiUrl = sanitized.toString();
            if (asciiUrl.length() > MAX_URL_LENGTH) {
                throw invalidInput("YouTube URL'si boş veya çok uzun.");
            }
            return asciiUrl;
        } catch (URISyntaxException exception) {
            throw new ExternalProcessException(
                    "Geçersiz YouTube URL'si.",
                    exception,
                    ExternalProcessException.FailureKind.INVALID_INPUT
            );
        }
    }

    /** Returns an empty value for malformed or legacy persisted URLs instead of reintroducing them. */
    public static String sanitizeStoredYoutubeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        try {
            return validateYoutubeUrl(rawUrl);
        } catch (ExternalProcessException ignored) {
            return "";
        }
    }

    private static String sanitizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        List<String> retained = new ArrayList<>();
        Set<String> retainedNames = new HashSet<>();
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            if (separator <= 0 || separator == pair.length() - 1) {
                continue;
            }
            String rawName = pair.substring(0, separator);
            final String decodedName;
            final String decodedValue;
            try {
                decodedName = URLDecoder.decode(rawName, StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT);
                decodedValue = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (!ALLOWED_QUERY_PARAMETERS.contains(decodedName)
                    || retainedNames.contains(decodedName)
                    || !isValidQueryValue(decodedName, decodedValue)) {
                continue;
            }
            retainedNames.add(decodedName);
            retained.add(decodedName + '=' + decodedValue);
        }
        return retained.isEmpty() ? null : String.join("&", retained);
    }

    private static boolean isValidQueryValue(String name, String value) {
        return switch (name) {
            case "v" -> isValidVideoId(value);
            case "list" -> PLAYLIST_ID_PATTERN.matcher(value).matches();
            case "index" -> INDEX_PATTERN.matcher(value).matches();
            case "t" -> TIME_PATTERN.matcher(value).matches();
            case "start", "end", "time_continue" -> SECONDS_PATTERN.matcher(value).matches();
            case "start_radio" -> value.equals("1");
            default -> false;
        };
    }

    private static boolean isSupportedMediaShape(String host, String rawPath, String rawQuery) {
        List<String> segments = decodedPathSegments(rawPath);
        if (host.equals("youtu.be") || host.endsWith(".youtu.be")) {
            return segments.size() == 1 && isValidVideoId(segments.getFirst());
        }

        boolean hasVideo = queryValue(rawQuery, "v").filter(YtDlpResolver::isValidVideoId).isPresent();
        boolean hasPlaylist = queryValue(rawQuery, "list")
                .filter(value -> PLAYLIST_ID_PATTERN.matcher(value).matches())
                .isPresent();
        String path = rawPath == null || rawPath.isEmpty() ? "/" : rawPath;
        if (path.equals("/watch") || path.equals("/watch/")) {
            return hasVideo || hasPlaylist;
        }
        if (path.equals("/playlist") || path.equals("/playlist/")) {
            return hasPlaylist;
        }
        if (segments.size() != 2) {
            return false;
        }
        String mediaType = segments.getFirst().toLowerCase(Locale.ROOT);
        String mediaId = segments.get(1);
        if (mediaType.equals("embed") && mediaId.equalsIgnoreCase("videoseries")) {
            return hasPlaylist;
        }
        return (mediaType.equals("shorts")
                || mediaType.equals("embed")
                || mediaType.equals("live")
                || mediaType.equals("v"))
                && isValidVideoId(mediaId);
    }

    private static List<String> decodedPathSegments(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return List.of();
        }
        List<String> segments = new ArrayList<>();
        try {
            for (String segment : rawPath.split("/")) {
                if (!segment.isEmpty()) {
                    segments.add(URLDecoder.decode(segment, StandardCharsets.UTF_8));
                }
            }
            return List.copyOf(segments);
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }

    private static Optional<String> queryValue(String rawQuery, String expectedName) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Optional.empty();
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0 || !pair.substring(0, separator).equals(expectedName)) {
                continue;
            }
            try {
                return Optional.of(URLDecoder.decode(
                        pair.substring(separator + 1),
                        StandardCharsets.UTF_8
                ));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static ExternalProcessException invalidInput(String message) {
        return new ExternalProcessException(message, ExternalProcessException.FailureKind.INVALID_INPUT);
    }

    private static String summarizeError(String error) {
        String singleLine = error.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= MAX_ERROR_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_ERROR_LENGTH) + "…";
    }

    public record ToolVersion(boolean available, String version, String errorMessage) {
        public ToolVersion(boolean available, String version) {
            this(available, version, "");
        }

        public ToolVersion {
            version = version == null ? "" : version;
            errorMessage = errorMessage == null ? "" : errorMessage;
        }
    }
}
