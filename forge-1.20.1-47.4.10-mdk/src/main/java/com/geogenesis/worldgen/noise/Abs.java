package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** 绝对值：|input|。 */
public final class Abs implements Noise {

    public static final Codec<Abs> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("input").forGetter(Abs::input)
    ).apply(i, Abs::new));

    private final Noise input;

    public Abs(Noise input) {
        this.input = input;
    }

    public Noise input() {
        return input;
    }

    @Override
    public double compute(double x, double z) {
        return Math.abs(input.compute(x, z));
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Abs(input.mapAll(visitor)));
    }
}
