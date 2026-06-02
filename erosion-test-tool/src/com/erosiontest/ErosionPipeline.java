package com.erosiontest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class ErosionPipeline {

    static int seed = 12345;
    static int scale = 8;
    static float strength = 1.5f;
    static float branchFactor = 0.3f;
    static float minCatchment = 0.3f;
    static float hardnessContrast = 0.5f;
    static float widthMultiplier = 0.5f;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) seed = Integer.parseInt(args[0]);
        if (args.length > 1) scale = Integer.parseInt(args[1]);
        if (args.length > 2) strength = Float.parseFloat(args[2]);
        if (args.length > 3) branchFactor = Float.parseFloat(args[3]);
        if (args.length > 4) minCatchment = Float.parseFloat(args[4]);
        if (args.length > 5) hardnessContrast = Float.parseFloat(args[5]);

        Noise noise = new Noise(seed);
        Erosion erosion = new Erosion(noise, seed);

        int[] res = {128, 256, 512, 1024};
        int[] dropsPer = {35000, 35000, 35000, 15000};  // 1024=22500滴
        float[] eroStr = {1.2f, 1.5f, 2.0f, 2.5f};

        System.out.println("=== Multi-Scale Erosion ===");
        System.out.println("Seed: " + seed);

        long t0 = System.currentTimeMillis();

        float[][] base = new float[res[0]][res[0]];
        for (int z = 0; z < res[0]; z++)
            for (int x = 0; x < res[0]; x++)
                base[z][x] = computeHeight(noise, x * scale, z * scale);
        float[][] before = cloneGrid(base);
        long t1 = System.currentTimeMillis();
        System.out.println("Base: " + (t1 - t0) + "ms");

        float[][] prevHeightmap = base;
        int prevRes = res[0];

        // Multi-scale brush erosion loop with padding to avoid boundary artifacts
        for (int si = 0; si < res.length; si++) {
            int curRes = res[si];
            if (curRes != prevRes) {
                float[][] upsampled = bicubicUpsampleGrid(prevHeightmap, prevRes, curRes);
                prevHeightmap = new float[curRes][curRes];
                for (int z = 0; z < curRes; z++)
                    for (int x = 0; x < curRes; x++)
                        prevHeightmap[z][x] = upsampled[z][x];
                prevRes = curRes;
            }

            int curDrops = (int)Math.round(dropsPer[si] * strength);

            if (curDrops > 0) {
                int brushR = si == 0 ? 12 : si == 1 ? 6 : si == 2 ? 4 : 2;  // 128=12, 256=6, 512=4, 1024=2
                float fallOff = si == 0 ? 0.5f : 0.3f;
                float scaleInertia = si == 0 ? 0.001f : 0.003f;
                float scaleGravity = si == 0 ? 2.5f : 3.0f;
                float erodeSpd = si == 0 ? 0.35f : 0.15f;  // 256降速少挖
                float depositSpd = si == 0 ? 0.04f : 0.02f;
                // Add padding: expand grid with mirrored edges, erode, then crop back
                int pad = Math.max(brushR * 2, 8);
                float[][] padded = padGridMirror(prevHeightmap, curRes, pad);
                terraforgedErosion(padded, curRes + pad * 2, curDrops, eroStr[si] * strength, brushR, fallOff, scaleInertia, scaleGravity, erodeSpd, depositSpd);
                // Crop back to original size
                for (int z = 0; z < curRes; z++)
                    for (int x = 0; x < curRes; x++)
                        prevHeightmap[z][x] = padded[z + pad][x + pad];
                // Light Gaussian blur to smooth deposition noise
                gaussianBlur(prevHeightmap, curRes, 1.0f);
            }
            long tn = System.currentTimeMillis();
            System.out.println("Scale " + curRes + ": " + (tn - t1) + "ms (drops=" + curDrops + ", str=" + eroStr[si] + ")");
        }

        float[][] h512 = prevHeightmap;

        long tFinal = System.currentTimeMillis();
        System.out.println("Final: " + (tFinal - t1) + "ms");

        // Clamp
        int finalRes = res[res.length - 1];
        for (int z = 0; z < finalRes; z++)
            for (int x = 0; x < finalRes; x++)
                h512[z][x] = clamp(h512[z][x], 0f, 1f);

        float[][] beforeFinal = bicubicUpsampleGrid(before, res[0], finalRes);
        // Bicubic can overshoot outside [0,1], clamp it
        for (int z = 0; z < finalRes; z++)
            for (int x = 0; x < finalRes; x++)
                beforeFinal[z][x] = clamp(beforeFinal[z][x], 0f, 1f);

        float minH = 1, maxH = 0, maxDelta = 0;
        int nanBefore = 0, nanAfter = 0;
        for (int z = 0; z < finalRes; z++)
            for (int x = 0; x < finalRes; x++) {
                float bf = beforeFinal[z][x];
                float af = h512[z][x];
                if (Float.isNaN(bf) || Float.isInfinite(bf)) { beforeFinal[z][x] = 0.5f; nanBefore++; }
                if (Float.isNaN(af) || Float.isInfinite(af)) { h512[z][x] = 0.5f; nanAfter++; }
                minH = Math.min(minH, Math.min(beforeFinal[z][x], h512[z][x]));
                maxH = Math.max(maxH, Math.max(beforeFinal[z][x], h512[z][x]));
                maxDelta = Math.max(maxDelta, Math.abs(h512[z][x] - beforeFinal[z][x]));
            }
        if (nanBefore > 0) System.out.println("WARN: " + nanBefore + " NaN in beforeFinal");
        if (nanAfter > 0) System.out.println("WARN: " + nanAfter + " NaN in h512");
        float range = Math.max(maxH - minH, 0.001f);
        System.out.println("Height range: " + minH + " ~ " + maxH + "  delta: " + maxDelta);

        // Region boundaries: every 64 blocks in world space
        // 512px covers 128*8 = 1024 blocks → 1px = 2 blocks → region = 32px
        float blocksPerPx = (float)(res[0] * scale) / finalRes;
        int regionPx = (int)(64 / blocksPerPx + 0.5f);
        int padPx = (int)(16 / blocksPerPx + 0.5f);

        int gap = 4;
        int detailSize = finalRes / 4;
        int zoomScale = 4;
        int detailStartX = finalRes / 2 - detailSize / 2;
        int detailStartZ = finalRes / 2 - detailSize / 2;
        // Profile: 2D cross-section along horizontal center line
        int profileH = 120;
        int profileY = finalRes + gap + detailSize * zoomScale + 120;
        int imgW = finalRes + gap + finalRes;
        int imgH = profileY + profileH + 20;
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);

        // Left column: Before full + After full stacked vertically
        for (int z = 0; z < finalRes; z++)
            for (int x = 0; x < finalRes; x++) {
                boolean regionEdge = (x % regionPx == 0) || (z % regionPx == 0);
                boolean padEdge = (x % regionPx == padPx) || (x % regionPx == regionPx - padPx) ||
                                  (z % regionPx == padPx) || (z % regionPx == regionPx - padPx);

                int cb = toColor(beforeFinal[z][x], minH, range);
                int ca = toColor(h512[z][x], minH, range);

                // Before (top-left): region boundary in red, pad zone in dark
                if (regionEdge) cb = 0xFF0000;
                else if (padEdge) cb = darken(cb);
                img.setRGB(x, z, cb);

                // After (bottom-left): region boundary in red
                if (regionEdge) ca = 0xFF0000;
                else if (padEdge) ca = darken(ca);
                img.setRGB(x, z + finalRes + gap, ca);
            }

        // Right top: delta
        for (int z = 0; z < finalRes; z++)
            for (int x = 0; x < finalRes; x++) {
                float d = h512[z][x] - beforeFinal[z][x];
                img.setRGB(x + finalRes + gap, z, deltaColor(d, maxDelta));
            }

        // Right bottom: zoomed detail (4x)
        int dxOff = finalRes + gap;
        int dzOff = finalRes + gap;
        for (int dz = 0; dz < detailSize; dz++)
            for (int dx = 0; dx < detailSize; dx++) {
                int sz = detailStartZ + dz;
                int sx = detailStartX + dx;
                float h = h512[sz][sx];
                float b = beforeFinal[sz][sx];
                float d = h - b;

                for (int subZ = 0; subZ < zoomScale; subZ++)
                    for (int subX = 0; subX < zoomScale; subX++) {
                        int px = dxOff + dx * zoomScale + subX;
                        int py = dzOff + dz * zoomScale + subZ;
                        if (px < imgW && py < imgH) {
                            if (sx % regionPx == 0 || sz % regionPx == 0) {
                                img.setRGB(px, py, 0xFF0000);
                            } else {
                                // 右下角放大图：直接显示高度颜色，不叠加delta着色
                                // 避免混合计算产生的黑色噪点
                                img.setRGB(px, py, toColor(h, minH, range));
                            }
                        }
                    }
            }

        // Annotations
        drawLabel(img, "Before", 2, 2, 0xFFFFFF);
        drawLabel(img, "After", 2, finalRes + gap + 2, 0xFFFFFF);
        drawLabel(img, "Delta", finalRes + gap + 2, 2, 0xFFFFFF);
        drawLabel(img, "Zoom 4x", finalRes + gap + 2, finalRes + gap + 2, 0xFFFFFF);
        drawLabel(img, "Red=region", finalRes + gap + 2, finalRes + gap + 14, 0xFF6666);
        // Color bar
        int barX = finalRes + gap + 2;
        int barY = finalRes + gap + detailSize * zoomScale + 20;
        int barW = finalRes - 4;
        int barH = 14;
        for (int bi = 0; bi < barW; bi++) {
            float t = (float) bi / barW;
            int c = toColor(minH + t * range, minH, range);
            for (int bj = 0; bj < barH; bj++) {
                int px = barX + bi;
                int py = barY + bj;
                if (px < imgW && py < imgH) img.setRGB(px, py, c);
            }
        }
        drawLabel(img, String.format("%.2f", minH), barX, barY + barH + 2, 0xFFFFFF);
        drawLabel(img, String.format("%.2f", maxH), barX + barW - 50, barY + barH + 2, 0xFFFFFF);
        // Delta color bar
        int dBarX = 2;
        int dBarY = barY + barH + 20;
        int dBarW = finalRes - 4;
        int dBarH = 14;
        for (int bi = 0; bi < dBarW; bi++) {
            float t = (float) bi / dBarW * 2 - 1;
            int c = deltaColor(t * maxDelta, maxDelta);
            for (int bj = 0; bj < dBarH; bj++) {
                int px = dBarX + bi;
                int py = dBarY + bj;
                if (px < imgW && py < imgH) img.setRGB(px, py, c);
            }
        }
        drawLabel(img, String.format("-%.3f", maxDelta), dBarX, dBarY + dBarH + 2, 0x00FF00);
        drawLabel(img, String.format("+%.3f", maxDelta), dBarX + dBarW - 50, dBarY + dBarH + 2, 0xFF0000);

        // ===== 2D PROFILE: horizontal cross-section through center row =====
        int profileCenterZ = finalRes / 2;
        int graphLeft = 40;
        int graphRight = imgW - 10;
        int graphW = graphRight - graphLeft;
        int graphTop = profileY + 5;
        int graphBottom = profileY + profileH - 20;
        int graphH = graphBottom - graphTop;

        float valMin = minH;
        float valMax = maxH;
        float valRange = Math.max(valMax - valMin, 0.01f);

        // Background
        for (int py = graphTop; py <= graphBottom; py++)
            for (int px = graphLeft; px <= graphRight; px++)
                img.setRGB(px, py, 0x111111);

        // Y-axis labels
        for (int tick = 0; tick <= 4; tick++) {
            int py = graphBottom - tick * graphH / 4;
            float val = valMin + tick * valRange / 4;
            // Tick line
            for (int px = graphLeft - 3; px <= graphLeft; px++)
                if (px >= 0 && py >= 0 && px < imgW && py < imgH)
                    img.setRGB(px, py, 0x444444);
            drawLabel(img, String.format("%.2f", val), 2, py - 4, 0x666666);
        }

        // Region boundary markers on x-axis
        int regionBlocks = (int)(64 / blocksPerPx + 0.5f);
        for (int rx = 0; rx <= finalRes; rx += regionBlocks) {
            int px = graphLeft + rx * graphW / finalRes;
            for (int py = graphBottom + 1; py <= graphBottom + 4; py++)
                if (px >= 0 && py >= 0 && px < imgW && py < imgH)
                    img.setRGB(px, py, 0xFF3333);
        }

        // Draw profiles (before=blue, after=green)
        int prevBeforeY = -1, prevAfterY = -1;
        for (int x = 0; x < finalRes; x++) {
            float bf = beforeFinal[profileCenterZ][x];
            float af = h512[profileCenterZ][x];
            int px = graphLeft + x * graphW / finalRes;
            int by = graphBottom - (int)((bf - valMin) / valRange * graphH);
            int ay = graphBottom - (int)((af - valMin) / valRange * graphH);
            by = clamp(by, graphTop, graphBottom);
            ay = clamp(ay, graphTop, graphBottom);

            // Before (blue)
            if (x > 0 && prevBeforeY >= 0)
                drawLine(img, px - 1, prevBeforeY, px, by, 0x4488FF);
            // After (green)
            if (x > 0 && prevAfterY >= 0)
                drawLine(img, px - 1, prevAfterY, px, ay, 0x44FF44);

            prevBeforeY = by;
            prevAfterY = ay;

            // Region boundary vertical reference lines
            if (x % regionBlocks == 0) {
                for (int py = graphTop; py <= graphBottom; py++)
                    if (px >= 0 && py >= 0 && px < imgW && py < imgH)
                        if (img.getRGB(px, py) == 0x111111)
                            img.setRGB(px, py, 0x332222);
            }
        }

        // Labels
        drawLabel(img, "Profile (center row) - Blue:Before  Green:After  Red=region", graphLeft, profileY + 1, 0xCCCCCC);
        drawLabel(img, "← left", graphLeft, graphBottom + 6, 0x888888);
        drawLabel(img, "right →", graphRight - 45, graphBottom + 6, 0x888888);

        File outDir = new File("output");
        outDir.mkdirs();
        int runNum = 0;
        File file;
        do {
            String suffix = runNum == 0 ? "" : "_v" + runNum;
            file = new File(outDir, "erosion_s" + seed + suffix + ".png");
            runNum++;
        } while (file.exists());
        ImageIO.write(img, "png", file);
        System.out.println("Saved: " + file.getAbsolutePath());
        System.out.println("Max delta: " + String.format("%.4f", maxDelta));
        System.out.println("Total: " + (System.currentTimeMillis() - t0) + "ms");
    }

    // ===== FLOW ACCUMULATION (simple upslope count) =====
    static float[][] computeFlowAccumulation(float[][] height, int size) {
        float[][] flow = new float[size][size];
        // Count how many higher neighbors drain into each cell
        for (int z = 1; z < size - 1; z++)
            for (int x = 1; x < size - 1; x++) {
                if (height[z][x] <= 0.01f) continue;
                int upslope = 0;
                float h = height[z][x];
                for (int dz = -1; dz <= 1; dz++)
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) continue;
                        if (height[z + dz][x + dx] > h + 0.001f) upslope++;
                    }
                flow[z][x] = 1 + upslope;
            }
        return flow;
    }

    // ===== TERRAFORGED EXACT HYDRAULIC EROSION =====
    // Continuous gradient + inertia + circular brush at current cell
    static void terraforgedErosion(float[][] map, int size,
                                     int drops, float strength,
                                     int radius, float fallOff,
                                     float inertia, float gravity,
                                     float erodeSpeed, float depositSpeed) {
        terraforgedErosion(map, size, drops, strength, radius, fallOff,
                inertia, gravity, erodeSpeed, depositSpeed, 0L);
    }

    static void terraforgedErosion(float[][] map, int size,
                                     int drops, float strength,
                                     int radius, float fallOff,
                                     float inertia, float gravity,
                                     float erodeSpeed, float depositSpeed,
                                     long seedOffset) {
        float capFactor = 10f;
        float minCap = 0.005f;
        float evaporate = 0.35f;

        // Flatten map for faster access
        float[] flat = new float[size * size];
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                flat[z * size + x] = map[z][x];

        // Precompute shared brush offsets (顶点计算)
        int r2 = radius * radius;
        int[] bOff = new int[(2*radius+1)*(2*radius+1)];
        float[] bWgt = new float[(2*radius+1)*(2*radius+1)];
        int bn = 0;
        for (int dy = -radius; dy <= radius; dy++)
            for (int dx = -radius; dx <= radius; dx++) {
                float d2 = dx*dx + dy*dy;
                if (d2 < r2) {
                    bOff[bn] = dy * size + dx;
                    bWgt[bn] = 1f - (float)Math.sqrt(d2)/radius;
                    bn++;
                }
            }
        { float s=0; for(int i=0;i<bn;i++)s+=bWgt[i]; for(int i=0;i<bn;i++)bWgt[i]/=s; }

        for (int i = 0; i < drops; i++) {
            long h = hash(i * 31 + (int)seedOffset, i * 73 + (int)(seedOffset >>> 32));
            float px = 1 + ((h & 0xFFFFFFFFL) % (size - 4)) + 2;
            float py = 1 + (((h >>> 32) & 0xFFFFFFFFL) % (size - 4)) + 2;

            float dirX = 0, dirY = 0, sed = 0, spd = 1f, wat = 1f;

            for (int step = 0; step < 60; step++) {
                int ix = (int) px, iy = (int) py;
                if (ix < 1 || ix >= size - 2 || iy < 1 || iy >= size - 2) break;
                int idx = iy * size + ix;

                float fx = px - ix, fy = py - iy;

                // Bilinear height at current position
                float hNW = flat[idx], hNE = flat[idx + 1];
                float hSW = flat[idx + size], hSE = flat[idx + size + 1];
                float h0 = hNW * (1 - fx) * (1 - fy) + hNE * fx * (1 - fy)
                         + hSW * (1 - fx) * fy + hSE * fx * fy;
                if (h0 <= 0.01f) break;

                // Gradient via bilinear
                float gx = (hNE - hNW) * (1 - fy) + (hSE - hSW) * fy;
                float gy = (hSW - hNW) * (1 - fx) + (hSE - hNE) * fx;
                float glen = (float) Math.sqrt(gx * gx + gy * gy);
                if (glen < 1e-12f) break;

                // Inertia
                dirX = dirX * inertia - gx * (1 - inertia);
                dirY = dirY * inertia - gy * (1 - inertia);
                float dlen = (float) Math.sqrt(dirX * dirX + dirY * dirY);
                if (dlen < 1e-12f) break;
                dirX /= dlen; dirY /= dlen;

                // Move
                float npx = px + dirX;
                float npy = py + dirY;
                if (npx < 1 || npx >= size - 2 || npy < 1 || npy >= size - 2) break;

                int nix = (int) npx, niy = (int) npy;
                float fnx = npx - nix, fny = npy - niy;
                int nidx = niy * size + nix;

                // New height
                float h1 = flat[nidx] * (1 - fnx) * (1 - fny) + flat[nidx + 1] * fnx * (1 - fny)
                         + flat[nidx + size] * (1 - fnx) * fny + flat[nidx + size + 1] * fnx * fny;

                float dh = (h1 - h0) * Math.min(1, h0 / fallOff);

                // Sediment capacity
                float cap = Math.max(-dh * spd * wat * capFactor * strength, minCap);

                if (sed > cap || dh > 0) {
                    float dep = dh > 0 ? Math.min(dh, sed) : (sed - cap) * depositSpeed;
                    sed -= dep;
                    flat[idx] += dep * (1 - fx) * (1 - fy);
                    flat[idx + 1] += dep * fx * (1 - fy);
                    flat[idx + size] += dep * (1 - fx) * fy;
                    flat[idx + size + 1] += dep * fx * fy;
                } else {
                    float eroAmt = Math.min((cap - sed) * erodeSpeed, -dh);
                    for (int b = 0; b < bn; b++) {
                        int bi = idx + bOff[b];
                        if (bi >= 0 && bi < size*size) {
                            float delta = Math.min(flat[bi], eroAmt * bWgt[b]);
                            flat[bi] -= delta;
                            sed += delta;
                        }
                    }
                }

                px = npx; py = npy;
                spd = (float) Math.sqrt(spd * spd + dh * gravity);
                if (spd <= 0) break;
                wat *= (1 - evaporate);
            }
        }

        // Copy back and clamp
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++) {
                float v = Math.max(0, Math.min(1, flat[z * size + x]));
                map[z][x] = Float.isNaN(v) || Float.isInfinite(v) ? 0.5f : v;
            }
    }

    // ===== LOCKED EROSION WRAPPER =====
    // 侵蚀后恢复锁定像素不变
    static void terraforgedErosionLocked(float[][] map, int size,
                                          int drops, float strength,
                                          int radius, float fallOff,
                                          float inertia, float gravity,
                                          float erodeSpeed, float depositSpeed,
                                          long seedOffset,
                                          int worldX, int worldZ, int pad, int baseSize, int worldScale,
                                          boolean[][] locked) {
        // 保存锁定像素原值
        float[] saved = null;
        if (locked != null) {
            saved = new float[size * size];
            for (int z = 0; z < size; z++)
                for (int x = 0; x < size; x++)
                    if (locked[z][x]) saved[z*size+x] = map[z][x];
        }
        // 标准侵蚀
        terraforgedErosion(map, size, drops, strength, radius, fallOff,
                inertia, gravity, erodeSpeed, depositSpeed, seedOffset);
        // 恢复锁定像素
        if (saved != null) {
            for (int z = 0; z < size; z++)
                for (int x = 0; x < size; x++)
                    if (locked[z][x]) map[z][x] = saved[z*size+x];
        }
    }

    // ===== BIASED EROSION: ridge-shoulder biasing for higher levels =====
    static void terraforgedErosionBiased(float[][] map, int size,
                                          int drops, float strength,
                                          int radius, float fallOff,
                                          float inertia, float gravity,
                                          float erodeSpeed, float depositSpeed,
                                          long seedOffset) {
        float capFactor = 10f, minCap = 0.005f, evaporate = 0.35f;
        float[] flat = new float[size * size];
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) flat[z*size+x] = map[z][x];

        int r2 = radius*radius;
        int[] bOff = new int[(2*radius+1)*(2*radius+1)];
        float[] bWgt = new float[(2*radius+1)*(2*radius+1)];
        int bn = 0;
        for (int dy=-radius; dy<=radius; dy++) for (int dx=-radius; dx<=radius; dx++) {
            float d2 = dx*dx+dy*dy;
            if (d2<r2) { bOff[bn]=dy*size+dx; bWgt[bn]=1f-(float)Math.sqrt(d2)/radius; bn++; }
        }
        { float s=0; for(int i=0;i<bn;i++)s+=bWgt[i]; for(int i=0;i<bn;i++)bWgt[i]/=s; }

        // Height range and RNG for biased placement
        float hMin=1,hMax=0; for(int j=0;j<size*size;j++){if(flat[j]<hMin)hMin=flat[j];if(flat[j]>hMax)hMax=flat[j];}
        float hRange=Math.max(hMax-hMin,0.001f);
        java.util.Random rng=new java.util.Random(seedOffset+42);

        for (int i = 0; i < drops; i++) {
            // Ridge-shoulder biased placement
            float px=1, py=1; int trial;
            for (trial=0; trial<10; trial++) {
                long h = hash(i*31+(int)seedOffset+trial*1000, i*73+(int)(seedOffset>>>32)+trial*2000);
                px = 1 + ((h & 0xFFFFFFFFL) % (size-4)) + 2;
                py = 1 + (((h >>> 32) & 0xFFFFFFFFL) % (size-4)) + 2;
                int sidx = (int)py*size+(int)px;
                float norm = (flat[sidx]-hMin)/hRange;
                // Simple height bias: peak at ~0.7, valley low, ridge moderate
                float bias = Math.max(0.2f, 0.8f - (norm-0.7f)*(norm-0.7f)*1.5f);
                if (rng.nextFloat()<bias) break;
            }
            if (trial>=10) continue;

            float dirX=0, dirY=0, sed=0, spd=1f, wat=1f;
            for (int step=0; step<60; step++) {
                int ix=(int)px, iy=(int)py;
                if (ix<1||ix>=size-2||iy<1||iy>=size-2) break;
                int idx=iy*size+ix;
                float fx=px-ix, fy=py-iy;
                float hNW=flat[idx],hNE=flat[idx+1],hSW=flat[idx+size],hSE=flat[idx+size+1];
                float h0=hNW*(1-fx)*(1-fy)+hNE*fx*(1-fy)+hSW*(1-fx)*fy+hSE*fx*fy;
                if (h0<=0.01f) break;
                float gx=(hNE-hNW)*(1-fy)+(hSE-hSW)*fy, gy=(hSW-hNW)*(1-fx)+(hSE-hNE)*fx;
                float glen=(float)Math.sqrt(gx*gx+gy*gy);
                if (glen<1e-12f) break;
                dirX=dirX*inertia-gx*(1-inertia); dirY=dirY*inertia-gy*(1-inertia);
                float dlen=(float)Math.sqrt(dirX*dirX+dirY*dirY);
                if (dlen<1e-12f) break; dirX/=dlen; dirY/=dlen;
                float npx=px+dirX, npy=py+dirY;
                if (npx<1||npx>=size-2||npy<1||npy>=size-2) break;
                int nix=(int)npx, niy=(int)npy;
                float fnx=npx-nix, fny=npy-niy;
                int nidx=niy*size+nix;
                float h1=flat[nidx]*(1-fnx)*(1-fny)+flat[nidx+1]*fnx*(1-fny)+flat[nidx+size]*(1-fnx)*fny+flat[nidx+size+1]*fnx*fny;
                float dh=(h1-h0)*Math.min(1,h0/fallOff);
                float cap=Math.max(-dh*spd*wat*capFactor*strength,minCap);
                if (sed>cap||dh>0) {
                    float dep=dh>0?Math.min(dh,sed):(sed-cap)*depositSpeed;
                    sed-=dep;
                    flat[idx]+=dep*(1-fx)*(1-fy); flat[idx+1]+=dep*fx*(1-fy);
                    flat[idx+size]+=dep*(1-fx)*fy; flat[idx+size+1]+=dep*fx*fy;
                } else {
                    float eroAmt=Math.min((cap-sed)*erodeSpeed,-dh);
                    for (int b=0; b<bn; b++) {
                        int bi=idx+bOff[b];
                        if (bi>=0&&bi<size*size) { float delta=Math.min(flat[bi],eroAmt*bWgt[b]); flat[bi]-=delta; sed+=delta; }
                    }
                }
                px=npx; py=npy;
                spd=(float)Math.sqrt(spd*spd+dh*gravity);
                if (spd<=0) break; wat*=(1-evaporate);
            }
        }
        for (int z=0;z<size;z++)for(int x=0;x<size;x++){float v=Math.max(0,Math.min(1,flat[z*size+x]));map[z][x]=Float.isNaN(v)||Float.isInfinite(v)?0.5f:v;}
    }

    // ===== STEP 3: DOMAIN-WARPED NOISE =====
    static void addDomainWarpedNoise(Noise noise, float[][] map, int size, int step, float strength) {
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++) {
                float wx = x * step, wz = z * step;
                float w1 = noise.terrainBase((int) (wx * 0.05f), (int) (wz * 0.05f)) * 0.3f;
                float w2 = noise.terrainBase((int) (wx * 0.05f + 500), (int) (wz * 0.05f + 500)) * 0.3f;
                float dwx = wx + w1 * 20;
                float dwz = wz + w2 * 20;
                float raw = noise.terrainBase((int) dwx, (int) dwz);
                float n = Math.min(1f, raw / 3f) * 2 - 1;
                map[z][x] += n * strength;
            }
    }

    // ===== SLOPE WASH =====
    static void applySlopeWash(Noise noise, float[][] map,
                                 int size, int scale, float strength) {
        float slopeThreshold = 0.04f;
        float[][] tmp = new float[size][size];
        for (int z = 2; z < size - 2; z++)
            for (int x = 2; x < size - 2; x++) {
                float gx = (map[z][x + 1] - map[z][x - 1]) * 0.5f;
                float gz = (map[z + 1][x] - map[z - 1][x]) * 0.5f;
                float slope = (float) Math.sqrt(gx * gx + gz * gz);
                if (slope < slopeThreshold) continue;
                gx /= slope; gz /= slope;

                float wx = x * scale + gx * 6;
                float wz = z * scale + gz * 6;
                float w1 = noise.terrainBase((int) (wx * 0.02f), (int) (wz * 0.02f)) * 0.2f;
                float w2 = noise.terrainBase((int) (wx * 0.02f + 999), (int) (wz * 0.02f + 999)) * 0.2f;
                float dwx = wx + w1 * 10 + gx * 6;
                float dwz = wz + w2 * 10 + gz * 6;
                float raw = noise.terrainBase((int) dwx, (int) dwz);
                float n = Math.min(1f, raw / 3f) * 2 - 1;
                float steepBoost = Math.min(1, (slope - slopeThreshold) / 0.12f);
                tmp[z][x] = n * strength * steepBoost;
            }
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                map[z][x] += tmp[z][x];
    }

    // ===== CATMULL-ROM SAMPLING & GRADIENT =====
    static float catromSample(float[][] grid, int size, int z, int x, float fz, float fx) {
        if (z < 1 || z >= size - 2 || x < 1 || x >= size - 2) return 0;
        float[] col = new float[4];
        for (int i = -1; i <= 2; i++) {
            int pz = clamp(z + i, 0, size - 1);
            col[i + 1] = cubic(line(grid[pz][x - 1], grid[pz][x], grid[pz][x + 1], grid[pz][x + 2]), fx);
        }
        return cubic(line(col[0], col[1], col[2], col[3]), fz);
    }

    static float catromDerivX(float[][] grid, int size, int z, int x, float fz, float fx) {
        if (z < 1 || z >= size - 2 || x < 1 || x >= size - 2) return 0;
        float[] col = new float[4];
        for (int i = -1; i <= 2; i++) {
            int pz = clamp(z + i, 0, size - 1);
            // Derivative at fx using cubic coefficient
            float v0 = grid[pz][x - 1], v1 = grid[pz][x], v2 = grid[pz][x + 1], v3 = grid[pz][x + 2];
            float t = fx;
            // dcubic/dt = a0*3*t^2 + a1*2*t + a2
            float a0 = v3 - v2 - v0 + v1;
            float a1 = v0 - v1 - a0;
            float a2 = v2 - v0;
            col[i + 1] = a0 * 3 * t * t + a1 * 2 * t + a2;
        }
        float t = fz;
        // Interpolate the x-derivatives in z direction (regular cubic)
        float v0 = col[0], v1 = col[1], v2 = col[2], v3 = col[3];
        float a0 = v3 - v2 - v0 + v1;
        float a1 = v0 - v1 - a0;
        float a2 = v2 - v0;
        return a0 * t * t * t + a1 * t * t + a2 * t + v1;
    }

    static float catromDerivZ(float[][] grid, int size, int z, int x, float fz, float fx) {
        if (z < 1 || z >= size - 2 || x < 1 || x >= size - 2) return 0;
        float[] row = new float[4];
        for (int j = -1; j <= 2; j++) {
            int px = clamp(x + j, 0, size - 1);
            // Sample at z for this column using cubic
            float v0 = grid[z - 1][px], v1 = grid[z][px], v2 = grid[z + 1][px], v3 = grid[z + 2][px];
            float t = fx;
            row[j + 1] = cubic(line(v0, v1, v2, v3), t);
        }
        // Derivative in z
        float v0 = row[0], v1 = row[1], v2 = row[2], v3 = row[3];
        float t = fz;
        float a0 = v3 - v2 - v0 + v1;
        float a1 = v0 - v1 - a0;
        float a2 = v2 - v0;
        return a0 * 3 * t * t + a1 * 2 * t + a2;
    }

    static float computeHeight(Noise noise, int wx, int wz) {
        float continent = noise.continentRaw(wx, wz);
        float terrain = noise.terrainBaseMod(wx, wz);
        float shapeNorm = terrain;  // FBM已输出[0,1]，直接使用
        float seaNorm = 0.33f;
        float continentBase = Math.max(0f, (continent - 0.08f) / (1f - 0.08f));
        float lift = continent < 0.08f ? 0 : Math.min(1f, (continent - 0.08f) / (1f - 0.08f)) * 0.05f;
        float base = seaNorm + continentBase * (1f - seaNorm) * 0.05f;
        float land = base + shapeNorm * (1f - seaNorm) * 0.8f + lift;
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

    static float[][] bicubicUpsampleGrid(float[][] src, int srcRes, int dstRes) {
        float[] flat = new float[srcRes * srcRes];
        for (int z = 0; z < srcRes; z++)
            for (int x = 0; x < srcRes; x++)
                flat[z * srcRes + x] = src[z][x];
        float[] dst = bicubicUpsample(flat, srcRes, dstRes);
        float[][] grid = new float[dstRes][dstRes];
        for (int z = 0; z < dstRes; z++)
            for (int x = 0; x < dstRes; x++)
                grid[z][x] = dst[z * dstRes + x];
        return grid;
    }

    static float[] bicubicUpsample(float[] src, int srcRes, int dstRes) {
        float[] dst = new float[dstRes * dstRes];
        float scale = (float) srcRes / dstRes;
        for (int dz = 0; dz < dstRes; dz++) {
            for (int dx = 0; dx < dstRes; dx++) {
                float sx = dx * scale, sy = dz * scale;
                int ix = (int) sx, iy = (int) sy;
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

    static float[] line(float a, float b, float c, float d) { return new float[]{a, b, c, d}; }
    static float cubic(float[] v, float t) {
        return v[1] + 0.5f * t * (v[2] - v[0] + t * (2 * v[0] - 5 * v[1] + 4 * v[2] - v[3] + t * (3 * (v[1] - v[2]) + v[3] - v[0])));
    }
    static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    static float clamp(float v, float min, float max) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return min;
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
    static float[][] cloneGrid(float[][] src) {
        int n = src.length;
        float[][] d = new float[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(src[i], 0, d[i], 0, n);
        return d;
    }
    static long hash(int a, int b) {
        long h = a * 0x9e3779b9L + b * 0x9e3779b9L * 31;
        h = (h ^ (h >>> 16)) * 0x85ebca6bL;
        h = h ^ (h >>> 13);
        h = h * 0xc2b2ae35L;
        h = h ^ (h >>> 16);
        return h;
    }
    static int darken(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r = r * 3 / 4; g = g * 3 / 4; b = b * 3 / 4;
        return (r << 16) | (g << 8) | b;
    }
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
    static int toColor(float h, float min, float range) {
        if (Float.isNaN(h) || Float.isInfinite(h)) return 0x444466;
        float t = range > 0 ? (h - min) / range : 0.5f;
        t = clamp(t, 0f, 1f);
        // 更平滑的色阶：深海→浅海→海岸→低地→丘陵→山地→高山→雪线→雪峰
        // 每个停止点之间的RGB变化更均匀，避免跳变
        float[][] stops = {
            {0.00f, 0, 10, 60},      // 深海蓝
            {0.06f, 0, 40, 110},     // 海蓝
            {0.12f, 0, 80, 140},     // 浅海蓝
            {0.18f, 30, 120, 120},   // 蓝绿过渡（海岸）
            {0.25f, 60, 140, 80},    // 绿
            {0.35f, 100, 160, 50},   // 黄绿
            {0.50f, 150, 150, 40},   // 土黄
            {0.65f, 170, 130, 60},   // 棕黄
            {0.78f, 190, 160, 130},  // 浅棕
            {0.90f, 210, 200, 190},  // 灰白
            {1.00f, 235, 240, 250},  // 雪白
        };
        // 使用更平滑的插值（hermite插值）
        for (int i = 0; i < stops.length - 1; i++) {
            if (t >= stops[i][0] && t <= stops[i + 1][0]) {
                float l = (t - stops[i][0]) / (stops[i + 1][0] - stops[i][0]);
                // smoothstep插值，让过渡更自然
                l = l * l * (3 - 2 * l);
                int r = (int)(stops[i][1] + (stops[i + 1][1] - stops[i][1]) * l);
                int g = (int)(stops[i][2] + (stops[i + 1][2] - stops[i][2]) * l);
                int b = (int)(stops[i][3] + (stops[i + 1][3] - stops[i][3]) * l);
                return (r << 16) | (g << 8) | b;
            }
        }
        return 0;
    }
    static int deltaColor(float delta, float maxDelta) {
        if (Float.isNaN(delta) || Float.isInfinite(delta)) return 0x222222;
        float t = maxDelta > 0.0001f ? Math.abs(delta) / maxDelta : 0;
        t = clamp(t, 0f, 1f);
        // 最小亮度60，保证即使小delta也能看见颜色，避免深灰/黑色边界
        int v = 60 + (int) (t * 195);
        v = Math.min(255, v);
        // 使用连续阈值，避免硬边界
        if (delta > 0.001f) return (v << 16);  // 红色-沉积
        if (delta < -0.001f) return (v << 8);   // 绿色-侵蚀
        // 接近0的delta：用暗灰色但不要太黑
        int gray = 40 + (int)(t * 60);
        return (gray << 16) | (gray << 8) | gray;
    }

    // Mirror padding: extend grid with mirrored edges to avoid boundary artifacts
    static float[][] padGridMirror(float[][] src, int size, int pad) {
        int newSize = size + pad * 2;
        float[][] dst = new float[newSize][newSize];
        for (int z = 0; z < newSize; z++) {
            for (int x = 0; x < newSize; x++) {
                int sz, sx;
                if (z < pad) sz = pad - z - 1; // mirror top
                else if (z >= size + pad) sz = size - 1 - (z - (size + pad)); // mirror bottom
                else sz = z - pad;

                if (x < pad) sx = pad - x - 1; // mirror left
                else if (x >= size + pad) sx = size - 1 - (x - (size + pad)); // mirror right
                else sx = x - pad;

                sz = clamp(sz, 0, size - 1);
                sx = clamp(sx, 0, size - 1);
                dst[z][x] = src[sz][sx];
            }
        }
        return dst;
    }

    // Light Gaussian blur to smooth deposition noise
    // sigma=1.0 gives a 3x3 kernel, sigma=1.5 gives 5x5
    static void gaussianBlur(float[][] map, int size, float sigma) {
        int radius = (int)Math.ceil(sigma * 2);
        if (radius < 1) radius = 1;
        float[] kernel = new float[radius * 2 + 1];
        float sum = 0;
        for (int i = -radius; i <= radius; i++) {
            float v = (float)Math.exp(-i * i / (2 * sigma * sigma));
            kernel[i + radius] = v;
            sum += v;
        }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= sum;

        float[][] tmp = new float[size][size];
        // Horizontal pass
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++) {
                float s = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sx = clamp(x + k, 0, size - 1);
                    s += map[z][sx] * kernel[k + radius];
                }
                tmp[z][x] = s;
            }
        // Vertical pass
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++) {
                float s = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sy = clamp(z + k, 0, size - 1);
                    s += tmp[sy][x] * kernel[k + radius];
                }
                map[z][x] = s;
            }
    }
}
