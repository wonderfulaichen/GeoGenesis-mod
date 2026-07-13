package com.geogenesis.worldgen.noise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 2D Simplex 噪声（Stefan Gustavson 经典实现，8-梯度），返回约 [-1,1]。
 * 无内建 frequency —— 调用方必须 {@code compute(x*freq, z*freq)}。
 * 置换表由世界种子确定（Seeded）。
 */
public final class Simplex implements Noise, Seeded {

    public static final Codec<Simplex> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("seed", 0).forGetter(Simplex::seedOffset)
    ).apply(i, Simplex::new));

    private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
    private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;

    private static final double[][] GRAD2 = {
            {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private final int seedOffset;
    private Seed seed;
    private boolean seeded;

    public Simplex(int seedOffset) {
        this.seedOffset = seedOffset;
    }

    public int seedOffset() {
        return seedOffset;
    }

    @Override
    public void seed(long worldSeed, int level) {
        long s = worldSeed ^ ((long) seedOffset << 16) ^ ((long) level * 0x9e3779b9L);
        this.seed = new Seed(s);
        this.seeded = true;
    }

    @Override
    public boolean isSeeded() {
        return seeded;
    }

    @Override
    public double compute(double x, double z) {
        // 加固定偏移，避免 world 整数坐标恰好落在 Simplex 网格节点上输出 0，
        // 从而消除规则波节/波腹结构。
        x += 0.5;
        z += 0.5;
        double s = (x + z) * F2;
        int i = (int) Math.floor(x + s);
        int j = (int) Math.floor(z + s);
        double t = (i + j) * G2;
        double x0 = x - (i - t);
        double y0 = z - (j - t);

        int i1, j1;
        if (x0 > y0) {
            i1 = 1;
            j1 = 0;
        } else {
            i1 = 0;
            j1 = 1;
        }

        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2;
        double y2 = y0 - 1.0 + 2.0 * G2;

        int ii = i & 255;
        int jj = j & 255;

        double n0 = corner(seed.gradIndex(ii, jj), x0, y0);
        double n1 = corner(seed.gradIndex(ii + i1, jj + j1), x1, y1);
        double n2 = corner(seed.gradIndex(ii + 1, jj + 1), x2, y2);

        // 缩放至约 [-1,1]
        return 70.0 * (n0 + n1 + n2);
    }

    private double corner(int hash, double x, double y) {
        double t = 0.5 - x * x - y * y;
        if (t < 0.0) {
            return 0.0;
        }
        t *= t;
        double[] g = GRAD2[hash & 7];
        return t * t * (g[0] * x + g[1] * y);
    }
}
