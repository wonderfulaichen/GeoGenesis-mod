package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** 阶梯量化：把 input 量化成 steps 个均匀级别（硬台阶）。 */
public final class Steps implements Noise {

    public static final Codec<Steps> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("input").forGetter(Steps::input),
            Codec.INT.fieldOf("steps").forGetter(Steps::steps)
    ).apply(i, Steps::new));

    private final Noise input;
    private final int steps;

    public Steps(Noise input, int steps) {
        this.input = input;
        this.steps = Math.max(1, steps);
    }

    public Noise input() {
        return input;
    }

    public int steps() {
        return steps;
    }

    @Override
    public double compute(double x, double z) {
        double f = input.compute(x, z) * 0.5 + 0.5;
        double s = Math.round(f * steps) / (double) steps;
        return s * 2.0 - 1.0;
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Steps(input.mapAll(visitor), steps));
    }
}
