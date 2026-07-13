package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** 白噪声：每个整数格点返回由种子确定的 [-1,1] 随机值（格点间不插值）。 */
public final class White implements Noise, Seeded {

    public static final Codec<White> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("seed", 0).forGetter(White::seedOffset)
    ).apply(i, White::new));

    private final int seedOffset;
    private long worldSeed;
    private boolean seeded;

    public White(int seedOffset) {
        this.seedOffset = seedOffset;
    }

    public int seedOffset() {
        return seedOffset;
    }

    @Override
    public void seed(long seed, int level) {
        this.worldSeed = seed ^ ((long) seedOffset << 16) ^ ((long) level * 0x1234567L);
        this.seeded = true;
    }

    @Override
    public boolean isSeeded() {
        return seeded;
    }

    @Override
    public double compute(double x, double z) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        return NoiseUtil.hash2(ix + (int) (worldSeed & 0xffff), iz + (int) ((worldSeed >>> 16) & 0xffff));
    }
}
