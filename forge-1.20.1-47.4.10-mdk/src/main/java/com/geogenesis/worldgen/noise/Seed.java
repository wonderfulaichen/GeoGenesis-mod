package com.geogenesis.worldgen.noise;

/**
 * 由世界种子派生的确定性置换表（0..255 洗牌），用于梯度噪声的坐标扰动。
 * 与 Minecraft 原版 FastNoise 的 permutation 思路一致，但完全独立实现。
 */
public final class Seed {

    private final int[] perm;

    public Seed(long seed) {
        this.perm = new int[512];
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            p[i] = i;
        }
        // Fisher-Yates 洗牌，种子确定
        for (int i = 255; i > 0; i--) {
            int j = NoiseUtil.hashInt(seed, i) & 0xff;
            j = j % (i + 1);
            int tmp = p[i];
            p[i] = p[j];
            p[j] = tmp;
        }
        for (int i = 0; i < 512; i++) {
            this.perm[i] = p[i & 255];
        }
    }

    public int at(int x, int y) {
        return perm[(perm[x & 255] + y) & 511];
    }

    /** 返回 [0,255) 的洗牌值（用于梯度索引）。 */
    public int gradIndex(int x, int y) {
        return at(x, y) & 255;
    }
}
