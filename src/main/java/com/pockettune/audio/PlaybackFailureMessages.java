package com.pockettune.audio;

import java.util.Locale;

/**
 * Converts internal process failures into stable messages that are safe to show to players.
 * Raw yt-dlp/mpv output must never cross the network or enter the normal game log because it can
 * contain request details, local paths or short-lived signed media URLs.
 */
public final class PlaybackFailureMessages {
    private PlaybackFailureMessages() {
    }

    public static String forUrlInput(ExternalProcessException exception) {
        return "Geçerli bir YouTube video veya playlist URL'si girin.";
    }

    public static String forPlaylistResolution(ExternalProcessException exception) {
        return switch (category(exception)) {
            case INVALID_INPUT -> forUrlInput(exception);
            case TOOL_MISSING -> "Sunucuda yt-dlp bulunamadı. Sunucu yöneticisi araç kurulumunu kontrol etmeli.";
            case NETWORK -> "YouTube'a bağlanılamadı. İnternet bağlantısını kontrol edip tekrar deneyin.";
            case TIMEOUT -> "YouTube yanıt vermedi. Lütfen biraz sonra tekrar deneyin.";
            case CAPACITY -> "Yerel ses işlem sınırına ulaşıldı. Bir hoparlörü durdurun veya istemci ayarındaki sınırı artırın.";
            case MEDIA_UNAVAILABLE -> "Video veya playlist kullanılamıyor, gizli ya da erişim kısıtlı olabilir.";
            case GENERAL -> "Şarkı veya playlist bilgileri alınamadı. Lütfen tekrar deneyin.";
        };
    }

    public static String forPlayback(ExternalProcessException exception) {
        return switch (category(exception)) {
            case INVALID_INPUT -> forUrlInput(exception);
            case TOOL_MISSING -> "Oynatma araçları bulunamadı. yt-dlp ve mpv kurulumunu kontrol edin.";
            case NETWORK -> "YouTube'a bağlanılamadı. İnternet bağlantısını kontrol edip tekrar deneyin.";
            case TIMEOUT -> "Şarkı zamanında başlatılamadı. Lütfen tekrar deneyin.";
            case CAPACITY -> "Aynı anda çalabilecek yerel ses sınırına ulaşıldı. Bir hoparlörü durdurun veya istemci ayarındaki sınırı artırın.";
            case MEDIA_UNAVAILABLE -> "Bu şarkı kullanılamıyor, gizli ya da erişim kısıtlı olabilir.";
            case GENERAL -> "Şarkı oynatılamadı. Lütfen tekrar deneyin.";
        };
    }

    /**
     * Safely unwraps an asynchronous failure without exposing process stderr, signed media URLs or
     * machine-local paths. Non-media failures intentionally collapse to one stable public message.
     */
    public static String forThrowable(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 32) {
            if (current instanceof ExternalProcessException external) {
                return forPlayback(external);
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return "PocketTune işlemi tamamlanamadı. Lütfen tekrar deneyin.";
    }

    public static FailureCategory category(ExternalProcessException exception) {
        if (exception == null) {
            return FailureCategory.GENERAL;
        }
        return switch (exception.kind()) {
            case INVALID_INPUT -> FailureCategory.INVALID_INPUT;
            case TOOL_MISSING -> FailureCategory.TOOL_MISSING;
            case NETWORK -> FailureCategory.NETWORK;
            case TIMEOUT -> FailureCategory.TIMEOUT;
            case CAPACITY -> FailureCategory.CAPACITY;
            case MEDIA_UNAVAILABLE -> {
                FailureCategory detail = classifyLegacyMessage(exception.getMessage());
                yield detail == FailureCategory.NETWORK
                        || detail == FailureCategory.TIMEOUT
                        || detail == FailureCategory.TOOL_MISSING
                        ? detail
                        : FailureCategory.MEDIA_UNAVAILABLE;
            }
            case GENERAL, CANCELLED -> classifyLegacyMessage(exception.getMessage());
        };
    }

    /** Returns only bounded categorical data; exception messages and URLs are intentionally absent. */
    public static String safeLogSummary(ExternalProcessException exception) {
        if (exception == null) {
            return "category=GENERAL, kind=GENERAL, cause=none";
        }
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String cause = root == exception ? "none" : root.getClass().getSimpleName();
        return "category=" + category(exception)
                + ", kind=" + exception.kind()
                + ", cause=" + cause;
    }

    private static FailureCategory classifyLegacyMessage(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (containsAny(normalized,
                "geçersiz youtube", "geçerli bir youtube", "url'si boş", "yalnızca youtube")) {
            return FailureCategory.INVALID_INPUT;
        }
        if (containsAny(normalized,
                "program başlatılamadı", "cannot run program", "createprocess error=2",
                "yt-dlp bulunamadı", "mpv bulunamadı", "mpv başlatılamadı")) {
            return FailureCategory.TOOL_MISSING;
        }
        if (containsAny(normalized, "zaman aşım", "timed out", "timeout")) {
            return FailureCategory.TIMEOUT;
        }
        if (containsAny(normalized,
                "unable to download", "network", "connection", "temporary failure",
                "name resolution", "name or service not known", "remote end closed",
                "certificate", "proxy", "http error 429", "http error 5")) {
            return FailureCategory.NETWORK;
        }
        if (containsAny(normalized,
                "video unavailable", "private video", "members-only", "age-restricted",
                "sign in to confirm your age", "not available", "copyright", "removed",
                "oynatılabilir parça bulunamadı", "requested format is not available")) {
            return FailureCategory.MEDIA_UNAVAILABLE;
        }
        return FailureCategory.GENERAL;
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    public enum FailureCategory {
        INVALID_INPUT,
        TOOL_MISSING,
        NETWORK,
        TIMEOUT,
        CAPACITY,
        MEDIA_UNAVAILABLE,
        GENERAL
    }
}
