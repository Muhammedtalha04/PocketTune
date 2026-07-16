package com.pockettune.client.gui.widget;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

public final class PocketTuneSlider extends AbstractSliderButton {
    private static final long SEND_INTERVAL_NANOS = 50_000_000L;

    private final double minimum;
    private final double maximum;
    private final DoubleFunction<String> formatter;
    private final DoubleConsumer consumer;
    private long lastSentNanos;

    public PocketTuneSlider(
            int x,
            int y,
            int width,
            double minimum,
            double maximum,
            double current,
            DoubleFunction<String> formatter,
            DoubleConsumer consumer
    ) {
        super(x, y, width, 20, Component.empty(), normalize(current, minimum, maximum));
        this.minimum = minimum;
        this.maximum = maximum;
        this.formatter = formatter;
        this.consumer = consumer;
        updateMessage();
    }

    public void setCurrentValue(double current) {
        value = normalize(current, minimum, maximum);
        updateMessage();
    }

    public double currentValue() {
        return minimum + value * (maximum - minimum);
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(formatter.apply(currentValue())));
    }

    @Override
    protected void applyValue() {
        long now = System.nanoTime();
        if (now - lastSentNanos >= SEND_INTERVAL_NANOS) {
            lastSentNanos = now;
            consumer.accept(currentValue());
        }
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        consumer.accept(currentValue());
        super.onRelease(mouseX, mouseY);
    }

    private static double normalize(double current, double minimum, double maximum) {
        if (!Double.isFinite(current)
                || !Double.isFinite(minimum)
                || !Double.isFinite(maximum)
                || maximum <= minimum) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, (current - minimum) / (maximum - minimum)));
    }
}
