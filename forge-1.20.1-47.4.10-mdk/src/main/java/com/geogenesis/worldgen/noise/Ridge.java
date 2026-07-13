package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 脊线噪声：{@code 1 - |child|}，把 [-1,1] 的噪声翻成 [0,1] 的山脊。
 * 多次叠加可生成尖锐山脉。需要 Seeded 子节点已被播种。
 */
public final class Ridge implements Noise {

    public static final Codec<Ridge> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("input").forGetter(Ridge::input),
            Codec.DOUBLE.optionalFieldOf("power", 1.0).forGetter(Ridge::power)
    ).apply(i, Ridge::new));

    private final Noise input;
    private final double power;

    public Ridge(Noise input, double power) {
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
        double v = 1.0 - Math.abs(input.compute(x, z));
        if (power != 1.0) {
            v = Math.pow(NoiseUtil.clamp(v, 0.0, 1.0), power);
        }
        return v;
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public double maxValue() {
        return 1.0;
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Ridge(input.mapAll(visitor), power));
    }
}
