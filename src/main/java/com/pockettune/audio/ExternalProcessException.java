package com.pockettune.audio;

public final class ExternalProcessException extends Exception {
    private final FailureKind kind;

    public ExternalProcessException(String message) {
        this(message, null, FailureKind.GENERAL);
    }

    public ExternalProcessException(String message, Throwable cause) {
        this(message, cause, FailureKind.GENERAL);
    }

    public ExternalProcessException(String message, FailureKind kind) {
        this(message, null, kind);
    }

    public ExternalProcessException(String message, Throwable cause, FailureKind kind) {
        super(message, cause);
        this.kind = kind == null ? FailureKind.GENERAL : kind;
    }

    public FailureKind kind() {
        return kind;
    }

    public enum FailureKind {
        GENERAL,
        MEDIA_UNAVAILABLE,
        INVALID_INPUT,
        TOOL_MISSING,
        NETWORK,
        TIMEOUT,
        CAPACITY,
        CANCELLED
    }
}
