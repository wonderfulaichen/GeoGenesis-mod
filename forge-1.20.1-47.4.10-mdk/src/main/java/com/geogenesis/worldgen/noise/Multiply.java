package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** 相乘：a * b。 */
public final class Multiply implements Noise {

    public static final Codec<Multiply> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("a").forGetter(Multiply::a),
            Noises.DIRECT_CODEC.fieldOf("b").forGetter(Multiply::b)
    ).apply(i, Multiply::new));

    private final Noise a;
    private final Noise b;

    public Multiply(Noise a, Noise b) {
        this.a = a;
        this.b = b;
    }

    public Noise a() {
        return a;
    }

    public Noise b() {
        return b;
    }

    @Override
    public double compute(double x, double z) {
        return a.compute(x, z) * b.compute(x, z);
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Multiply(a.mapAll(visitor), b.mapAll(visitor)));
    }
}
