package com.erosiontest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class MultiErosion {
    static int seed = 12345;
    static float strength = 1.0f;
    static String outputName = "multi_erosion";

    public static void main(String[] args) throws Exception {
        if (args.length > 0) seed = Integer.parseInt(args[0]);
        if (args.length > 1) strength = Float.parseFloat(args[1]);

        Noise noise = new Noise(seed);
        Erosion erosion = new Erosion(noise, seed);

        // Start from 256 resolution, each = 4 blocks -> covers 1024x1024 world
        int baseRes = 128;
        int finalRes = 512;

        System.out.println("=== Multi-Resolution Erosion ===");
        System.out.println("Seed: " + seed + "  Strength: " + strength);
        System.out.println("Base: " + baseRes + "x" + baseRes + "  Final: " + finalRes + "x" + finalRes);

        long t0 = System.currentTimeMillis();

        // Step 1: Generate base heightmap at low resolution
        float[] map = new float[baseRes * baseRes];
        for (int z = 0; z < baseRes; z++)
            for (int x = 0; x < baseRes; x++)
                map[z * baseRes + x] = sampleHeight(noise, x * 4f, z * 4f);
        long t1 = System.currentTimeMillis();
        System.out.println("Base gen: " + (t1 - t0) + "ms");

        // Step 2: Strong erosion at base resolution
        float[][] baseGrid = toGrid(map, baseRes);
        erosion.applyErosion(baseGrid, baseRes, 2.0f * strength);
        map = toFlat(baseGrid, baseRes);
        long t2 = System.currentTimeMillis();
        System.out.println("Base erosion: " + (t2 - t1) + "ms");

        // Step 3: Multi-level upscale + noise + light erosion
        int[] levels = {baseRes, baseRes * 2, finalRes};
        float[] prevMap = map;
        int prevRes = baseRes;

        for (int li = 1; li < levels.length; li++) {
            int curRes = levels[li];
            float[] upsampled = bicubicUpsample(prevMap, prevRes, curRes);

            // Add raw noise to restore high frequencies
            float noiseStrength = 0.08f / li;
            for (int z = 0; z < curRes; z++) {
                for (int x = 0; x < curRes; x++) {
                    float wx = x * 4f * baseRes / curRes;
                    float wz = z * 4f * baseRes / curRes;
                    float raw = noise.terrainBase((int)wx, (int)wz);
                    float rawNorm = Math.min(1f, raw / 3f);
                    upsampled[z * curRes + x] = (upsampled[z * curRes + x] + rawNorm * noiseStrength) / (1 + noiseStrength);
                }
            }

            // Light erosion on this level
            float[][] curGrid = toGrid(upsampled, curRes);
            float eroStrength = 0.5f * strength / li;
            int prevDropCount = (int)(40000 * strength);
            int drops = Math.max(5000, prevDropCount / (li * li));
            dropValleyConstrained(curGrid, curRes, drops / li, 30, prevRes);
            erosion.applyErosion(curGrid, curRes, eroStrength);
            map = toFlat(curGrid, curRes);

            long tn = System.currentTimeMillis();
            System.out.println("Level " + curRes + "x" + curRes + ": " + (tn - t2) + "ms (drops=" + drops + ", ero=" + String.format("%.2f", eroStrength) + ")");
            prevMap = map;
            prevRes = curRes;
        }

        // Save result
        int displayRes = finalRes;
        float minH = 1, maxH = 0;
        for (float v : map) { minH = Math.min(minH, v); maxH = Math.max(maxH, v); }
        float range = Math.max(maxH - minH, 0.01f);

        BufferedImage img = new BufferedImage(displayRes, displayRes, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < displayRes; z++)
            for (int x = 0; x < displayRes; x++)
                img.setRGB(x, z, toColor(map[z * displayRes + x], minH, range));

        File outDir = new File("output");
        outDir.mkdirs();
        File file = new File(outDir, outputName + "_s" + seed + ".png");
        ImageIO.write(img, "png", file);
        System.out.println("Saved: " + file.getAbsolutePath());
        System.out.println("Total: " + (System.currentTimeMillis() - t0) + "ms");
    }

    static float sampleHeight(Noise noise, float wx, float wz) {
        float continent = noise.continentRaw((int)wx, (int)wz);
        float terrain = noise.terrainBase((int)wx, (int)wz);
        float shapeNorm = Math.min(1f, terrain / 2.2f);
        shapeNorm = (float)Math.pow(shapeNorm, 0.85f);
        float seaNorm = 0.33f;
        float continentBase = Math.max(0f, (continent - 0.08f) / (1f - 0.08f));
        float lift = continent < 0.08f ? 0 : Math.min(1f, (continent - 0.08f) / (1f - 0.08f)) * 0.05f;
        float base = seaNorm + continentBase * (1f - seaNorm) * 0.05f;
        float land = base + shapeNorm * (1f - seaNorm) * 1.5f + lift;
        float oceanDepth = 0;
        if (continent < 0.08f) {
            float t = (continent + 1f) / 1.08f;
            t = Math.max(0, Math.min(1, t));
            oceanDepth = 0.1f * (1f - t * t * (3 - 2 * t));
        }
        float ocean = seaNorm - oceanDepth;
        float mask;
        if (continent <= 0.08f) mask = 0;
        else if (continent >= 0.18f) mask = 1;
        else { float t = (continent - 0.08f) / 0.10f; mask = t * t * (3 - 2 * t); }
        return Math.max(0, Math.min(1, ocean * (1 - mask) + land * mask));
    }

    static float[] bicubicUpsample(float[] src, int srcRes, int dstRes) {
        float[] dst = new float[dstRes * dstRes];
        float scale = (float)srcRes / dstRes;
        for (int dz = 0; dz < dstRes; dz++) {
            for (int dx = 0; dx < dstRes; dx++) {
                float sx = dx * scale;
                float sy = dz * scale;
                int ix = (int)sx;
                int iy = (int)sy;
                float fx = sx - ix;
                float fy = sy - iy;

                float[] col = new float[4];
                for (int i = -1; i <= 2; i++) {
                    float[] row = new float[4];
                    for (int j = -1; j <= 2; j++) {
                        int px = clamp(ix + j, 0, srcRes - 1);
                        int py = clamp(iy + i, 0, srcRes - 1);
                        row[j + 1] = src[py * srcRes + px];
                    }
                    col[i + 1] = cubicInterp(row[0], row[1], row[2], row[3], fx);
                }
                dst[dz * dstRes + dx] = cubicInterp(col[0], col[1], col[2], col[3], fy);
            }
        }
        return dst;
    }

    static float cubicInterp(float v0, float v1, float v2, float v3, float t) {
        float t2 = t * t;
        float a0 = v3 - v2 - v0 + v1;
        float a1 = v0 - v1 - a0;
        float a2 = v2 - v0;
        float a3 = v1;
        return a0 * t * t2 + a1 * t2 + a2 * t + a3;
    }

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    static void dropValleyConstrained(float[][] map, int size, int drops, int life, int valleyRes) {
        float valleyThreshold = 0.005f;
        for (int i = 0; i < drops; i++) {
            long h = hash(i * 31 + 999, i * 73 + 999);
            int sx = (int)((h & 0xFFFFFFFFL) % (size - 6)) + 3;
            int sz = (int)(((h >>> 32) & 0xFFFFFFFFL) % (size - 6)) + 3;
            if (sx < 0 || sz < 0 || sx >= size - 1 || sz >= size - 1) continue;
            if (map[sz][sx] <= 0.33f) continue;

            // Check if this is in a valley: adjacent cells are higher
            float h0 = map[sz][sx];
            float lowestNeighbor = Float.MAX_VALUE;
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    lowestNeighbor = Math.min(lowestNeighbor, map[sz + dz][sx + dx]);
                }
            if (h0 - lowestNeighbor < valleyThreshold) continue;

            // Simple valley drop: just erode along steepest descent
            float px = sx + 0.5f, pz = sz + 0.5f;
            for (int step = 0; step < life; step++) {
                int ix = (int)px, iz = (int)pz;
                if (ix < 1 || ix >= size - 2 || iz < 1 || iz >= size - 2) break;
                float gx = map[iz][ix+1] - map[iz][ix];
                float gz = map[iz+1][ix] - map[iz][ix];
                float glen = (float)Math.sqrt(gx*gx + gz*gz);
                if (glen < 1e-8f) break;
                gx /= glen; gz /= glen;
                px -= gx; pz -= gz;
                if (px < 1 || px >= size - 2 || pz < 1 || pz >= size - 2) break;
                int nix = (int)px, niz = (int)pz;
                float dh = map[iz][ix] - map[niz][nix];
                if (dh <= 0) break;
                float erode = Math.min(0.005f, dh * 0.1f);
                map[iz][ix] -= erode;
            }
        }
    }

    static float[][] toGrid(float[] flat, int size) {
        float[][] g = new float[size][size];
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                g[z][x] = flat[z * size + x];
        return g;
    }

    static float[] toFlat(float[][] grid, int size) {
        float[] f = new float[size * size];
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                f[z * size + x] = grid[z][x];
        return f;
    }

    static long hash(int a, int b) {
        long h = a * 0x9e3779b9L + b * 0x9e3779b9L * 31;
        h = (h ^ (h >>> 16)) * 0x85ebca6bL;
        h = h ^ (h >>> 13);
        h = h * 0xc2b2ae35L;
        h = h ^ (h >>> 16);
        return h;
    }

    static int toColor(float h, float min, float range) {
        float t = range > 0 ? (h - min) / range : 0.5f;
        t = Math.max(0, Math.min(1, t));
        if (t < 0.15f) {
            int b = (int)(t / 0.15f * 80 + 40);
            return (0<<16) | (0<<8) | b;
        }
        if (t < 0.35f) {
            float tt = (t - 0.15f) / 0.20f;
            return (0<<16) | ((int)(tt * 160 + 40)<<8) | (int)(120 - tt * 80);
        }
        if (t < 0.45f) {
            return ((int)((t-0.35f)/0.10f*80+20)<<16) | ((int)((t-0.35f)/0.10f*60+140)<<8) | 10;
        }
        if (t < 0.70f) {
            int c = (int)((t-0.45f)/0.25f*60+100);
            return (c<<16) | ((c-10)<<8) | (c-30);
        }
        int c = (int)Math.min(255, (t-0.70f)/0.30f*100+160);
        return (c<<16) | (c<<8) | c;
    }
}
