package com.pockettune.audio;

import com.pockettune.model.SpeakerSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SpatialAudioMathTest {
    @Test
    void quadraticFadeMatchesFullMiddleAndRangeBoundaries() {
        SpeakerSettings settings = new SpeakerSettings(
                100.0D,
                20,
                SpeakerSettings.FadeType.QUADRATIC,
                true,
                0.0D,
                0.0D,
                0.0D,
                false,
                SpeakerSettings.RepeatMode.ALL
        );

        assertEquals(100.0D, DistanceAttenuation.volumeForDistance(4.0D, settings, 4.0D));
        assertEquals(25.0D, DistanceAttenuation.volumeForDistance(12.0D, settings, 4.0D), 0.0001D);
        assertEquals(0.0D, DistanceAttenuation.volumeForDistance(20.0D, settings, 4.0D));
    }

    @Test
    void fullVolumeDistanceCannotCreateInvalidDivision() {
        SpeakerSettings settings = new SpeakerSettings(
                80.0D,
                4,
                SpeakerSettings.FadeType.LINEAR,
                false,
                0.0D,
                0.0D,
                0.0D,
                false,
                SpeakerSettings.RepeatMode.ALL
        );

        assertEquals(80.0D, DistanceAttenuation.volumeForDistance(3.9D, settings, 16.0D));
        assertEquals(0.0D, DistanceAttenuation.volumeForDistance(4.0D, settings, 16.0D));
    }
}
