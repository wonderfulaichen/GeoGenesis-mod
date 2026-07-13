package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** 相加：a + b。 */
public final class Add implements Noise {

    public static final Codec<Add> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("a").forGetter(Add::a),
            Noises.DIRECT_CODEC.fieldOf("b").forGetter(Add::b)
    ).apply(i, Add::new));

    private final Noise a;
    private final Noise b;

    public Add(Noise a, Noise b) {
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
        return a.compute(x, z) + b.compute(x, z);
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Add(a.mapAll(visitor), b.mapAll(visitor)));
    }
}
