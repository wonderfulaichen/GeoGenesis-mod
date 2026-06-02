package com.erosiontest;

import java.util.Random;

public class Noise {
    private final ImprovedNoise[] large = new ImprovedNoise[4];
    private final ImprovedNoise[] medium = new ImprovedNoise[4];
    private final ImprovedNoise[] small = new ImprovedNoise[3];
    private final ImprovedNoise continentNoise;
    private final float baseFreq;
    private final int seed;

    public Noise(int seed) {
        this.seed = seed;
        Random rng = new Random(seed);
        baseFreq = 1f;
        continentNoise = new ImprovedNoise(rng.nextLong() ^ 0x9e3779b97f4a7c15L);
        for (int i = 0; i < 4; i++) large[i] = new ImprovedNoise(rng.nextLong());
        for (int i = 0; i < 4; i++) medium[i] = new ImprovedNoise(rng.nextLong());
        for (int i = 0; i < 3; i++) small[i] = new ImprovedNoise(rng.nextLong());
    }

    public float continentRaw(float x, float z) {
        float val = 0, amp = 0.6f, freq = 0.0012f;
        return Math.max(-1f, Math.min(1f,
            (float)continentNoise.noise(x * freq, 3.7f, z * freq) * amp
            + (float)continentNoise.noise(x * freq * 2.1f, 5.2f, z * freq * 2.1f) * amp * 0.5f));
    }

    /** 原版测试工具 terrainBase：域扭曲+加法FBM */
    public float terrainBase(float x, float z) {
        float wx = (float)continentNoise.noise(x * 0.0003, 13.7, z * 0.0003) * 200;
        float wz = (float)continentNoise.noise(x * 0.0003 + 500, 29.1, z * 0.0003 + 500) * 200;
        float px = x + wx, pz = z + wz;

        float l = sampleFBM(large, px * 0.0002f, pz * 0.0002f, 4, 0.5f, 2.0f, 1.0f) * 0.35f;
        float m = sampleFBM(medium, px * 0.0012f, pz * 0.0012f, 3, 0.6f, 2.0f, 1.0f) * 0.15f;
        float s = sampleFBM(small, px * 0.006f, pz * 0.006f, 3, 0.7f, 2.0f, 1.0f) * 0.05f;

        return Math.max(0, Math.min(1, (l + m + s) + 0.5f));
    }

    /** 精确复刻新 NoiseEngine.sampleRidge —— 层级域扭曲分支脊线 */
    public float sampleRidge(float x, float z) {
        float total = 0f, maxW = 0f;

        for (int i = 0; i < 3; i++) {
            float f = (float)Math.pow(2.1, i);

            float wx = (float)continentNoise.noise(x * 0.0003 + i * 100, 13.7, z * 0.0003 + i * 200) * 8;
            float wz = (float)continentNoise.noise(x * 0.0003 + 500 + i * 300, 29.1, z * 0.0003 + 500 + i * 400) * 8;
            float px = x + wx, pz = z + wz;

            float n = terrainBaseMod(px * f * 0.004f, pz * f * 0.004f);
            float r = 1f - Math.abs(n * 2f - 1f);
            total += Math.max(0f, r) * (float)Math.pow(0.5, i);
            maxW += (float)Math.pow(0.5, i);
        }
        return maxW > 0f ? total / maxW : 0f;
    }

    /** 精确复刻 NoiseEngine.sampleTerrainBase —— FBM域扭曲版本 */
    public float terrainBaseMod(float x, float z) {
        // 域扭曲
        float wx = (float)continentNoise.noise(x * 0.0003, 13.7, z * 0.0003) * 200;
        float wz = (float)continentNoise.noise(x * 0.0003 + 500, 29.1, z * 0.0003 + 500) * 200;
        float px = x + wx, pz = z + wz;

        float lv = sampleFBM(this.large,  px * 0.00025f, pz * 0.00025f, 4, 0.5f, 2.15f, 1.0f) * 0.35f;
        float mv = sampleFBM(this.medium, px * 0.001f,   pz * 0.001f,   4, 0.6f, 2.2f,  1.0f) * 0.15f;
        float sv = sampleFBM(this.small,  px * 0.005f,   pz * 0.005f,   3, 0.7f, 2.25f, 1.0f) * 0.05f;

        float result = (lv + mv + sv) + 0.5f;
        return Math.max(0f, Math.min(1f, result));
    }

    private float sampleFBM(ImprovedNoise[] octaves, float x, float z, int n, float gain, float lacunarity, float baseAmp) {
        float total = 0, amp = baseAmp, freq = 1;
        float baseY = (octaves[0].hashCode() % 1000) * 0.01f;
        for (int i = 0; i < n && i < octaves.length; i++) {
            float y = baseY + i * 1.5f;
            total += (float)octaves[i].noise(x * freq, y, z * freq) * amp;
            amp *= gain;
            freq *= lacunarity;
        }
        return total;
    }
}
