package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** 幂次：pow(|input|, power)（非负，用于锐化山脊/盆地）。 */
public final class Power implements Noise {

    public static final Codec<Power> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("input").forGetter(Power::input),
            Codec.DOUBLE.fieldOf("power").forGetter(Power::power)
    ).apply(i, Power::new));

    private final Noise input;
    private final double power;

    public Power(Noise input, double power) {
        this.input = input;
        this.power = power;
    }

    public Noise input() {
        return input;
    }

    public double power() {
        return power;
    }

    @Override
    public double compute(double x, double z) {
        double v = Math.abs(input.compute(x, z));
        return Math.pow(v, power);
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Power(input.mapAll(visitor), power));
    }
}
