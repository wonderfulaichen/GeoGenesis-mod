package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 按权重噪声在 a、b 之间平滑混合：{@code lerp(a, b, saturate(w))}。
 * 用于海陆过渡、群系边界等。
 */
public final class Blend implements Noise {

    public static final Codec<Blend> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("a").forGetter(Blend::a),
            Noises.DIRECT_CODEC.fieldOf("b").forGetter(Blend::b),
            Noises.DIRECT_CODEC.fieldOf("weight").forGetter(Blend::weight)
    ).apply(i, Blend::new));

    private final Noise a;
    private final Noise b;
    private final Noise weight;

    public Blend(Noise a, Noise b, Noise weight) {
        this.a = a;
        this.b = b;
        this.weight = weight;
    }

    public Noise a() {
        return a;
    }

    public Noise b() {
        return b;
    }

    public Noise weight() {
        return weight;
    }

    @Override
    public double compute(double x, double z) {
        double t = NoiseUtil.saturate(weight.compute(x, z));
        return NoiseUtil.lerp(a.compute(x, z), b.compute(x, z), t);
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Blend(a.mapAll(visitor), b.mapAll(visitor), weight.mapAll(visitor)));
    }
}
