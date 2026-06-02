package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 精确复刻 Mod 的 pyramidErosion 算法，诊断两个相邻 tile 的边界
 */
public class ModDiagnostic {

    static final int TILE = 112;
    static final int CHUNK = 16;
    static final int HALF = 3;
    static final int OUT_R = 1;
    static final int OUT_DIM = 3;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Mod 算法复刻诊断 ===\n");

        long seed = 12345L;
        Noise noise = new Noise((int) seed);

        // Tile A (0,0) world(-48,-48) — 初始tile，无邻居
        System.out.println("Tile A — 完全自由侵蚀...");
        float[][][] cacheA = new float[3][][];
        long t0 = System.nanoTime();
        float[][] hA = erodeModAlgo(noise, -48, -48, seed, null, cacheA);
        System.out.println("  " + (System.nanoTime() - t0) / 1e6 + "ms");

        // Tile B (3,0) world(0,-48) — 左边缘混合到 A
        System.out.println("Tile B — 左边缘混合到 A...");
        float[][][] cacheB = new float[3][][];
        long t1 = System.nanoTime();
        float[][] hB = erodeModAlgo(noise, 0, -48, seed + 999L, cacheA, cacheB);
        System.out.println("  " + (System.nanoTime() - t1) / 1e6 + "ms");

        // === 诊断 ===
        int[] res = {14, 28, 56, 112};
        int[] brushP = {3, 4, 4};

        // 检查每个级别
        for (int li = 0; li < 3; li++) {
            int curRes = res[li];
            int os = (HALF - OUT_R) * CHUNK * curRes / TILE;
            int ow = OUT_DIM * CHUNK * curRes / TILE;
            int delta = 3 * CHUNK * curRes / TILE;
            int bw = brushP[li];

            System.out.printf("\n--- Level %d (%dx%d) ---%n", li, curRes, curRes);
            System.out.printf("  outStart=%d outWidth=%d delta=%d blendW=%d%n", os, ow, delta, bw);

            if (cacheA[li] == null || cacheB[li] == null) continue;

            // 混合区：B左边缘 [os, os+bw-1]
            System.out.println("  B左混合列 → A对应列（应一致）:");
            for (int col = 0; col < bw; col++) {
                int bc = os + col;
                int ac = os + delta + col;
                if (bc >= curRes || ac >= curRes) break;
                double maxDiff = 0;
                for (int i = os; i < os + ow && i < curRes; i++)
                    maxDiff = Math.max(maxDiff, Math.abs(cacheA[li][i][ac] - cacheB[li][i][bc]));
                System.out.printf("    B[%d]=A[%d] maxDiff=%.6f %s%n",
                    bc, ac, maxDiff, maxDiff < 0.0001 ? "✓" : "✗");
            }

            // 混合外：B内部非混合列 vs A对应列
            System.out.println("  B内部(非混合) vs A对应列:");
            double totalMax = 0;
            for (int col = bw; col < ow - bw; col++) {
                int bc = os + col;
                int ac = os + col + delta;
                if (bc >= curRes || ac >= curRes) break;
                double maxDiff = 0;
                for (int i = os; i < os + ow && i < curRes; i++)
                    maxDiff = Math.max(maxDiff, Math.abs(cacheA[li][i][ac] - cacheB[li][i][bc]));
                totalMax = Math.max(totalMax, maxDiff);
                if (maxDiff > 0.001) {
                    System.out.printf("    B[%d]=A[%d] maxDiff=%.6f %s%n",
                        bc, ac, maxDiff, maxDiff < 0.0001 ? "✓" : "✗");
                }
            }
            System.out.printf("    非混合列最大差异: %.6f%n", totalMax);
        }

        // 最终112级
        System.out.println("\n--- 最终 112×112 ---");
        int os = (HALF - OUT_R) * CHUNK;
        int ow = OUT_DIM * CHUNK;
        int aR = os + ow - 1, bL = os;
        double max = 0;
        for (int row = os; row < os + ow; row++) {
            double d = Math.abs(hA[row][aR] - hB[row][bL]);
            max = Math.max(max, d);
        }
        System.out.printf("  A右边缘 vs B左边缘: maxDiff=%.6f %s%n", max, max < 0.0001 ? "✓" : "✗");

        saveDiag(hA, hB, cacheA, cacheB, os, ow);
    }

    /** 精确复刻 Mod 的 generateErosionTile + pyramidErosion */
    static float[][] erodeModAlgo(Noise noise, int wx, int wz, long seed,
                                   float[][][] leftCache, float[][][] outCache) {
        float[][] raw = new float[TILE][TILE];
        float mn = 1, mx = 0;
        for (int z = 0; z < TILE; z++)
            for (int x = 0; x < TILE; x++) {
                raw[z][x] = noise.terrainBaseMod(wx + x, wz + z);
                mn = Math.min(mn, raw[z][x]);
                mx = Math.max(mx, raw[z][x]);
            }
        float rng = mx - mn;
        for (int z = 0; z < TILE; z++)
            for (int x = 0; x < TILE; x++)
                raw[z][x] = (raw[z][x] - mn) / (rng > 0 ? rng : 1);

        int[] res = {14, 28, 56, 112};
        int[] brushP = {3, 4, 4};
        float[] strP = {1.5f, 1.2f, 1.0f};
        float[] erodeP = {0.3f, 0.2f, 0.15f};
        float[] depP = {0.04f, 0.03f, 0.02f};
        int[] dropsP = {7500, 9000, 6000};
        int adjChunks = 3;

        float[][] h = null;

        for (int li = 0; li < res.length; li++) {
            int curRes = res[li];

            if (li == 0) {
                int scale = TILE / curRes;
                h = new float[curRes][curRes];
                for (int z = 0; z < curRes; z++)
                    for (int x = 0; x < curRes; x++) {
                        float s = 0;
                        int n = 0;
                        for (int dz = 0; dz < scale; dz++)
                            for (int dx = 0; dx < scale; dx++) {
                                s += raw[z * scale + dz][x * scale + dx];
                                n++;
                            }
                        h[z][x] = s / n;
                    }
            } else {
                h = ErosionPipeline.bicubicUpsampleGrid(h, res[li - 1], curRes);
            }

            // 全自由侵蚀（复刻 pyramidErosion 逻辑）
            if (li < res.length - 1) {
                int brushR = brushP[li];
                int pad = Math.max(brushR * 2, 4);
                float[][] p = padEdgeClamp(h, curRes, pad);
                pyramidErosionClone(p, curRes + pad * 2, dropsP[li], strP[li],
                    brushR, 0.5f, 0.001f, 2.5f, erodeP[li], depP[li],
                    wx, wz, pad, curRes, TILE / curRes, null, seed + li * 10000L);
                for (int z = 0; z < curRes; z++)
                    System.arraycopy(p[z + pad], pad, h[z], 0, curRes);
            }

            // 侵蚀后混合（与 Mod 完全一致）
            if (li < res.length - 1 && leftCache != null && leftCache[li] != null) {
                int os = (HALF - OUT_R) * CHUNK * curRes / TILE;
                int ow = OUT_DIM * CHUNK * curRes / TILE;
                int delta = adjChunks * CHUNK * curRes / TILE;
                int bw = brushP[li];
                float[][] left = leftCache[li];

                // 左混合
                for (int col = 0; col < bw; col++) {
                    int d = os + col, s = os + delta + col;
                    if (d >= curRes || s >= curRes) break;
                    float w = 0.5f * (1f + (float) Math.cos((1f - (float) col / bw) * Math.PI));
                    for (int i = os; i < os + ow && i < curRes; i++)
                        h[i][d] = h[i][d] * (1 - w) + left[i][s] * w;
                }
            }

            // 缓存
            if (li < 3 && outCache != null) {
                outCache[li] = new float[curRes][curRes];
                for (int z = 0; z < curRes; z++)
                    System.arraycopy(h[z], 0, outCache[li][z], 0, curRes);
            }
        }
        return h;
    }

    /** 精确复刻 Mod 的 pyramidErosion（世界坐标密度法，30步，边沿钳制pad） */
    static void pyramidErosionClone(float[][] map, int bufSize, int drops, float strength,
                                     int radius, float fallOff, float inertia, float gravity,
                                     float erodeSpeed, float depositSpeed,
                                     int ox, int oz, int pad, int baseSize, int worldScale,
                                     boolean[][] locked, long seedOffset) {
        float capFactor = 10f, minCap = 0.005f, evaporate = 0.35f;
        float[] flat = new float[bufSize * bufSize];
        for (int z = 0; z < bufSize; z++)
            for (int x = 0; x < bufSize; x++)
                flat[z * bufSize + x] = map[z][x];

        int r2 = radius * radius;
        int maxB = (2 * radius + 1) * (2 * radius + 1);
        int[] bOff = new int[maxB];
        float[] bWgt = new float[maxB];
        int bn = 0;
        for (int dy = -radius; dy <= radius; dy++)
            for (int dx = -radius; dx <= radius; dx++) {
                float d2 = dx * dx + dy * dy;
                if (d2 < r2) {
                    bOff[bn] = dy * bufSize + dx;
                    bWgt[bn] = 1f - (float) Math.sqrt(d2) / radius;
                    bn++;
                }
            }
        {
            float s = 0;
            for (int i = 0; i < bn; i++)
                s += bWgt[i];
            for (int i = 0; i < bn; i++)
                bWgt[i] /= s;
        }

        int interiorStart = pad;
        int interiorEnd = pad + baseSize;
        int margin = 1;
        float pixelCount = (float) ((baseSize - margin * 2) * (baseSize - margin * 2));
        float density = drops / pixelCount;
        long threshold = (long) (density * (1L << 20));

        for (int py = interiorStart + margin; py < interiorEnd - margin; py++) {
            for (int px = interiorStart + margin; px < interiorEnd - margin; px++) {
                int worldX = ox + (px - pad) * worldScale;
                int worldZ = oz + (py - pad) * worldScale;
                long h = hashCoarse(worldX * 31 + 1, worldZ * 73 + 1);
                if ((h & ((1L << 20) - 1)) >= threshold) continue;
                int idx = py * bufSize + px;
                if (flat[idx] <= 0.02f) continue;
                if (locked != null && locked[py][px]) continue;

                float dirX = 0, dirZ = 0, sed = 0, spd = 1f, wat = 1f;
                float fpx = px + 0.5f, fpz = py + 0.5f;

                for (int step = 0; step < 30; step++) {
                    int ix = (int) fpx, iz = (int) fpz;
                    if (ix < 1 || ix >= bufSize - 2 || iz < 1 || iz >= bufSize - 2) break;
                    idx = iz * bufSize + ix;
                    float fx = fpx - ix, fz = fpz - iz;
                    float hNW = flat[idx], hNE = flat[idx + 1];
                    float hSW = flat[idx + bufSize], hSE = flat[idx + bufSize + 1];
                    float h0 = hNW * (1 - fx) * (1 - fz) + hNE * fx * (1 - fz)
                             + hSW * (1 - fx) * fz + hSE * fx * fz;
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
                        if (locked == null || !locked[iz][ix]) flat[idx] += dep * (1 - fx) * (1 - fz);
                        if (locked == null || !locked[iz][ix + 1]) flat[idx + 1] += dep * fx * (1 - fz);
                        if (locked == null || !locked[iz + 1][ix]) flat[idx + bufSize] += dep * (1 - fx) * fz;
                        if (locked == null || !locked[iz + 1][ix + 1]) flat[idx + bufSize + 1] += dep * fx * fz;
                    } else {
                        float eroAmt = Math.min((cap - sed) * erodeSpeed, -dh);
                        for (int b = 0; b < bn; b++) {
                            int bi = idx + bOff[b];
                            if (bi >= 0 && bi < bufSize * bufSize) {
                                int bz = bi / bufSize, bx = bi % bufSize;
                                if (locked != null && locked[bz][bx]) continue;
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
        }
        for (int z = 0; z < bufSize; z++)
            for (int x = 0; x < bufSize; x++)
                map[z][x] = clamp(flat[z * bufSize + x], 0f, 1f);
    }

    static float[][] padEdgeClamp(float[][] src, int size, int pad) {
        int ns = size + pad * 2;
        float[][] dst = new float[ns][ns];
        for (int z = 0; z < ns; z++)
            for (int x = 0; x < ns; x++)
                dst[z][x] = src[clamp(z - pad, 0, size - 1)][clamp(x - pad, 0, size - 1)];
        return dst;
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

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    static void saveDiag(float[][] a, float[][] b,
                          float[][][] cA, float[][][] cB,
                          int os, int ow) throws Exception {
        int sz = 256;
        int aR = os + ow - 1, bL = os;
        BufferedImage img = new BufferedImage(sz * 3, sz * 2, BufferedImage.TYPE_INT_RGB);

        for (int py = 0; py < sz; py++) {
            int row = os + py * ow / sz;
            if (row >= TILE) row = TILE - 1;
            int ga = (int) (clamp(a[row][aR], 0, 1) * 255);
            int gb = (int) (clamp(b[row][bL], 0, 1) * 255);
            for (int px = 0; px < sz; px++) img.setRGB(px, py, ga << 16 | ga << 8 | ga);
            for (int px = 0; px < sz; px++) img.setRGB(sz + px, py, gb);
            if (Math.abs(a[row][aR] - b[row][bL]) > 0.001)
                for (int x = sz - 1; x < sz + 1; x++) img.setRGB(x, py, 0xFF0000);
        }

        for (int py = 0; py < sz; py++) {
            int row = os + py * ow / sz;
            if (row >= TILE) row = TILE - 1;
            double diff = Math.abs(a[row][aR] - b[row][bL]);
            int heat = (int) Math.min(255, diff * 2000);
            int color = heat << 16 | (255 - heat) << 8;
            for (int px = 0; px < sz * 2; px++) img.setRGB(px, sz + py, color);
        }

        new File("output").mkdirs();
        ImageIO.write(img, "png", new File("output/mod_diag.png"));
    }
}
