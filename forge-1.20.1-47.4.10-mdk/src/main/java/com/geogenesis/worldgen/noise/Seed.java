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

    /**
     * ★ 2026-09-15：<b>3D</b> 梯度索引（供 {@code Simplex3} 使用）。
     *
     * <p>经典 Gustavson 3D simplex 的形式：{@code perm[ii + perm[jj + perm[kk]]]}
     * —— 三级嵌套保证三个坐标都参与哈希，且对任意 (x,y,z) 都有良好分布。</p>
     *
     * <p>索引安全性：{@code perm} 长度为 512，而每次加法的被加数都先 {@code & 255}
     * （∈[0,255]）⇒ 索引最大 255+255=510 &lt; 512，不会越界。</p>
     *
     * @return [0,255) 的洗牌值（调用方再 {@code % 12} 取 12 个 3D 梯度）
     */
    public int gradIndex3(int x, int y, int z) {
        int a = perm[z & 255];
        int b = perm[(y & 255) + a];
        return perm[(x & 255) + b];
    }
}
