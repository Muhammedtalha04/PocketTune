package com.pockettune.audio.ipc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pockettune.audio.ExternalProcessException;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MpvIpcClient {
    private static final int MAX_RESPONSE_BYTES = 65_536;
    private static final int MAX_SKIPPED_LINES = 256;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    private final String endpoint;
    private final Path unixSocketPath;
    private long requestSequence;
    private volatile boolean usable = true;

    public MpvIpcClient(String endpoint, Path unixSocketPath) {
        this.endpoint = endpoint;
        this.unixSocketPath = unixSocketPath;
    }

    public synchronized JsonObject request(JsonObject request) throws ExternalProcessException {
        if (!usable) {
            throw new ExternalProcessException("The mpv IPC connection is unavailable after a previous timeout.");
        }
        // mpv aynı bağlantıya asenkron event satırları da yazar (start-file, seek, playback-restart...).
        // İlk satırı yanıt saymak yerine request_id eşleşen satır bulunana kadar event'ler atlanır.
        long requestId = ++requestSequence;
        request.addProperty("request_id", requestId);
        String serializedRequest = request.toString();
        AtomicReference<Closeable> activeConnection = new AtomicReference<>();
        AtomicBoolean requestExpired = new AtomicBoolean();
        JsonObject response;
        try {
            response = IpcRequestExecutor.execute(
                    () -> unixSocketPath == null
                            ? requestWindows(serializedRequest, requestId, activeConnection, requestExpired)
                            : requestUnix(serializedRequest, requestId, activeConnection, requestExpired),
                    REQUEST_TIMEOUT,
                    () -> {
                        requestExpired.set(true);
                        closeQuietly(activeConnection.getAndSet(null));
                    }
            );
        } catch (ExternalProcessException exception) {
            if (requestExpired.get()) {
                usable = false;
            }
            throw exception;
        }

        String error = response.has("error") ? response.get("error").getAsString() : "unknown";
        if (!"success".equals(error)) {
            throw new ExternalProcessException(
                    "mpv IPC command failed " + request.get("command") + ": " + error
            );
        }
        return response;
    }

    public boolean isUsable() {
        return usable;
    }

    private static JsonObject matchResponse(String line, long requestId) {
        try {
            JsonElement parsed = JsonParser.parseString(line);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject object = parsed.getAsJsonObject();
            if (object.has("event")) {
                return null;
            }
            if (object.has("request_id") && !object.get("request_id").isJsonNull()) {
                return object.get("request_id").getAsLong() == requestId ? object : null;
            }
            // request_id yankılamayan çok eski mpv sürümlerinde hata alanlı satır yanıt kabul edilir.
            return object.has("error") ? object : null;
        } catch (RuntimeException ignored) {
            // JSON olmayan/bozuk satırlar yanıt değildir, akış taranmaya devam eder.
            return null;
        }
    }

    private JsonObject requestWindows(
            String request,
            long requestId,
            AtomicReference<Closeable> activeConnection,
            AtomicBoolean requestExpired
    ) throws ExternalProcessException {
        RandomAccessFile pipe = null;
        try {
            pipe = new RandomAccessFile(endpoint, "rw");
            registerConnection(pipe, activeConnection, requestExpired);
            pipe.write((request + "\n").getBytes(StandardCharsets.UTF_8));
            for (int lines = 0; lines < MAX_SKIPPED_LINES; lines++) {
                String line = pipe.readLine();
                if (line == null) {
                    throw new ExternalProcessException("The mpv IPC connection closed without a response.");
                }
                JsonObject response = matchResponse(line, requestId);
                if (response != null) {
                    return response;
                }
            }
            throw new ExternalProcessException("The mpv IPC response was not found in the event stream.");
        } catch (IOException exception) {
            throw new ExternalProcessException("The mpv Windows IPC pipe connection could not be established.", exception);
        } finally {
            if (pipe != null) {
                activeConnection.compareAndSet(pipe, null);
                closeQuietly(pipe);
            }
        }
    }

    private JsonObject requestUnix(
            String request,
            long requestId,
            AtomicReference<Closeable> activeConnection,
            AtomicBoolean requestExpired
    ) throws ExternalProcessException {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(unixSocketPath);
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            registerConnection(channel, activeConnection, requestExpired);
            channel.connect(address);
            ByteBuffer output = StandardCharsets.UTF_8.encode(request + "\n");
            while (output.hasRemaining()) {
                channel.write(output);
            }

            ByteArrayOutputStream line = new ByteArrayOutputStream();
            ByteBuffer input = ByteBuffer.allocate(4_096);
            int totalBytes = 0;
            while (totalBytes < MAX_RESPONSE_BYTES) {
                int read = channel.read(input);
                if (read < 0) {
                    break;
                }
                totalBytes += read;
                input.flip();
                while (input.hasRemaining()) {
                    byte value = input.get();
                    if (value == '\n') {
                        JsonObject response = matchResponse(line.toString(StandardCharsets.UTF_8), requestId);
                        if (response != null) {
                            return response;
                        }
                        line.reset();
                    } else {
                        line.write(value);
                    }
                }
                input.clear();
            }
            throw new ExternalProcessException("The mpv IPC response was not received or was too large.");
        } catch (IOException exception) {
            throw new ExternalProcessException("The mpv Unix IPC socket connection could not be established.", exception);
        } finally {
            if (channel != null) {
                activeConnection.compareAndSet(channel, null);
                closeQuietly(channel);
            }
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Timeout/normal teardown may race; either way the caller is already being released.
        }
    }

    private static void registerConnection(
            Closeable connection,
            AtomicReference<Closeable> activeConnection,
            AtomicBoolean requestExpired
    ) throws ExternalProcessException {
        activeConnection.set(connection);
        if (!requestExpired.get()) {
            return;
        }
        if (activeConnection.compareAndSet(connection, null)) {
            closeQuietly(connection);
        }
        throw new ExternalProcessException("The mpv IPC request connected after its deadline.");
    }
}
