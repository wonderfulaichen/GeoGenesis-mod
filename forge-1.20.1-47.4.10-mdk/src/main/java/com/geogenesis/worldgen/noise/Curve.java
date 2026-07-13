package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * S 形曲线：把 input 从 [-1,1] 归一化到 [0,1] 后施加 quintic smootherstep，
 * 再回到 [-1,1]。用于柔化/锐化地形过渡。
 */
public final class Curve implements Noise {

    public static final Codec<Curve> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("input").forGetter(Curve::input)
    ).apply(i, Curve::new));

    private final Noise input;

    public Curve(Noise input) {
        this.input = input;
    }

    public Noise input() {
        return input;
    }

    @Override
    public double compute(double x, double z) {
        double v = input.compute(x, z);
        double t = NoiseUtil.smoother(NoiseUtil.saturate((v + 1.0) * 0.5));
        return t * 2.0 - 1.0;
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Curve(input.mapAll(visitor)));
    }
}
