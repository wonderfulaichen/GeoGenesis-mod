package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 波浪噪声：{@code 2*|child| - 1}，把 [-1,1] 噪声翻成起伏的 [−1,1] 云团状地形。
 */
public final class Billow implements Noise {

    public static final Codec<Billow> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("input").forGetter(Billow::input)
    ).apply(i, Billow::new));

    private final Noise input;

    public Billow(Noise input) {
        this.input = input;
    }

    public Noise input() {
        return input;
    }

    @Override
    public double compute(double x, double z) {
        return 2.0 * Math.abs(input.compute(x, z)) - 1.0;
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Billow(input.mapAll(visitor)));
    }
}
