package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** 区间钳制：clamp(input, min, max)。 */
public final class Clamp implements Noise {

    public static final Codec<Clamp> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("input").forGetter(Clamp::input),
            Codec.DOUBLE.fieldOf("min").forGetter(Clamp::min),
            Codec.DOUBLE.fieldOf("max").forGetter(Clamp::max)
    ).apply(i, Clamp::new));

    private final Noise input;
    private final double min;
    private final double max;

    public Clamp(Noise input, double min, double max) {
        this.input = input;
        this.min = min;
        this.max = max;
    }

    public Noise input() {
        return input;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    @Override
    public double compute(double x, double z) {
        return NoiseUtil.clamp(input.compute(x, z), min, max);
    }

    @Override
    public double minValue() {
        return min;
    }

    @Override
    public double maxValue() {
        return max;
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Clamp(input.mapAll(visitor), min, max));
    }
}
