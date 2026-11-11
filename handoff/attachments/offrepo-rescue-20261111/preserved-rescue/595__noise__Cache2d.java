package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * 2D 采样缓存：按整数坐标记忆 input 的输出，避免同一坐标被多层噪声重复计算。
 * 内部使用同步 Map，可安全用于多线程区块生成。
 */
public final class Cache2d implements Noise {

    public static final Codec<Cache2d> CODEC = RecordCodecBuilder.create(i -> i.group(
            Noises.DIRECT_CODEC.fieldOf("input").forGetter(Cache2d::input)
    ).apply(i, Cache2d::new));

    private final Noise input;
    private final Map<Long, Double> cache = new HashMap<>();

    public Cache2d(Noise input) {
        this.input = input;
    }

    public Noise input() {
        return input;
    }

    @Override
    public double compute(double x, double z) {
        long key = pack((int) Math.floor(x), (int) Math.floor(z));
        Double v;
        synchronized (cache) {
            v = cache.get(key);
            if (v == null) {
                v = input.compute(x, z);
                cache.put(key, v);
            }
        }
        return v;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    @Override
    public Noise mapAll(Visitor visitor) {
        return visitor.apply(new Cache2d(input.mapAll(visitor)));
    }
}
