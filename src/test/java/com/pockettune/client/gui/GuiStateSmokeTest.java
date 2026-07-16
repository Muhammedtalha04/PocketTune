package com.pockettune.client.gui;

import com.pockettune.model.SpeakerSettings;
import com.pockettune.model.TrackMetadata;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class GuiStateSmokeTest {
    private GuiStateSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        TrackMetadata thumbnailTrack = new TrackMetadata(
                "dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", 213_000L);

        SpeakerSettings clamped = new SpeakerSettings(
                150.0D,
                512,
                SpeakerSettings.FadeType.LOGARITHMIC,
                true,
                -50.0D,
                50.0D,
                1.5D,
                true,
                SpeakerSettings.RepeatMode.ONE
        );
        if (clamped.volumePercent() != 100.0D
                || clamped.rangeBlocks() != 128
                || clamped.bassDb() != -12.0D
                || clamped.midDb() != 12.0D) {
            throw new IllegalStateException("GUI ayar sınırlandırma testi başarısız: " + clamped);
        }

        verifyThumbnailDecode(thumbnailTrack);
    }

    // NativeImage 1.21.4'te yalnız PNG okuduğu için kapaklar ImageIO ile çözülür;
    // gerçek YouTube kapağının indirilip JPEG olarak çözülebildiğini doğrular.
    private static void verifyThumbnailDecode(TrackMetadata track) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        for (String thumbnailUrl : track.thumbnailUrls()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(thumbnailUrl))
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length == 0) {
                continue;
            }
            BufferedImage cover = ImageIO.read(new ByteArrayInputStream(response.body()));
            if (cover != null && cover.getWidth() >= 480 && cover.getHeight() > 0) {
                return;
            }
        }
        throw new IllegalStateException("Yüksek kaliteli YouTube kapağı indirilemedi: " + track.videoId());
    }
}
