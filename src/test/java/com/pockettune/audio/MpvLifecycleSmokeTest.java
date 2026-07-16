package com.pockettune.audio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class MpvLifecycleSmokeTest {
    private static final String TEST_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final int REENTRY_CYCLES = 3;

    private MpvLifecycleSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path mpvExecutable = ExternalToolLocator.findMpv()
                .orElseThrow(() -> new IllegalStateException("mpv bulunamadı."));
        if (!mpvExecutable.getFileName().toString().equalsIgnoreCase("mpv.exe")) {
            throw new IllegalStateException("Doğrudan mpv.exe kullanılmıyor: " + mpvExecutable);
        }

        Path runtimeDirectory = Files.createTempDirectory("pockettune-lifecycle-");
        MpvController controller = null;
        try {
            for (int cycle = 1; cycle <= REENTRY_CYCLES; cycle++) {
                String streamUrl = new YtDlpResolver().resolveAudioStream(TEST_URL);
                double initialPosition = 15.0D + cycle;
                controller = MpvController.startPrepared(
                        streamUrl,
                        runtimeDirectory,
                        0.0D,
                        false,
                        true,
                        initialPosition,
                        false
                );
                if (!controller.isAlive() || MpvProcessRegistry.activeControllerCount() != 1) {
                    throw new IllegalStateException("Döngü " + cycle + ": mpv controller registry'ye doğru kaydolmadı.");
                }

                double firstPosition = controller.getTimeSeconds();
                Thread.sleep(350L);
                double secondPosition = controller.getTimeSeconds();
                if (firstPosition < initialPosition - 1.0D || secondPosition - firstPosition < 0.1D) {
                    throw new IllegalStateException(
                            "Döngü " + cycle + ": hazırlanmış mpv konumu monoton ilerlemedi: "
                                    + firstPosition + " -> " + secondPosition
                    );
                }

                controller.setPaused(true);
                controller.setEqualizer(3.0D, -1.5D, 2.0D);
                controller.setVolume(25.0D);
                controller.setEqualizer(0.0D, 0.0D, 0.0D);
                controller.setPaused(false);

                long previousEpoch = MpvProcessRegistry.currentSessionEpoch();
                MpvProcessRegistry.invalidateAndTerminateAll();
                if (MpvProcessRegistry.currentSessionEpoch() <= previousEpoch) {
                    throw new IllegalStateException("Döngü " + cycle + ": session epoch ilerlemedi.");
                }
                if (controller.isAlive() || MpvProcessRegistry.activeControllerCount() != 0) {
                    throw new IllegalStateException(
                            "Döngü " + cycle + ": mpv controller kapanıştan sonra çalışmaya devam ediyor."
                    );
                }
                controller = null;
            }
        } finally {
            if (controller != null) {
                controller.terminateImmediately();
            }
            MpvProcessRegistry.invalidateAndTerminateAll();
            if (Files.exists(runtimeDirectory)) {
                try (var paths = Files.walk(runtimeDirectory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        }
    }
}
