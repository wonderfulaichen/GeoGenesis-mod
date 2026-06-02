package com.erosiontest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 多区域边界裂缝测试与修复验证
 *
 * 对比四种方案：
 * 1. 无修复（独立区域 + 直接拼接）— 复现边界裂缝
 * 2. 加权混合修复 — 后处理边界混合
 * 3. TerraForged 滑动窗口 — 以每个单元为中心做侵蚀，只取中心
 * 4. 全局基准 — 整张图一次性侵蚀（理想情况）
 */
public class RegionBoundaryTest {

    public static void main(String[] args) throws Exception {
        int seed = args.length > 0 ? Integer.parseInt(args[0]) : 12345;

        // ========== 配置 ==========
        // 总图 128px = 256 blocks，分成 4x4 个输出单元，每单元 32px = 64 blocks
        int unitPx = 32;                    // 输出单元大小
        int unitsX = 4, unitsZ = 4;         // 4x4 网格
        int finalRes = unitPx * unitsX;     // 128
        int blocksPerPx = 2;

        // 滑动窗口 = 5x5 单元 = 160px，确保粒子60步内碰不到边界（到边界64px > 60px）
        int windowPx = unitPx * 5;          // 160
        int padPx = (windowPx - unitPx) / 2; // 64

        // 独立区域测试用的大区域（2x2，每区域 64px）
        int bigRegionPx = 64;
        int bigRegionsX = 2, bigRegionsZ = 2;
        int bigPadPx = 16;

        Noise noise = new Noise(seed);

        System.out.println("=== Region Boundary Test ===");
        System.out.println("Total: " + finalRes + "px = " + (finalRes * blocksPerPx) + " blocks");
        System.out.println("Unit: " + unitPx + "px, Window: " + windowPx + "px, Pad: " + padPx + "px");

        long t0 = System.currentTimeMillis();

        // 1. 全局基础高度
        float[][] globalBase = new float[finalRes][finalRes];
        for (int z = 0; z < finalRes; z++)
            for (int x = 0; x < finalRes; x++)
                globalBase[z][x] = ErosionPipeline.computeHeight(noise, x * blocksPerPx, z * blocksPerPx);

        long t1 = System.currentTimeMillis();
        System.out.println("Base terrain: " + (t1 - t0) + "ms");

        // 2. 独立大区域侵蚀（模拟模组当前 bug：2x2 区域，镜像 padding，独立随机）
        float[][][][] bigRegions = erodeAllRegions(globalBase, bigRegionsX, bigRegionsZ, bigRegionPx, bigPadPx, seed);
        float[][] noBlend = new float[finalRes][finalRes];
        stitch(noBlend, bigRegions, bigRegionsX, bigRegionsZ, bigRegionPx);

        // 3. 加权混合修复
        float[][] withBlend = new float[finalRes][finalRes];
        stitchWithBlend(withBlend, bigRegions, bigRegionsX, bigRegionsZ, bigRegionPx, 4);

        long t2 = System.currentTimeMillis();
        System.out.println("Independent regions + blend: " + (t2 - t1) + "ms");

        // 4. TerraForged 滑动窗口侵蚀
        float[][] slidingWindow = runSlidingWindow(globalBase, finalRes, unitPx, windowPx, blocksPerPx, seed);

        long t3 = System.currentTimeMillis();
        System.out.println("Sliding window: " + (t3 - t2) + "ms");

        // 5. 全局连续侵蚀基准
        float[][] globalEroded = erodeGlobal(globalBase, finalRes, bigPadPx, seed);

        long t4 = System.currentTimeMillis();
        System.out.println("Global eroded: " + (t4 - t3) + "ms");

        // 6. 量化（用 4x4 单元网格统计边界跳变）
        float seamNoBlend = maxSeamDeltaGrid(noBlend, unitsX, unitsZ, unitPx);
        float seamBlend = maxSeamDeltaGrid(withBlend, unitsX, unitsZ, unitPx);
        float seamSlide = maxSeamDeltaGrid(slidingWindow, unitsX, unitsZ, unitPx);
        float seamGlobal = maxSeamDeltaGrid(globalEroded, unitsX, unitsZ, unitPx);

        System.out.println("Max seam (no blend):   " + String.format("%.5f", seamNoBlend));
        System.out.println("Max seam (blend):      " + String.format("%.5f", seamBlend));
        System.out.println("Max seam (sliding):    " + String.format("%.5f", seamSlide));
        System.out.println("Max seam (global):     " + String.format("%.5f", seamGlobal));

        // 7. 可视化
        saveVisualization(globalBase, noBlend, withBlend, slidingWindow, globalEroded,
                unitsX, unitsZ, unitPx, finalRes, seamNoBlend, seamBlend, seamSlide, seamGlobal);

        System.out.println("Total: " + (System.currentTimeMillis() - t0) + "ms");
    }

    // ========== TerraForged 滑动窗口侵蚀 ==========
    // 关键：所有窗口使用相同的全局种子，粒子行为完全由地形决定。
    // 窗口必须足够大，使粒子在到达边界前自然蒸发（maxLife 60步 < 到边界距离）。
    static float[][] runSlidingWindow(float[][] globalBase, int finalRes,
                                      int unitPx, int windowPx, int blocksPerPx, int seed) {
        float[][] out = new float[finalRes][finalRes];
        int unitsX = finalRes / unitPx;
        int unitsZ = finalRes / unitPx;
        int pad = (windowPx - unitPx) / 2;

        // 所有窗口使用相同的全局种子
        long globalSeedOffset = 0L;
        int drops = 15000;
        int brushR = 6;
        float fallOff = 0.3f, inertia = 0.003f, gravity = 3.0f;
        float erodeSpd = 0.15f, depositSpd = 0.02f;
        float strength = 1.5f;

        for (int uz = 0; uz < unitsZ; uz++) {
            for (int ux = 0; ux < unitsX; ux++) {
                int centerZ = uz * unitPx + unitPx / 2;
                int centerX = ux * unitPx + unitPx / 2;

                // 提取窗口（从全局基础高度，带 clamp）
                float[][] window = new float[windowPx][windowPx];
                for (int z = 0; z < windowPx; z++) {
                    for (int x = 0; x < windowPx; x++) {
                        int gz = centerZ + z - pad;
                        int gx = centerX + x - pad;
                        gz = clamp(gz, 0, finalRes - 1);
                        gx = clamp(gx, 0, finalRes - 1);
                        window[z][x] = globalBase[gz][gx];
                    }
                }

                ErosionPipeline.terraforgedErosion(window, windowPx, drops, strength, brushR,
                        fallOff, inertia, gravity, erodeSpd, depositSpd, globalSeedOffset);
                ErosionPipeline.gaussianBlur(window, windowPx, 1.0f);

                int outZ = uz * unitPx;
                int outX = ux * unitPx;
                for (int z = 0; z < unitPx; z++) {
                    for (int x = 0; x < unitPx; x++) {
                        out[outZ + z][outX + x] = window[pad + z][pad + x];
                    }
                }
            }
        }
        return out;
    }

    // ========== 独立大区域侵蚀（模拟模组当前做法） ==========
    static float[][][][] erodeAllRegions(float[][] globalBase,
                                         int regionsX, int regionsZ, int regionPx, int padPx, int seed) {
        int finalRes = regionPx * regionsX;
        float[][][][] regions = new float[regionsZ][regionsX][regionPx][regionPx];

        for (int rz = 0; rz < regionsZ; rz++) {
            for (int rx = 0; rx < regionsX; rx++) {
                int baseZ = rz * regionPx;
                int baseX = rx * regionPx;

                float[][] region = new float[regionPx][regionPx];
                for (int z = 0; z < regionPx; z++)
                    for (int x = 0; x < regionPx; x++)
                        region[z][x] = globalBase[baseZ + z][baseX + x];

                float[][] buf = padGridMirror(region, regionPx, padPx);
                int bufSize = regionPx + padPx * 2;

                long seedOffset = hash(rz * 131 + rx * 257, rz * 331 + rx * 541);
                int drops = 15000;
                int brushR = 6;
                float fallOff = 0.3f, inertia = 0.003f, gravity = 3.0f;
                float erodeSpd = 0.15f, depositSpd = 0.02f;
                float strength = 1.5f;

                ErosionPipeline.terraforgedErosion(buf, bufSize, drops, strength, brushR,
                        fallOff, inertia, gravity, erodeSpd, depositSpd, seedOffset);
                ErosionPipeline.gaussianBlur(buf, bufSize, 1.0f);

                for (int z = 0; z < regionPx; z++)
                    for (int x = 0; x < regionPx; x++)
                        regions[rz][rx][z][x] = buf[z + padPx][x + padPx];
            }
        }
        return regions;
    }

    // ========== 多级全局侵蚀基准 ==========
    // 使用与ErosionPipeline当前相同的四层参数
    static float[][] erodeGlobal(float[][] globalBase, int finalRes, int padPx, int seed) {
        int[] scaleRes = {128, 256, 512, 1024};
        int[] dropsPer = {35000, 35000, 35000, 15000};
        float[] eroStr = {1.2f, 1.5f, 2.0f, 2.5f};
        int[] brushRs = {12, 6, 4, 2};

        // 从128开始，逐步映射到finalRes
        float[][] h = new float[scaleRes[0]][scaleRes[0]];
        for (int z = 0; z < scaleRes[0]; z++)
            for (int x = 0; x < scaleRes[0]; x++) {
                float wx = (float)x / scaleRes[0] * finalRes;
                float wz = (float)z / scaleRes[0] * finalRes;
                h[z][x] = globalBase[Math.min((int)wz, finalRes-1)][Math.min((int)wx, finalRes-1)];
            }

        int prevRes = scaleRes[0];
        for (int si = 0; si < scaleRes.length; si++) {
            int curRes = scaleRes[si];
            if (curRes != prevRes) {
                h = ErosionPipeline.bicubicUpsampleGrid(h, prevRes, curRes);
                prevRes = curRes;
            }
            int drops = (int)Math.round(dropsPer[si] * 1.5f);
            if (drops > 0) {
                int pad = Math.max(brushRs[si] * 2, 8);
                float[][] p = ErosionPipeline.padGridMirror(h, curRes, pad);
                ErosionPipeline.terraforgedErosion(p, curRes + pad*2, drops,
                        eroStr[si] * 1.5f, brushRs[si], 0.5f, 0.001f, 2.5f, 0.35f, 0.04f);
                for (int z = 0; z < curRes; z++) System.arraycopy(p[z+pad], pad, h[z], 0, curRes);
            }
        }

        // 缩放到finalRes
        if (scaleRes[scaleRes.length-1] != finalRes) {
            h = ErosionPipeline.bicubicUpsampleGrid(h, scaleRes[scaleRes.length-1], finalRes);
        }
        return h;
    }

    static void stitch(float[][] out, float[][][][] regions, int regionsX, int regionsZ, int regionPx) {
        for (int rz = 0; rz < regionsZ; rz++)
            for (int rx = 0; rx < regionsX; rx++)
                for (int z = 0; z < regionPx; z++)
                    for (int x = 0; x < regionPx; x++)
                        out[rz * regionPx + z][rx * regionPx + x] = regions[rz][rx][z][x];
    }

    static void stitchWithBlend(float[][] out, float[][][][] regions,
                                int regionsX, int regionsZ, int regionPx, int blendWidth) {
        int finalRes = regionPx * regionsX;
        for (int z = 0; z < finalRes; z++) {
            for (int x = 0; x < finalRes; x++) {
                int rz = z / regionPx;
                int rx = x / regionPx;
                int lz = z - rz * regionPx;
                int lx = x - rx * regionPx;
                float value = regions[rz][rx][lz][lx];
                float sum = value;
                float wSum = 1.0f;

                int dl = lx, dr = regionPx - 1 - lx;
                int dt = lz, db = regionPx - 1 - lz;

                if (dl < blendWidth && rx > 0) {
                    float w = 0.5f * (1f + (float) Math.cos((float) dl / blendWidth * Math.PI));
                    sum += regions[rz][rx - 1][lz][regionPx - 1] * w; wSum += w;
                }
                if (dr < blendWidth && rx + 1 < regionsX) {
                    float w = 0.5f * (1f + (float) Math.cos((float) dr / blendWidth * Math.PI));
                    sum += regions[rz][rx + 1][lz][0] * w; wSum += w;
                }
                if (dt < blendWidth && rz > 0) {
                    float w = 0.5f * (1f + (float) Math.cos((float) dt / blendWidth * Math.PI));
                    sum += regions[rz - 1][rx][regionPx - 1][lx] * w; wSum += w;
                }
                if (db < blendWidth && rz + 1 < regionsZ) {
                    float w = 0.5f * (1f + (float) Math.cos((float) db / blendWidth * Math.PI));
                    sum += regions[rz + 1][rx][0][lx] * w; wSum += w;
                }
                out[z][x] = sum / wSum;
            }
        }
    }

    // 基于小单元网格统计边界跳变
    static float maxSeamDeltaGrid(float[][] map, int unitsX, int unitsZ, int unitPx) {
        int finalRes = unitPx * unitsX;
        float max = 0;
        for (int uz = 0; uz < unitsZ; uz++) {
            for (int ux = 0; ux < unitsX; ux++) {
                for (int z = 0; z < unitPx; z++) {
                    for (int x = 0; x < unitPx; x++) {
                        int gz = uz * unitPx + z;
                        int gx = ux * unitPx + x;
                        if (x == unitPx - 1 && ux + 1 < unitsX && gx + 1 < finalRes)
                            max = Math.max(max, Math.abs(map[gz][gx] - map[gz][gx + 1]));
                        if (z == unitPx - 1 && uz + 1 < unitsZ && gz + 1 < finalRes)
                            max = Math.max(max, Math.abs(map[gz][gx] - map[gz + 1][gx]));
                    }
                }
            }
        }
        return max;
    }

    // ========== 可视化 ==========
    static void saveVisualization(float[][] base, float[][] noBlend, float[][] withBlend,
                                  float[][] sliding, float[][] globalEroded,
                                  int unitsX, int unitsZ, int unitPx, int finalRes,
                                  float seamNo, float seamBlend, float seamSlide, float seamGlobal) throws Exception {
        int gap = 4;
        int profileH = 110;
        int imgW = finalRes * 2 + gap;
        int imgH = finalRes * 2 + gap + profileH + 35;
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < imgH; y++)
            for (int x = 0; x < imgW; x++)
                img.setRGB(x, y, 0x111111);

        float minH = 1, maxH = 0;
        for (int z = 0; z < finalRes; z++)
            for (int x = 0; x < finalRes; x++) {
                minH = Math.min(minH, base[z][x]);
                maxH = Math.max(maxH, Math.max(noBlend[z][x], Math.max(withBlend[z][x],
                        Math.max(sliding[z][x], globalEroded[z][x]))));
            }
        float range = Math.max(maxH - minH, 0.001f);

        java.util.function.BiPredicate<Integer, Integer> isUnitEdge = (x, z) ->
                (x % unitPx == 0) || (z % unitPx == 0);

        // 左上：无修复
        for (int z = 0; z < finalRes; z++)
            for (int x = 0; x < finalRes; x++) {
                int c = ErosionPipeline.toColor(noBlend[z][x], minH, range);
                if (isUnitEdge.test(x, z)) c = 0xFF0000;
                img.setRGB(x, z, c);
            }

        // 右上：滑动窗口
        for (int z = 0; z < finalRes; z++)
            for (int x = 0; x < finalRes; x++) {
                int c = ErosionPipeline.toColor(sliding[z][x], minH, range);
                if (isUnitEdge.test(x, z)) c = 0x00FFFF;
                img.setRGB(x + finalRes + gap, z, c);
            }

        // 左下：加权混合
        for (int z = 0; z < finalRes; z++)
            for (int x = 0; x < finalRes; x++) {
                int c = ErosionPipeline.toColor(withBlend[z][x], minH, range);
                if (isUnitEdge.test(x, z)) c = 0x00FF00;
                img.setRGB(x, z + finalRes + gap, c);
            }

        // 右下：全局基准
        for (int z = 0; z < finalRes; z++)
            for (int x = 0; x < finalRes; x++) {
                int c = ErosionPipeline.toColor(globalEroded[z][x], minH, range);
                if (isUnitEdge.test(x, z)) c = 0xFFFF00;
                img.setRGB(x + finalRes + gap, z + finalRes + gap, c);
            }

        // 标签
        drawLabel(img, "NO BLEND (red=edge)", 2, 2, 0xFFFFFF);
        drawLabel(img, "SLIDING WINDOW (cyan=edge)", finalRes + gap + 2, 2, 0xFFFFFF);
        drawLabel(img, "BLEND FIX (green=edge)", 2, finalRes + gap + 2, 0xFFFFFF);
        drawLabel(img, "GLOBAL IDEAL (yellow=edge)", finalRes + gap + 2, finalRes + gap + 2, 0xFFFFFF);

        // 底部剖面图
        int profileZ = (unitsZ / 2) * unitPx;
        int graphTop = finalRes * 2 + gap + 12;
        int graphLeft = 40;
        int graphRight = imgW - 10;
        int graphW = graphRight - graphLeft;
        int graphBottom = graphTop + profileH - 20;
        int graphH = graphBottom - graphTop;

        for (int y = graphTop; y <= graphBottom; y++)
            for (int x = graphLeft; x <= graphRight; x++)
                img.setRGB(x, y, 0x1a1a1a);

        for (int tick = 0; tick <= 4; tick++) {
            int py = graphBottom - tick * graphH / 4;
            float val = minH + tick * range / 4;
            for (int px = graphLeft - 3; px <= graphLeft; px++)
                if (px >= 0 && py >= 0 && px < imgW && py < imgH)
                    img.setRGB(px, py, 0x444444);
            drawLabel(img, String.format("%.2f", val), 2, py - 4, 0x666666);
        }

        for (int ux = 0; ux <= unitsX; ux++) {
            int px = graphLeft + ux * unitPx * graphW / finalRes;
            for (int py = graphBottom + 1; py <= graphBottom + 4; py++)
                if (px >= 0 && py >= 0 && px < imgW && py < imgH)
                    img.setRGB(px, py, 0xFF3333);
        }

        int[] prevY = {-1, -1, -1, -1, -1};
        int[] colors = {0x4488FF, 0xFF4444, 0x44FF44, 0x00FFFF, 0xFFFF00};
        String[] names = {"Base", "NoBlend", "Blend", "Slide", "Global"};

        for (int x = 0; x < finalRes; x++) {
            float[] vals = {
                base[profileZ][x], noBlend[profileZ][x], withBlend[profileZ][x],
                sliding[profileZ][x], globalEroded[profileZ][x]
            };
            int px = graphLeft + x * graphW / finalRes;
            for (int i = 0; i < vals.length; i++) {
                int py = graphBottom - (int) ((vals[i] - minH) / range * graphH);
                py = clamp(py, graphTop, graphBottom);
                if (x > 0 && prevY[i] >= 0)
                    drawLine(img, px - 1, prevY[i], px, py, colors[i]);
                prevY[i] = py;
            }
            if (x % unitPx == 0) {
                for (int py = graphTop; py <= graphBottom; py++)
                    if (px >= 0 && py >= 0 && px < imgW && py < imgH)
                        if (img.getRGB(px, py) == 0x1a1a1a)
                            img.setRGB(px, py, 0x332222);
            }
        }

        drawLabel(img, "Profile z=" + profileZ + "  Blue=Base Red=NoBlend Green=Blend Cyan=Slide Yellow=Global",
                graphLeft, graphTop - 10, 0xCCCCCC);
        drawLabel(img, "Seam: " + String.format("%.4f", seamNo) + " / " + String.format("%.4f", seamBlend)
                + " / " + String.format("%.4f", seamSlide) + " / " + String.format("%.4f", seamGlobal),
                graphLeft, graphBottom + 6, 0xFFAA66);

        File outDir = new File("output");
        outDir.mkdirs();
        int runNum = 0;
        File file;
        do {
            String suffix = runNum == 0 ? "" : "_v" + runNum;
            file = new File(outDir, "region_boundary" + suffix + ".png");
            runNum++;
        } while (file.exists());
        ImageIO.write(img, "png", file);
        System.out.println("Saved: " + file.getAbsolutePath());
    }

    static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    static void drawLabel(BufferedImage img, String text, int x, int y, int color) {
        for (int i = 0; i < text.length(); i++)
            for (int dy = 0; dy < 9; dy++)
                for (int dx = 0; dx < 7; dx++) {
                    int px = x + i * 8 + dx, py = y + dy;
                    if (px < img.getWidth() && py < img.getHeight())
                        img.setRGB(px, py, color);
                }
    }

    static void drawLine(BufferedImage img, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0, y = y0;
        while (true) {
            if (x >= 0 && x < img.getWidth() && y >= 0 && y < img.getHeight())
                img.setRGB(x, y, color);
            if (x == x1 && y == y1) break;
            int e2 = err * 2;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
    }

    static long hash(int a, int b) {
        long h = a * 0x9e3779b9L + b * 0x9e3779b9L * 31;
        h = (h ^ (h >>> 16)) * 0x85ebca6bL;
        h = h ^ (h >>> 13);
        h = h * 0xc2b2ae35L;
        h = h ^ (h >>> 16);
        return h;
    }

    static float[][] padGridMirror(float[][] src, int size, int pad) {
        int newSize = size + pad * 2;
        float[][] dst = new float[newSize][newSize];
        for (int z = 0; z < newSize; z++) {
            for (int x = 0; x < newSize; x++) {
                int sz, sx;
                if (z < pad) sz = pad - z - 1;
                else if (z >= size + pad) sz = size - 1 - (z - (size + pad));
                else sz = z - pad;
                if (x < pad) sx = pad - x - 1;
                else if (x >= size + pad) sx = size - 1 - (x - (size + pad));
                else sx = x - pad;
                sz = clamp(sz, 0, size - 1);
                sx = clamp(sx, 0, size - 1);
                dst[z][x] = src[sz][sx];
            }
        }
        return dst;
    }
}
