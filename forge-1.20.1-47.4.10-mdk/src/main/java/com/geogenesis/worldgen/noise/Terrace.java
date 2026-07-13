package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 梯田：把 input 量化成 steps 级台阶，strength 控制台阶边缘的平滑程度
 * （0=完全平滑，1=硬台阶）。适合高原/台地地貌。
 */
public final class Terrace implements Noise {

    public static final Codec<Terrace> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("input").forGetter(Terrace::input),
            Codec.INT.fieldOf("steps").forGetter(Terrace::steps),
            Codec.DOUBLE.fieldOf("strength").forGetter(Terrace::strength)
    ).apply(i, Terrace::new));

    private final Noise input;
    private final int steps;
    private final double strength;

    public Terrace(Noise input, int steps, double strength) {
        this.input = input;
        this.steps = Math.max(1, steps);
        this.strength = strength;
    }

    public Noise input() {
        return input;
    }

    public int steps() {
        return steps;
    }

    public double strength() {
        return strength;
    }

    @Override
    public double compute(double x, double z) {
        double f = input.compute(x, z) * 0.5 + 0.5; // [0,1]
        double scaled = f * steps;
        double lower = Math.floor(scaled);
        double upper = lower + 1.0;
        double blend = NoiseUtil.smoother(scaled - lower);
        double stepped = (lower + blend) / steps;
        double mixed = NoiseUtil.lerp(scaled / steps, stepped, strength);
        return mixed * 2.0 - 1.0;
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Terrace(input.mapAll(visitor), steps, strength));
    }
}
