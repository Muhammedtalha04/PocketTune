package com.pockettune.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.pockettune.PocketTune;
import com.pockettune.model.TrackMetadata;
import com.pockettune.config.PocketTuneClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ThumbnailCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_IMAGE_BYTES = 2_000_000;
    private static final int MAX_IMAGE_DIMENSION = 2_048;
    private static final long MAX_IMAGE_PIXELS = 4_194_304L;
    private static final ThumbnailCache INSTANCE = new ThumbnailCache();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);
    private long generation;

    private ThumbnailCache() {
    }

    public static ThumbnailCache instance() {
        return INSTANCE;
    }

    public synchronized Entry get(TrackMetadata track) {
        Entry existing = entries.get(track.videoId());
        if (existing != null) {
            return existing;
        }
        Entry loading = new Entry(null, 0, 0, true);
        entries.put(track.videoId(), loading);
        evictIfNeeded();
        download(track);
        return loading;
    }

    private void download(TrackMetadata track) {
        long requestGeneration;
        synchronized (this) {
            requestGeneration = generation;
        }
        java.util.List<String> allCandidates = track.thumbnailUrls();
        java.util.List<String> candidates = switch (PocketTuneClientConfig.THUMBNAIL_QUALITY.get()) {
            case MAXIMUM -> allCandidates;
            case HIGH -> allCandidates.subList(1, allCandidates.size());
            case BALANCED -> allCandidates.subList(2, allCandidates.size());
        };
        downloadCandidate(track, candidates, 0, requestGeneration);
    }

    private void downloadCandidate(
            TrackMetadata track,
            java.util.List<String> candidates,
            int candidateIndex,
            long requestGeneration
    ) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(candidates.get(candidateIndex)))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "PocketTune/0.7.0")
                .GET()
                .build();
        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    try (InputStream input = response.body()) {
                        long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                        if (response.statusCode() != 200 || declaredLength > MAX_IMAGE_BYTES) {
                            throw new IllegalStateException("Thumbnail HTTP " + response.statusCode());
                        }
                        byte[] bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
                        if (bytes.length > MAX_IMAGE_BYTES) {
                            throw new IllegalStateException("Thumbnail HTTP " + response.statusCode());
                        }
                        return bytes;
                    } catch (java.io.IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .thenApply(bytes -> {
                    try {
                        return decodeImage(bytes);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .thenAccept(image -> Minecraft.getInstance().execute(
                        () -> register(track.videoId(), image, requestGeneration)))
                .exceptionally(exception -> {
                    synchronized (this) {
                        if (generation != requestGeneration) {
                            return null;
                        }
                    }
                    if (candidateIndex + 1 < candidates.size()) {
                        downloadCandidate(track, candidates, candidateIndex + 1, requestGeneration);
                        return null;
                    }
                    synchronized (this) {
                        entries.put(track.videoId(), new Entry(null, 0, 0, false));
                    }
                    LOGGER.debug("PocketTune thumbnail could not be loaded for {}", track.videoId());
                    return null;
                });
    }

    private static NativeImage decodeImage(byte[] bytes) throws IOException {
        // NativeImage.read 1.21.4'te yalnız PNG kabul eder (PngInfo.validateHeader);
        // YouTube kapakları JPEG geldiği için diğer formatlar ImageIO ile çözülür.
        if (bytes.length >= 4
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G') {
            NativeImage image = NativeImage.read(bytes);
            if (!isSafeDimensions(image.getWidth(), image.getHeight())) {
                image.close();
                throw new IOException("Küçük resim boyut sınırını aşıyor");
            }
            return image;
        }
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        if (decoded == null || !isSafeDimensions(decoded.getWidth(), decoded.getHeight())) {
            throw new IOException("Desteklenmeyen küçük resim formatı");
        }
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, decoded.getWidth(), decoded.getHeight(), false);
        for (int y = 0; y < decoded.getHeight(); y++) {
            for (int x = 0; x < decoded.getWidth(); x++) {
                image.setPixel(x, y, decoded.getRGB(x, y));
            }
        }
        return image;
    }

    private static boolean isSafeDimensions(int width, int height) {
        return width > 0
                && height > 0
                && width <= MAX_IMAGE_DIMENSION
                && height <= MAX_IMAGE_DIMENSION
                && (long) width * height <= MAX_IMAGE_PIXELS;
    }

    private synchronized void register(String videoId, NativeImage image, long requestGeneration) {
        if (generation != requestGeneration) {
            image.close();
            return;
        }
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                PocketTune.MOD_ID,
                "thumbnail/" + UUID.nameUUIDFromBytes(videoId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
        Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
        entries.put(videoId, new Entry(location, image.getWidth(), image.getHeight(), false));
        evictIfNeeded();
    }

    private void evictIfNeeded() {
        int maximumEntries = PocketTuneClientConfig.THUMBNAIL_CACHE_ENTRIES.get();
        while (entries.size() > maximumEntries) {
            Map.Entry<String, Entry> eldest = entries.entrySet().iterator().next();
            entries.remove(eldest.getKey());
            if (eldest.getValue().location() != null) {
                Minecraft.getInstance().getTextureManager().release(eldest.getValue().location());
            }
        }
    }

    public synchronized void clear() {
        generation++;
        for (Entry entry : entries.values()) {
            if (entry.location() != null) {
                Minecraft.getInstance().getTextureManager().release(entry.location());
            }
        }
        entries.clear();
    }

    public record Entry(ResourceLocation location, int width, int height, boolean loading) {
        /** Renders a square, center-cropped cover without stretching the 16:9 YouTube thumbnail. */
        public void renderCover(net.minecraft.client.gui.GuiGraphics graphics, int x, int y, int size) {
            if (location == null || this.width <= 0 || this.height <= 0) {
                graphics.fill(x, y, x + size, y + size, 0xFF1B211D);
                return;
            }
            int sourceSize = Math.min(this.width, this.height);
            float sourceX = (this.width - sourceSize) / 2.0F;
            float sourceY = (this.height - sourceSize) / 2.0F;
            graphics.blit(
                    RenderType::guiTextured,
                    location,
                    x,
                    y,
                    sourceX,
                    sourceY,
                    size,
                    size,
                    sourceSize,
                    sourceSize,
                    this.width,
                    this.height
            );
        }

        public void render(net.minecraft.client.gui.GuiGraphics graphics, int x, int y, int width, int height) {
            if (location == null) {
                graphics.fill(x, y, x + width, y + height, 0xFF242424);
                return;
            }
            // blit(x, y, u, v, ekranBoyutu, örneklenenBölge, dokuBoyutu): görüntünün tamamı hedef alana ölçeklenir.
            graphics.blit(
                    RenderType::guiTextured,
                    location,
                    x,
                    y,
                    0.0F,
                    0.0F,
                    width,
                    height,
                    this.width,
                    this.height,
                    this.width,
                    this.height
            );
        }
    }
}
