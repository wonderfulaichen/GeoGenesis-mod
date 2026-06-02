package com.erosiontest;

public class ImprovedNoise {
    private final int[] p = new int[512];
    private final long seed;

    public ImprovedNoise(long seed) {
        this.seed = seed;
        long s = seed;
        int[] perm = new int[256];
        for (int i = 0; i < 256; i++) perm[i] = i;
        for (int i = 255; i > 0; i--) {
            s = s * 0x5DEECE66DL + 0xBL;
            int j = (int)((s >>> 16) % (i + 1));
            int tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp;
        }
        for (int i = 0; i < 512; i++) p[i] = perm[i & 255];
    }

    public double noise(double x, double y, double z) {
        int X = (int)Math.floor(x) & 255, Y = (int)Math.floor(y) & 255, Z = (int)Math.floor(z) & 255;
        x -= Math.floor(x); y -= Math.floor(y); z -= Math.floor(z);
        double u = fade(x), v = fade(y), w = fade(z);
        int A = p[X] + Y, AA = p[A] + Z, AB = p[A + 1] + Z;
        int B = p[X + 1] + Y, BA = p[B] + Z, BB = p[B + 1] + Z;
        return lerp(w,
            lerp(v, lerp(u, grad(p[AA], x, y, z), grad(p[BA], x - 1, y, z)),
                    lerp(u, grad(p[AB], x, y - 1, z), grad(p[BB], x - 1, y - 1, z))),
            lerp(v, lerp(u, grad(p[AA + 1], x, y, z - 1), grad(p[BA + 1], x - 1, y, z - 1)),
                    lerp(u, grad(p[AB + 1], x, y - 1, z - 1), grad(p[BB + 1], x - 1, y - 1, z - 1))));
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double t, double a, double b) { return a + t * (b - a); }
    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y, v = h < 4 ? y : h == 12 || h == 14 ? x : z;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
