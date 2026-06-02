package com.erosiontest;

import java.util.Random;

/**
 * 地形侵蚀性能测试工具
 * 复现 ErosionEngine.pyramidErosion 的核心逻辑
 */
public class ErosionPerfTest {

    static int SIZE = 112; // 模拟模组的 tile 大小
    static int[] DROPS_PER_LAYER = {4000, 2500, 1200};
    static int[] BRUSH_RADIUS = {8, 4, 2};
    static float[] STR_PER_LAYER = {1.5f, 1.0f, 0.6f};
    static float[] ERODE_PER_LAYER = {0.3f, 0.2f, 0.1f};
    static float[] DEPOSIT_PER_LAYER = {0.06f, 0.12f, 0.18f};

    static float[][] generateTerrain(int size, long seed) {
        Random rng = new Random(seed);
        float[][] h = new float[size][size];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                // 简单噪声地形
                float nx = x * 0.02f, nz = z * 0.02f;
                h[z][x] = 0.3f + 0.4f * (float)(Math.sin(nx * 3) * Math.cos(nz * 2) + Math.sin(nx * 7 + nz * 5) * 0.3);
                h[z][x] = Math.max(0, Math.min(1, h[z][x]));
            }
        }
        return h;
    }

    static void applyErosion(float[][] heights, int size, int ox, int oz) {
        float capFactor = 10f, minCap = 0.005f, evaporate = 0.35f;
        float fallOff = 0.5f, inertia = 0.001f, gravity = 2.5f;

        for (int li = 0; li < DROPS_PER_LAYER.length; li++) {
            int drops = DROPS_PER_LAYER[li];
            int radius = BRUSH_RADIUS[li];
            float strength = STR_PER_LAYER[li];
            float erodeSpeed = ERODE_PER_LAYER[li];
            float depositSpeed = DEPOSIT_PER_LAYER[li];

            int pad = Math.max(radius * 2, 4);
            int bufSize = size + pad * 2;
            float[] flat = new float[bufSize * bufSize];

            // 填充 + mirror padding
            for (int z = 0; z < bufSize; z++) {
                for (int x = 0; x < bufSize; x++) {
                    int sz = Math.max(0, Math.min(size - 1, z - pad));
                    int sx = Math.max(0, Math.min(size - 1, x - pad));
                    flat[z * bufSize + x] = heights[sz][sx];
                }
            }

            // 共享笔刷
            int r2 = radius * radius;
            int maxB = (2 * radius + 1) * (2 * radius + 1);
            int[] bOff = new int[maxB];
            float[] bWgt = new float[maxB];
            int bn = 0;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    float d2 = dx * dx + dy * dy;
                    if (d2 < r2) {
                        bOff[bn] = dy * bufSize + dx;
                        bWgt[bn] = 1f - (float) Math.sqrt(d2) / radius;
                        bn++;
                    }
                }
            }
            { float s = 0; for (int i = 0; i < bn; i++) s += bWgt[i]; for (int i = 0; i < bn; i++) bWgt[i] /= s; }

            // ===== 密度法（旧）=====
            long t1 = System.currentTimeMillis();
            float pixelCount = (float)(size * size);
            float density = drops / pixelCount;
            long threshold = (long)(density * (1L << 20));

            for (int py = pad; py < pad + size; py++) {
                for (int px = pad; px < pad + size; px++) {
                    int worldX = ox + (px - pad);
                    int worldZ = oz + (py - pad);
                    long h = hashCoarse(worldX * 31 + 1, worldZ * 73 + 1);
                    if ((h & ((1L << 20) - 1)) >= threshold) continue;
                    int idx = py * bufSize + px;
                    if (flat[idx] <= 0.02f) continue;

                    simulateDrop(flat, bufSize, px, py, capFactor, minCap, evaporate, fallOff, inertia, gravity, erodeSpeed, depositSpeed, strength, bOff, bWgt, bn);
                }
            }
            long t2 = System.currentTimeMillis();

            // ===== 随机法（新）=====
            // 重置 flat
            for (int z = 0; z < bufSize; z++) {
                for (int x = 0; x < bufSize; x++) {
                    int sz = Math.max(0, Math.min(size - 1, z - pad));
                    int sx = Math.max(0, Math.min(size - 1, x - pad));
                    flat[z * bufSize + x] = heights[sz][sx];
                }
            }

            long t3 = System.currentTimeMillis();
            Random rng = new Random(ox * 31L + oz * 73L);
            int spawnMin = pad;
            int spawnMax = pad + size;
            int spawnRange = spawnMax - spawnMin;

            for (int i = 0; i < drops; i++) {
                int px = spawnMin + rng.nextInt(spawnRange);
                int py = spawnMin + rng.nextInt(spawnRange);
                int idx = py * bufSize + px;
                if (flat[idx] <= 0.02f) continue;

                simulateDrop(flat, bufSize, px, py, capFactor, minCap, evaporate, fallOff, inertia, gravity, erodeSpeed, depositSpeed, strength, bOff, bWgt, bn);
            }
            long t4 = System.currentTimeMillis();

            System.out.println("Layer " + li + ": radius=" + radius + ", drops=" + drops);
            System.out.println("  密度法: " + (t2 - t1) + "ms");
            System.out.println("  随机法: " + (t4 - t3) + "ms");
            System.out.println("  加速比: " + String.format("%.1f", (float)(t2 - t1) / (t4 - t3)) + "x");

            // 写回
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    heights[z][x] = clamp(flat[(z + pad) * bufSize + (x + pad)], 0f, 1f);
                }
            }
        }
    }

    static void simulateDrop(float[] flat, int bufSize, int px, int py,
                             float capFactor, float minCap, float evaporate,
                             float fallOff, float inertia, float gravity,
                             float erodeSpeed, float depositSpeed, float strength,
                             int[] bOff, float[] bWgt, int bn) {
        float dirX = 0, dirZ = 0, sed = 0, spd = 1f, wat = 1f;
        float fpx = px + 0.5f, fpz = py + 0.5f;

        for (int step = 0; step < 30; step++) {
            int ix = (int)fpx, iz = (int)fpz;
            if (ix < 1 || ix >= bufSize - 2 || iz < 1 || iz >= bufSize - 2) break;
            int idx = iz * bufSize + ix;
            float fx = fpx - ix, fz = fpz - iz;
            float hNW = flat[idx], hNE = flat[idx + 1], hSW = flat[idx + bufSize], hSE = flat[idx + bufSize + 1];
            float h0 = hNW * (1 - fx) * (1 - fz) + hNE * fx * (1 - fz) + hSW * (1 - fx) * fz + hSE * fx * fz;
            if (h0 <= 0.02f) break;
            float gx = (hNE - hNW) * (1 - fz) + (hSE - hSW) * fz;
            float gz = (hSW - hNW) * (1 - fx) + (hSE - hNE) * fx;
            float glen = (float) Math.sqrt(gx * gx + gz * gz);
            if (glen < 1e-12f) break;
            dirX = dirX * inertia - gx * (1 - inertia);
            dirZ = dirZ * inertia - gz * (1 - inertia);
            float dlen = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (dlen < 1e-12f) break;
            dirX /= dlen;
            dirZ /= dlen;
            float npx = fpx + dirX, npz = fpz + dirZ;
            if (npx < 1 || npx >= bufSize - 2 || npz < 1 || npz >= bufSize - 2) break;
            int nix = (int) npx, niz = (int) npz;
            float fnx = npx - nix, fnz = npz - niz;
            int nidx = niz * bufSize + nix;
            float h1 = flat[nidx] * (1 - fnx) * (1 - fnz) + flat[nidx + 1] * fnx * (1 - fnz)
                    + flat[nidx + bufSize] * (1 - fnx) * fnz + flat[nidx + bufSize + 1] * fnx * fnz;
            float dh = (h1 - h0) * Math.min(1, h0 / fallOff);
            float cap = Math.max(-dh * spd * wat * capFactor * strength, minCap);
            if (sed > cap || dh > 0) {
                float dep = dh > 0 ? Math.min(dh, sed) : (sed - cap) * depositSpeed;
                sed -= dep;
                flat[idx] += dep * (1 - fx) * (1 - fz);
                flat[idx + 1] += dep * fx * (1 - fz);
                flat[idx + bufSize] += dep * (1 - fx) * fz;
                flat[idx + bufSize + 1] += dep * fx * fz;
            } else {
                float eroAmt = Math.min((cap - sed) * erodeSpeed, -dh);
                for (int b = 0; b < bn; b++) {
                    int bi = idx + bOff[b];
                    if (bi >= 0 && bi < bufSize * bufSize) {
                        float delta = Math.min(flat[bi], eroAmt * bWgt[b]);
                        flat[bi] -= delta;
                        sed += delta;
                    }
                }
            }
            fpx = npx;
            fpz = npz;
            spd = (float) Math.sqrt(spd * spd + dh * gravity);
            if (spd <= 0) break;
            wat *= (1 - evaporate);
        }
    }

    static long hashCoarse(int a, int b) {
        long h = a * 0x9e3779b9L + b * 0x9e3779b9L * 31;
        h = (h ^ (h >>> 16)) * 0x85ebca6bL;
        h = h ^ (h >>> 13);
        h = h * 0xc2b2ae35L;
        h = h ^ (h >>> 16);
        return h;
    }

    static float clamp(float v, float min, float max) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    public static void main(String[] args) {
        System.out.println("=== 地形侵蚀性能测试 ===");
        System.out.println("Tile size: " + SIZE + "x" + SIZE);
        System.out.println();

        long seed = 12345;
        float[][] terrain = generateTerrain(SIZE, seed);

        long start = System.currentTimeMillis();
        applyErosion(terrain, SIZE, 0, 0);
        long end = System.currentTimeMillis();

        System.out.println("\n总耗时: " + (end - start) + "ms");
    }
}
