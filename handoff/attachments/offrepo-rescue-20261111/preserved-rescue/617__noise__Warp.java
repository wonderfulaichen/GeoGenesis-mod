package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 域扭曲（domain warp）：用两个噪声偏移采样坐标，制造蜿蜒、自然的山脉/海岸。
 * output = input.compute(x + wx*amount, z + wz*amount)
 */
public final class Warp implements Noise {

    public static final Codec<Warp> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("input").forGetter(Warp::input),
            Noises.DIRECT_CODEC.fieldOf("warp_x").forGetter(Warp::warpX),
            Noises.DIRECT_CODEC.fieldOf("warp_z").forGetter(Warp::warpZ),
            Codec.DOUBLE.fieldOf("amount").forGetter(Warp::amount)
    ).apply(i, Warp::new));

    private final Noise input;
    private final Noise warpX;
    private final Noise warpZ;
    private final double amount;

    public Warp(Noise input, Noise warpX, Noise warpZ, double amount) {
        this.input = input;
        this.warpX = warpX;
        this.warpZ = warpZ;
        this.amount = amount;
    }

    public Noise input() {
        return input;
    }

    public Noise warpX() {
        return warpX;
    }

    public Noise warpZ() {
        return warpZ;
    }

    public double amount() {
        return amount;
    }

    @Override
    public double compute(double x, double z) {
        double dx = warpX.compute(x, z) * amount;
        double dz = warpZ.compute(x, z) * amount;
        return input.compute(x + dx, z + dz);
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Warp(
                input.mapAll(visitor), warpX.mapAll(visitor), warpZ.mapAll(visitor), amount));
    }
}
