package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** 取反：-input。 */
public final class Invert implements Noise {

    public static final Codec<Invert> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("input").forGetter(Invert::input)
    ).apply(i, Invert::new));

    private final Noise input;

    public Invert(Noise input) {
        this.input = input;
    }

    public Noise input() {
        return input;
    }

    @Override
    public double compute(double x, double z) {
        return -input.compute(x, z);
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Invert(input.mapAll(visitor)));
    }
}
