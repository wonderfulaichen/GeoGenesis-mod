package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 频率变换：采样前把坐标乘以 frequency。
 * 因为基础噪声（Simplex 等）无内建频率，必须用本节点控制地形尺度。
 */
public final class Frequency implements Noise {

    public static final Codec<Frequency> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("input").forGetter(Frequency::input),
            Codec.DOUBLE.fieldOf("frequency").forGetter(Frequency::frequency)
    ).apply(i, Frequency::new));

    private final Noise input;
    private final double frequency;

    public Frequency(Noise input, double frequency) {
        this.input = input;
        this.frequency = frequency;
    }

    public Noise input() {
        return input;
    }

    public double frequency() {
        return frequency;
    }

    @Override
    public double compute(double x, double z) {
        return input.compute(x * frequency, z * frequency);
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Frequency(input.mapAll(visitor), frequency));
    }
}
