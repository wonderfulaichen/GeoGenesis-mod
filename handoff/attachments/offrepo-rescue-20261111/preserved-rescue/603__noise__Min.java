package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** 取小：min(a, b)。 */
public final class Min implements Noise {

    public static final Codec<Min> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("a").forGetter(Min::a),
            Noises.DIRECT_CODEC.fieldOf("b").forGetter(Min::b)
    ).apply(i, Min::new));

    private final Noise a;
    private final Noise b;

    public Min(Noise a, Noise b) {
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
        return Math.min(a.compute(x, z), b.compute(x, z));
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Min(a.mapAll(visitor), b.mapAll(visitor)));
    }
}
