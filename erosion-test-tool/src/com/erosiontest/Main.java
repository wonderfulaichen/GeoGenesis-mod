package com.erosiontest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class Main {
    static int seed = 12345;
    static int scale = 4;
    static int mapSize = 128;
    static int worldSize = mapSize * scale;
    static float strength = 1.0f;
    static float seaNorm = 0.33f;
    static boolean multi = false;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) seed = Integer.parseInt(args[0]);
        if (args.length > 1) scale = Integer.parseInt(args[1]);
        if (args.length > 2) strength = Float.parseFloat(args[2]);
        if (args.length > 3) multi = args[3].equals("multi");

        if (multi) {
            // Multi-resolution test
            mapSize = 128;
            worldSize = mapSize * scale;
            runMulti();
        } else {
            // Single resolution test
            mapSize = 1024 / scale;
            worldSize = mapSize * scale;
            runSingle();
        }
    }

    static void runSingle() throws Exception {
        Noise noise = new Noise(seed);
        Erosion erosion = new Erosion(noise, seed);

        long t0 = System.currentTimeMillis();
        float[][] heightmap = new float[mapSize][mapSize];
        for (int z = 0; z < mapSize; z++)
            for (int x = 0; x < mapSize; x++)
                heightmap[z][x] = computeHeight(noise, x * scale, z * scale);
        float[][] before2 = cloneGrid(heightmap);
        long t1 = System.currentTimeMillis();
        System.out.println("Base terrain: " + (t1 - t0) + "ms");

        erosion.applyErosion(heightmap, mapSize, strength);
        long t2 = System.currentTimeMillis();
        System.out.println("Erosion: " + (t2 - t1) + "ms");

        renderAndSave(heightmap, before2, "single_s" + scale + "_str" + strength);
    }

    static void runMulti() throws Exception {
        Noise noise = new Noise(seed);
        Erosion erosion = new Erosion(noise, seed);

        int baseRes = 128;
        int finalRes = 512;

        long t0 = System.currentTimeMillis();

        // Base heightmap at 128
        float[][] baseGrid = new float[baseRes][baseRes];
        for (int z = 0; z < baseRes; z++)
            for (int x = 0; x < baseRes; x++)
                baseGrid[z][x] = computeHeight(noise, x * 4, z * 4);

        float[][] beforeBase = cloneGrid(baseGrid);
        erosion.applyErosion(baseGrid, baseRes, 2.0f * strength);
        float[] map = toFlat(baseGrid, baseRes);
        long t1 = System.currentTimeMillis();
        System.out.println("Base erosion (128): " + (t1 - t0) + "ms");

        // Upscale 128->256
        map = upscaleStep(noise, erosion, map, 128, 256, 1.0f * strength, 40000);
        long t2 = System.currentTimeMillis();
        System.out.println("Level 256: " + (t2 - t1) + "ms");

        // Upscale 256->512
        map = upscaleStep(noise, erosion, map, 256, 512, 0.5f * strength, 10000);
        long t3 = System.currentTimeMillis();
        System.out.println("Level 512: " + (t3 - t2) + "ms");

        float[][] finalGrid = toGrid(map, finalRes);

        // Create before snapshot at 512 using bicubic upscale from base
        float[] baseMap = toFlat(beforeBase, baseRes);
        float[] beforeMap = bicubicUpsample(baseMap, baseRes, finalRes);
        float[][] beforeGrid = toGrid(beforeMap, finalRes);

        System.out.println("Total: " + (t3 - t0) + "ms");
        renderAndSave(finalGrid, beforeGrid, "multi_s" + seed);
    }

    static float[] upscaleStep(Noise noise, Erosion erosion, float[] src, int srcRes, int dstRes, float eroStrength, int drops) {
        float[] up = bicubicUpsample(src, srcRes, dstRes);
        // Add high-freq noise from raw terrain
        for (int z = 0; z < dstRes; z++)
            for (int x = 0; x < dstRes; x++) {
                float wx = x * 4f * srcRes / dstRes;
                float wz = z * 4f * srcRes / dstRes;
                float raw = noise.terrainBase((int)wx, (int)wz);
                float rawNorm = Math.min(1f, raw / 3f);
                up[z * dstRes + x] = (up[z * dstRes + x] + rawNorm * 0.04f) / 1.04f;
            }
        float[][] grid = toGrid(up, dstRes);
        erosion.applyErosion(grid, dstRes, eroStrength);
        return toFlat(grid, dstRes);
    }

    static void renderAndSave(float[][] after, float[][] before, String name) throws Exception {
        int size = after.length;
        int chunkSize = 16 / (1024 / size / (128 / 128));

        // Auto-detect chunk size: if 512 -> 32px = 1 chunk; if 128 -> 8px
        int pxPerChunk = size / (512 / 16);  // For 512: 16, for 256: 8, for 128: 4
        // Actually simpler: if this is multi-erosion at 512, each block = original 4 blocks, so chunk = 4 px
        if (size == 512) chunkSize = 4;
        else if (size == 256) chunkSize = 8;
        else chunkSize = Math.max(1, 16 / (1024 / size));

        float minH = 1, maxH = 0, maxDelta = 0;
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++) {
                minH = Math.min(minH, Math.min(before[z][x], after[z][x]));
                maxH = Math.max(maxH, Math.max(before[z][x], after[z][x]));
                maxDelta = Math.max(maxDelta, Math.abs(after[z][x] - before[z][x]));
            }
        float range = maxH - minH;

        int gap = 4;
        BufferedImage img = new BufferedImage(size * 2 + gap, size * 2 + gap, BufferedImage.TYPE_INT_RGB);

        // Compute seam delta
        float[][] seamDelta = new float[size][size];
        int maxSeam = 0;
        for (int cz = 0; cz < size; cz += chunkSize) {
            for (int cx = 0; cx < size; cx += chunkSize) {
                for (int dz = 0; dz < chunkSize && cz + dz < size; dz++) {
                    for (int dx = 0; dx < chunkSize && cx + dx < size; dx++) {
                        int z = cz + dz, x = cx + dx;
                        if (cx + dx == cx + chunkSize - 1 && cx + chunkSize < size) {
                            float d = Math.abs(after[z][x] - after[z][x + 1]);
                            seamDelta[z][x] += d;
                        }
                        if (cz + dz == cz + chunkSize - 1 && cz + chunkSize < size) {
                            float d = Math.abs(after[z][x] - after[z + 1][x]);
                            seamDelta[z][x] += d;
                        }
                        maxSeam = Math.max(maxSeam, (int)(seamDelta[z][x] * 10000));
                    }
                }
            }
        }

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                boolean grid = (x % chunkSize == 0) || (z % chunkSize == 0);

                // TL: Before
                int cb = toColor(before[z][x], minH, range);
                img.setRGB(x, z, grid ? darken(cb) : cb);

                // TR: After
                int ca = toColor(after[z][x], minH, range);
                img.setRGB(x + size + gap, z, grid ? darken(ca) : ca);

                int y2 = z + size + gap;

                // BL: Delta
                float d = after[z][x] - before[z][x];
                img.setRGB(x, y2, deltaColor(d, maxDelta));

                // BR: Seam highlight
                float seam = seamDelta[z][x];
                int cs;
                if (seam > 0.0005f) {
                    int v = (int)Math.min(255, seam * 10000);
                    cs = (v << 16);
                } else {
                    cs = toColor(after[z][x], minH, range);
                }
                img.setRGB(x + size + gap, y2, cs);
            }
        }

        File outDir = new File("output");
        outDir.mkdirs();
        int runNum = 0;
        File file;
        do {
            String suffix = runNum == 0 ? "" : "_v" + runNum;
            file = new File(outDir, name + suffix + ".png");
            runNum++;
        } while (file.exists());
        ImageIO.write(img, "png", file);
        System.out.println("Saved: " + file.getAbsolutePath());
        System.out.println("Layout: TL=Before  TR=After  BL=Delta  BR=Seam(red)");
        System.out.println("Max delta: " + String.format("%.4f", maxDelta));
        System.out.println("Max seam: " + maxSeam);
    }

    static float computeHeight(Noise noise, int wx, int wz) {
        float continent = noise.continentRaw(wx, wz);
        float terrain = noise.terrainBase(wx, wz);
        float shapeNorm = Math.min(1f, terrain / 3f);
        float continentBase = Math.max(0f, (continent - 0.08f) / (1f - 0.08f));
        float lift = continent < 0.08f ? 0 : Math.min(1f, (continent - 0.08f) / (1f - 0.08f)) * 0.05f;
        float base = seaNorm + continentBase * (1f - seaNorm) * 0.05f;
        float land = base + shapeNorm * (1f - seaNorm) * 0.7f + lift;
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

    static float[][] cloneGrid(float[][] src) {
        int n = src.length;
        float[][] dst = new float[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(src[i], 0, dst[i], 0, n);
        return dst;
    }

    static float[] bicubicUpsample(float[] src, int srcRes, int dstRes) {
        float[] dst = new float[dstRes * dstRes];
        float scale = (float)srcRes / dstRes;
        for (int dz = 0; dz < dstRes; dz++) {
            for (int dx = 0; dx < dstRes; dx++) {
                float sx = dx * scale, sy = dz * scale;
                int ix = (int)sx, iy = (int)sy;
                float fx = sx - ix, fy = sy - iy;
                float[] col = new float[4];
                for (int i = -1; i <= 2; i++) {
                    float[] row = new float[4];
                    for (int j = -1; j <= 2; j++) {
                        int px = clamp(ix + j, 0, srcRes - 1);
                        int py = clamp(iy + i, 0, srcRes - 1);
                        row[j + 1] = src[py * srcRes + px];
                    }
                    col[i + 1] = cubic(line(row[0], row[1], row[2], row[3]), fx);
                }
                dst[dz * dstRes + dx] = cubic(line(col[0], col[1], col[2], col[3]), fy);
            }
        }
        return dst;
    }

    static float[] line(float v0, float v1, float v2, float v3) { return new float[]{v0, v1, v2, v3}; }
    static float cubic(float[] v, float t) {
        return v[1] + 0.5f * t * (v[2] - v[0] + t * (2 * v[0] - 5 * v[1] + 4 * v[2] - v[3] + t * (3 * (v[1] - v[2]) + v[3] - v[0])));
    }

    static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    static float[][] toGrid(float[] flat, int size) {
        float[][] g = new float[size][size];
        for (int z = 0; z < size; z++)
            System.arraycopy(flat, z * size, g[z], 0, size);
        return g;
    }

    static float[] toFlat(float[][] grid, int size) {
        float[] f = new float[size * size];
        for (int z = 0; z < size; z++)
            System.arraycopy(grid[z], 0, f, z * size, size);
        return f;
    }

    static int darken(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r = r * 3 / 4; g = g * 3 / 4; b = b * 3 / 4;
        return (r << 16) | (g << 8) | b;
    }

    static int toColor(float h, float min, float range) {
        float t = range > 0 ? (h - min) / range : 0.5f;
        t = Math.max(0, Math.min(1, t));
        if (t < 0.15f) { int b = (int)(t / 0.15f * 80 + 40); return (0<<16) | (0<<8) | b; }
        if (t < 0.35f) { float tt = (t - 0.15f) / 0.20f; return (0<<16) | ((int)(tt*160+40)<<8) | (int)(120-tt*80); }
        if (t < 0.45f) { return ((int)((t-0.35f)/0.10f*80+20)<<16) | ((int)((t-0.35f)/0.10f*60+140)<<8) | 10; }
        if (t < 0.70f) { int c = (int)((t-0.45f)/0.25f*60+100); return (c<<16) | ((c-10)<<8) | (c-30); }
        int c = (int)Math.min(255, (t-0.70f)/0.30f*100+160);
        return (c<<16) | (c<<8) | c;
    }

    static int deltaColor(float delta, float maxDelta) {
        float t = maxDelta > 0 ? Math.abs(delta) / maxDelta : 0;
        t = Math.min(1, t); int v = (int)(t * 255);
        if (delta > 0) return (v << 16);
        if (delta < 0) return (v << 8);
        return 0x444444;
    }
}
