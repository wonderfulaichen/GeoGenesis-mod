package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** 增益：input * gain，用于放大/缩小某层噪声振幅。 */
public final class Boost implements Noise {

    public static final Codec<Boost> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("input").forGetter(Boost::input),
            Codec.DOUBLE.fieldOf("gain").forGetter(Boost::gain)
    ).apply(i, Boost::new));

    private final Noise input;
    private final double gain;

    public Boost(Noise input, double gain) {
        this.input = input;
        this.gain = gain;
    }

    public Noise input() {
        return input;
    }

    public double gain() {
        return gain;
    }

    @Override
    public double compute(double x, double z) {
        return input.compute(x, z) * gain;
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Boost(input.mapAll(visitor), gain));
    }
}
