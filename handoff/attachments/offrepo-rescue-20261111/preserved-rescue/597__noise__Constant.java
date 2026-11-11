package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** 返回常量的噪声节点（调试/融合用）。 */
public final class Constant implements Noise {

    public static final Codec<Constant> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("value").forGetter(Constant::value)
    ).apply(i, Constant::new));

    private final double value;

    public Constant(double value) {
        this.value = value;
    }

    public double value() {
        return value;
    }

    @Override
    public double compute(double x, double z) {
        return value;
    }

    @Override
    public double minValue() {
        return value;
    }

    @Override
    public double maxValue() {
        return value;
    }
}
