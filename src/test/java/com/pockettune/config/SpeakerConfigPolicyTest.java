package com.pockettune.config;

import com.pockettune.model.SpeakerSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class SpeakerConfigPolicyTest {
    @Test
    void configuredDefaultsUseVolumeAndNeverExceedMaximumRange() {
        SpeakerSettings settings = SpeakerConfigPolicy.defaults(65.0D, 24);

        assertEquals(65.0D, settings.volumePercent());
        assertEquals(24, settings.rangeBlocks());
    }

    @Test
    void malformedDefaultsFallBackToHardSafetyBounds() {
        SpeakerSettings settings = SpeakerConfigPolicy.defaults(Double.NaN, Integer.MAX_VALUE);

        assertEquals(SpeakerSettings.DEFAULT_VOLUME, settings.volumePercent());
        assertEquals(SpeakerSettings.DEFAULT.rangeBlocks(), settings.rangeBlocks());
        assertEquals(SpeakerSettings.MAX_RANGE, SpeakerConfigPolicy.maximumRange(Integer.MAX_VALUE));
        assertEquals(SpeakerSettings.MIN_RANGE, SpeakerConfigPolicy.maximumRange(Integer.MIN_VALUE));
    }

    @Test
    void existingSettingsAreClampedWithoutChangingOtherAudioValues() {
        SpeakerSettings original = new SpeakerSettings(
                73.0D,
                96,
                SpeakerSettings.FadeType.LOGARITHMIC,
                true,
                2.0D,
                -1.0D,
                3.0D,
                true,
                SpeakerSettings.RepeatMode.ONE
        );

        SpeakerSettings clamped = SpeakerConfigPolicy.constrain(original, 48);

        assertEquals(48, clamped.rangeBlocks());
        assertEquals(original.volumePercent(), clamped.volumePercent());
        assertEquals(original.fadeType(), clamped.fadeType());
        assertEquals(original.wallOcclusion(), clamped.wallOcclusion());
        assertEquals(original.repeatMode(), clamped.repeatMode());
        assertSame(original, SpeakerConfigPolicy.constrain(original, 128));
    }
}
