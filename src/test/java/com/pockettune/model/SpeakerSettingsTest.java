package com.pockettune.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SpeakerSettingsTest {
    @Test
    void clampsFiniteValuesToSupportedRanges() {
        SpeakerSettings settings = new SpeakerSettings(
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

        assertEquals(100.0D, settings.volumePercent());
        assertEquals(128, settings.rangeBlocks());
        assertEquals(-12.0D, settings.bassDb());
        assertEquals(12.0D, settings.midDb());
        assertEquals(1.5D, settings.trebleDb());
    }

    @Test
    void replacesNonFiniteNetworkValuesWithSafeDefaults() {
        SpeakerSettings settings = new SpeakerSettings(
                Double.NaN,
                32,
                null,
                false,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.NaN,
                false,
                null
        );

        assertEquals(SpeakerSettings.DEFAULT_VOLUME, settings.volumePercent());
        assertEquals(SpeakerSettings.DEFAULT_EQ_DB, settings.bassDb());
        assertEquals(SpeakerSettings.DEFAULT_EQ_DB, settings.midDb());
        assertEquals(SpeakerSettings.DEFAULT_EQ_DB, settings.trebleDb());
        assertEquals(SpeakerSettings.FadeType.QUADRATIC, settings.fadeType());
        assertEquals(SpeakerSettings.RepeatMode.ALL, settings.repeatMode());
    }
}
