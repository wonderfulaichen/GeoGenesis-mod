package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** 线性重映射：把 input 从 [inMin,inMax] 映射到 [outMin,outMax]（区间外 clamp）。 */
public final class Map implements Noise {

    public static final Codec<Map> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("input").forGetter(Map::input),
            Codec.DOUBLE.fieldOf("in_min").forGetter(Map::inMin),
            Codec.DOUBLE.fieldOf("in_max").forGetter(Map::inMax),
            Codec.DOUBLE.fieldOf("out_min").forGetter(Map::outMin),
            Codec.DOUBLE.fieldOf("out_max").forGetter(Map::outMax)
    ).apply(i, Map::new));

    private final Noise input;
    private final double inMin;
    private final double inMax;
    private final double outMin;
    private final double outMax;

    public Map(Noise input, double inMin, double inMax, double outMin, double outMax) {
        this.input = input;
        this.inMin = inMin;
        this.inMax = inMax;
        this.outMin = outMin;
        this.outMax = outMax;
    }

    public Noise input() {
        return input;
    }

    public double inMin() {
        return inMin;
    }

    public double inMax() {
        return inMax;
    }

    public double outMin() {
        return outMin;
    }

    public double outMax() {
        return outMax;
    }

    @Override
    public double compute(double x, double z) {
        double v = input.compute(x, z);
        double t = NoiseUtil.saturate((v - inMin) / (inMax - inMin));
        return outMin + t * (outMax - outMin);
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Map(input.mapAll(visitor), inMin, inMax, outMin, outMax));
    }
}
