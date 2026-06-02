package com.geogenesis.worldgen.erosion;

import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.NoiseEngine;

import java.util.ArrayList;
import java.util.Random;

public class ErosionEngine {

    private final NoiseEngine noise;
    private final int worldSeed;

    public ErosionEngine(NoiseEngine noiseEngine, int seed) {
        this.noise = noiseEngine;
        this.worldSeed = seed;
    }

    /**
     * TerraForged 风格侵蚀：单层 + 少粒子 + 高惯性 + 高度衰减
     *
     * 与旧版多层级侵蚀的关键区别：
     *   - 单层侵蚀，radius=4，每chunk 350 粒子
     *   - 高惯性(0.05)：粒子沿坡面长距离流动，不会局部打转
     *   - 高度衰减(0.4)：低地不被侵蚀，防止地形被压平
     *   - 低蒸发(0.01)：粒子保持水量，沉积分散
     *   - 高沉积率(0.3)：侵蚀和沉积平衡，不压平地形
     *
     * 性能提升：7700粒子 → ~1700粒子（每chunk 350×7chunks）
     */
    public void applyErosionNormalized(float[][] heights, int size, int ox, int oz,
                                        float seaNorm, float strength,
                                        boolean[][] locked) {
        if (size < 16) return;

        float configDropsMul = (float)GeoGenesisConfig.COMMON.erosionDropsMul.get().doubleValue();

        // TerraForged 风格参数：单层 + 共享笔刷
        // === 第一层：大尺度侵蚀（大沟壑、峡谷） ===
        // 高惯性 → 粒子沿坡面长距离流动 → 形成大沟壑
        // 大笔刷 → 侵蚀范围宽 → 天山式大型切割地貌
        int radiusLarge = 6;
        int dropsPerChunkLarge = (int)(80 * configDropsMul);
        int lifetimeLarge = 50;
        float inertiaLarge = 0.35f;
        float capFactorLarge = 6f;
        float minCapLarge = 0.008f;
        float evaporateLarge = 0.12f;
        float gravityLarge = 4f;
        float erodeSpeedLarge = 0.25f;
        float depositSpeedLarge = 0.6f;
        float fallOffLarge = 0.3f;

        // === 第二层：细节侵蚀（小沟壑、纹理） ===
        int radius = 4;
        int dropsPerChunk = (int)(280 * configDropsMul);
        int lifetime = 30;
        float inertia = 0.05f;
        float capFactor = 4f;
        float minCap = 0.01f;
        float evaporate = 0.01f;
        float gravity = 3f;
        float erodeSpeed = 0.3f;
        float depositSpeed = 0.3f;
        float fallOff = 0.4f;

        int pad = radius + 2;
        int bufSize = size + pad * 2;
        float[] flat = new float[bufSize * bufSize];

        // 填充 + clamp padding
        for (int z = 0; z < bufSize; z++)
            for (int x = 0; x < bufSize; x++) {
                int sz = Math.max(0, Math.min(size - 1, z - pad));
                int sx = Math.max(0, Math.min(size - 1, x - pad));
                flat[z * bufSize + x] = heights[sz][sx];
            }

        // 保存锁定区域高度
        float[][] savedLocked = null;
        if (locked != null) {
            savedLocked = new float[size][size];
            for (int z = 0; z < size; z++)
                for (int x = 0; x < size; x++)
                    if (locked[z][x]) savedLocked[z][x] = heights[z][x];
        }

        // 共享笔刷偏移 — 第二层（细节）
        int r2 = radius * radius;
        int maxB = (2*radius+1)*(2*radius+1);
        int[] bOff = new int[maxB]; float[] bWgt = new float[maxB];
        int bn = 0;
        for (int dy = -radius; dy <= radius; dy++)
            for (int dx = -radius; dx <= radius; dx++) {
                float d2 = dx*dx + dy*dy;
                if (d2 < r2) { bOff[bn] = dy*bufSize+dx; bWgt[bn] = 1f-(float)Math.sqrt(d2)/radius; bn++; }
            }
        { float s=0; for(int i=0;i<bn;i++)s+=bWgt[i]; for(int i=0;i<bn;i++)bWgt[i]/=s; }

        // 共享笔刷偏移 — 第一层（大尺度）
        int r2L = radiusLarge * radiusLarge;
        int maxBL = (2*radiusLarge+1)*(2*radiusLarge+1);
        int[] bOffL = new int[maxBL]; float[] bWgtL = new float[maxBL];
        int bnL = 0;
        for (int dy = -radiusLarge; dy <= radiusLarge; dy++)
            for (int dx = -radiusLarge; dx <= radiusLarge; dx++) {
                float d2 = dx*dx + dy*dy;
                if (d2 < r2L) { bOffL[bnL] = dy*bufSize+dx; bWgtL[bnL] = 1f-(float)Math.sqrt(d2)/radiusLarge; bnL++; }
            }
        { float s=0; for(int i=0;i<bnL;i++)s+=bWgtL[i]; for(int i=0;i<bnL;i++)bWgtL[i]/=s; }

        // 按 chunk 分组生成粒子（TerraForged 方式）
        int chunksX = size / 16;
        int chunksZ = size / 16;
        // 使用 worldSeed 固定随机序列，不依赖 tile 原点。
        // 噪声高度是确定性的 → 重叠区域侵蚀结果一致 → 消除 tile 间接缝
        Random rng = new Random(worldSeed);

        // === 第一层：大尺度侵蚀（先执行，形成大沟壑骨架） ===
        for (int cz = 0; cz < chunksZ; cz++) {
            int relZ = cz << 4;
            for (int cx = 0; cx < chunksX; cx++) {
                int relX = cx << 4;
                for (int d = 0; d < dropsPerChunkLarge; d++) {
                    int px = pad + relX + rng.nextInt(16);
                    int py = pad + relZ + rng.nextInt(16);
                    px = Math.max(pad + 1, Math.min(pad + size - 2, px));
                    py = Math.max(pad + 1, Math.min(pad + size - 2, py));
                    int idx = py * bufSize + px;
                    if (flat[idx] <= 0.02f) continue;
                    if (locked != null && locked[py - pad][px - pad]) continue;

                    simulateDropTF(flat, bufSize, px + 0.5f, py + 0.5f,
                        inertiaLarge, gravityLarge, capFactorLarge, minCapLarge, evaporateLarge,
                        fallOffLarge, erodeSpeedLarge, depositSpeedLarge, strength, lifetimeLarge,
                        bOffL, bWgtL, bnL, locked, pad, size);
                }
            }
        }

        // === 第二层：细节侵蚀（在大沟壑基础上添加小纹理） ===

        for (int cz = 0; cz < chunksZ; cz++) {
            int relZ = cz << 4;
            for (int cx = 0; cx < chunksX; cx++) {
                int relX = cx << 4;
                for (int d = 0; d < dropsPerChunk; d++) {
                    int px = pad + relX + rng.nextInt(16);
                    int py = pad + relZ + rng.nextInt(16);
                    px = Math.max(pad + 1, Math.min(pad + size - 2, px));
                    py = Math.max(pad + 1, Math.min(pad + size - 2, py));
                    int idx = py * bufSize + px;
                    if (flat[idx] <= 0.02f) continue;
                    if (locked != null && locked[py - pad][px - pad]) continue;

                    simulateDropTF(flat, bufSize, px + 0.5f, py + 0.5f,
                        inertia, gravity, capFactor, minCap, evaporate,
                        fallOff, erodeSpeed, depositSpeed, strength, lifetime,
                        bOff, bWgt, bn, locked, pad, size);
                }
            }
        }

        // 写回
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                heights[z][x] = clamp(flat[(z+pad)*bufSize+(x+pad)], 0f, 1f);

        if (savedLocked != null) {
            for (int z = 0; z < size; z++)
                for (int x = 0; x < size; x++)
                    if (locked[z][x]) heights[z][x] = savedLocked[z][x];
        }
    }

    // 兼容旧调用（无锁定掩码）
    public void applyErosionNormalized(float[][] heights, int size, int ox, int oz,
                                        float seaNorm, float strength) {
        applyErosionNormalized(heights, size, ox, oz, seaNorm, strength, null);
    }

    /**
     * TerraForged 风格粒子模拟
     * 关键差异：高惯性(0.05)、高度衰减(0.4)、低蒸发(0.01)、共享笔刷
     */
    private void simulateDropTF(float[] flat, int bufSize, float posX, float posY,
                                 float inertia, float gravity, float capFactor, float minCap,
                                 float evaporate, float fallOff, float erodeSpeed, float depositSpeed,
                                 float strength, int lifetime,
                                 int[] bOff, float[] bWgt, int bn,
                                 boolean[][] locked, int pad, int baseSize) {
        float dirX = 0, dirY = 0, sediment = 0;
        float speed = 1f, water = 1f;

        for (int step = 0; step < lifetime; step++) {
            int nodeX = (int) posX, nodeY = (int) posY;
            if (nodeX < 1 || nodeX >= bufSize - 2 || nodeY < 1 || nodeY >= bufSize - 2) return;
            int dropletIndex = nodeY * bufSize + nodeX;
            float cellOffX = posX - nodeX, cellOffY = posY - nodeY;

            // 双线性插值计算高度和梯度
            float hNW = flat[dropletIndex], hNE = flat[dropletIndex + 1];
            float hSW = flat[dropletIndex + bufSize], hSE = flat[dropletIndex + bufSize + 1];
            float height = hNW*(1-cellOffX)*(1-cellOffY) + hNE*cellOffX*(1-cellOffY)
                         + hSW*(1-cellOffX)*cellOffY + hSE*cellOffX*cellOffY;
            float gradX = (hNE - hNW)*(1-cellOffY) + (hSE - hSW)*cellOffY;
            float gradY = (hSW - hNW)*(1-cellOffX) + (hSE - hNE)*cellOffX;

            // 高惯性方向更新
            dirX = dirX * inertia - gradX * (1 - inertia);
            dirY = dirY * inertia - gradY * (1 - inertia);
            float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
            if (len < 1e-12f) return;
            dirX /= len; dirY /= len;

            posX += dirX; posY += dirY;
            if (posX < 1 || posX >= bufSize - 2 || posY < 1 || posY >= bufSize - 2) return;

            // 计算新高度
            int nNodeX = (int) posX, nNodeY = (int) posY;
            float nOffX = posX - nNodeX, nOffY = posY - nNodeY;
            int newIdx = nNodeY * bufSize + nNodeX;
            float newHeight = flat[newIdx]*(1-nOffX)*(1-nOffY) + flat[newIdx+1]*nOffX*(1-nOffY)
                            + flat[newIdx+bufSize]*(1-nOffX)*nOffY + flat[newIdx+bufSize+1]*nOffX*nOffY;

            // 高度衰减：低地不被侵蚀
            float falloff = (height >= fallOff) ? 1f : height / fallOff;
            float deltaHeight = (newHeight - height) * falloff;

            // 搬运能力
            float sedimentCapacity = Math.max(-deltaHeight * speed * water * capFactor * strength, minCap);

            if (sediment > sedimentCapacity || deltaHeight > 0) {
                // 沉积（双线性插值到4个节点）
                float amountToDeposit = (deltaHeight > 0)
                    ? Math.min(deltaHeight, sediment)
                    : (sediment - sedimentCapacity) * depositSpeed;
                sediment -= amountToDeposit;
                flat[dropletIndex] += amountToDeposit * (1-cellOffX) * (1-cellOffY);
                flat[dropletIndex + 1] += amountToDeposit * cellOffX * (1-cellOffY);
                flat[dropletIndex + bufSize] += amountToDeposit * (1-cellOffX) * cellOffY;
                flat[dropletIndex + bufSize + 1] += amountToDeposit * cellOffX * cellOffY;
            } else {
                // 侵蚀（共享笔刷）
                float amountToErode = Math.min((sedimentCapacity - sediment) * erodeSpeed, -deltaHeight);
                for (int b = 0; b < bn; b++) {
                    int bi = dropletIndex + bOff[b];
                    if (bi >= 0 && bi < bufSize * bufSize) {
                        // 检查锁定
                        if (locked != null) {
                            int bz = bi / bufSize, bx = bi % bufSize;
                            int bzLocal = bz - pad, bxLocal = bx - pad;
                            if (bzLocal >= 0 && bzLocal < baseSize && bxLocal >= 0 && bxLocal < baseSize
                                && locked[bzLocal][bxLocal]) continue;
                        }
                        float weighedErodeAmount = amountToErode * bWgt[b];
                        float deltaSediment = Math.min(flat[bi], weighedErodeAmount);
                        flat[bi] -= deltaSediment;
                        sediment += deltaSediment;
                    }
                }
            }

            // 更新速度和水量
            float speed2 = speed * speed + deltaHeight * gravity;
            if (speed2 <= 0) return;
            speed = (float) Math.sqrt(speed2);
            water *= (1 - evaporate);
        }
    }

    /**
     * Tile模式直接侵蚀：TerraForged 风格，单层 + 少粒子 + 高惯性
     * 直接在buf上操作，无padding/mirror。
     */
    public void applyErosionDirect(float[][] buf, int size, int ox, int oz, float strength) {
        float configDropsMul = (float)GeoGenesisConfig.COMMON.erosionDropsMul.get().doubleValue();

        int radius = 4;
        int dropsPerChunk = (int)(350 * configDropsMul);
        int lifetime = 30;
        float inertia = 0.05f;
        float capFactor = 4f;
        float minCap = 0.01f;
        float evaporate = 0.01f;
        float gravity = 3f;
        float erodeSpeed = 0.3f;
        float depositSpeed = 0.3f;
        float fallOff = 0.4f;
        int margin = radius + 2;

        // 一维化
        float[] flat = new float[size * size];
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                flat[z * size + x] = buf[z][x];

        // 共享笔刷
        int r2 = radius * radius;
        int maxB = (2*radius+1)*(2*radius+1);
        int[] bOff = new int[maxB]; float[] bWgt = new float[maxB];
        int bn = 0;
        for (int dy = -radius; dy <= radius; dy++)
            for (int dx = -radius; dx <= radius; dx++) {
                float d2 = dx*dx + dy*dy;
                if (d2 < r2) { bOff[bn] = dy*size+dx; bWgt[bn] = 1f-(float)Math.sqrt(d2)/radius; bn++; }
            }
        { float s=0; for(int i=0;i<bn;i++)s+=bWgt[i]; for(int i=0;i<bn;i++)bWgt[i]/=s; }

        // 按 chunk 分组生成粒子
        int chunksX = size / 16;
        int chunksZ = size / 16;
        Random rng = new Random(worldSeed);

        for (int cz = 0; cz < chunksZ; cz++) {
            int relZ = cz << 4;
            for (int cx = 0; cx < chunksX; cx++) {
                int relX = cx << 4;
                for (int d = 0; d < dropsPerChunk; d++) {
                    int px = relX + rng.nextInt(16);
                    int py = relZ + rng.nextInt(16);
                    px = Math.max(margin, Math.min(size - margin - 1, px));
                    py = Math.max(margin, Math.min(size - margin - 1, py));
                    int idx = py * size + px;
                    if (flat[idx] <= 0.02f) continue;

                    simulateDropTFDirect(flat, size, px + 0.5f, py + 0.5f,
                        inertia, gravity, capFactor, minCap, evaporate,
                        fallOff, erodeSpeed, depositSpeed, strength, lifetime,
                        bOff, bWgt, bn);
                }
            }
        }

        // 写回
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                buf[z][x] = clamp(flat[z * size + x], 0f, 1f);
    }

    /** TerraForged 风格粒子模拟（无锁定掩码版，用于 Direct 模式）*/
    private void simulateDropTFDirect(float[] flat, int size, float posX, float posY,
                                       float inertia, float gravity, float capFactor, float minCap,
                                       float evaporate, float fallOff, float erodeSpeed, float depositSpeed,
                                       float strength, int lifetime,
                                       int[] bOff, float[] bWgt, int bn) {
        float dirX = 0, dirY = 0, sediment = 0;
        float speed = 1f, water = 1f;

        for (int step = 0; step < lifetime; step++) {
            int nodeX = (int) posX, nodeY = (int) posY;
            if (nodeX < 1 || nodeX >= size - 2 || nodeY < 1 || nodeY >= size - 2) return;
            int dropletIndex = nodeY * size + nodeX;
            float cellOffX = posX - nodeX, cellOffY = posY - nodeY;

            float hNW = flat[dropletIndex], hNE = flat[dropletIndex + 1];
            float hSW = flat[dropletIndex + size], hSE = flat[dropletIndex + size + 1];
            float height = hNW*(1-cellOffX)*(1-cellOffY) + hNE*cellOffX*(1-cellOffY)
                         + hSW*(1-cellOffX)*cellOffY + hSE*cellOffX*cellOffY;
            float gradX = (hNE - hNW)*(1-cellOffY) + (hSE - hSW)*cellOffY;
            float gradY = (hSW - hNW)*(1-cellOffX) + (hSE - hNE)*cellOffX;

            dirX = dirX * inertia - gradX * (1 - inertia);
            dirY = dirY * inertia - gradY * (1 - inertia);
            float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
            if (len < 1e-12f) return;
            dirX /= len; dirY /= len;

            posX += dirX; posY += dirY;
            if (posX < 1 || posX >= size - 2 || posY < 1 || posY >= size - 2) return;

            int nNodeX = (int) posX, nNodeY = (int) posY;
            float nOffX = posX - nNodeX, nOffY = posY - nNodeY;
            int newIdx = nNodeY * size + nNodeX;
            float newHeight = flat[newIdx]*(1-nOffX)*(1-nOffY) + flat[newIdx+1]*nOffX*(1-nOffY)
                            + flat[newIdx+size]*(1-nOffX)*nOffY + flat[newIdx+size+1]*nOffX*nOffY;

            float falloff = (height >= fallOff) ? 1f : height / fallOff;
            float deltaHeight = (newHeight - height) * falloff;
            float sedimentCapacity = Math.max(-deltaHeight * speed * water * capFactor * strength, minCap);

            if (sediment > sedimentCapacity || deltaHeight > 0) {
                float amountToDeposit = (deltaHeight > 0)
                    ? Math.min(deltaHeight, sediment)
                    : (sediment - sedimentCapacity) * depositSpeed;
                sediment -= amountToDeposit;
                flat[dropletIndex] += amountToDeposit * (1-cellOffX) * (1-cellOffY);
                flat[dropletIndex + 1] += amountToDeposit * cellOffX * (1-cellOffY);
                flat[dropletIndex + size] += amountToDeposit * (1-cellOffX) * cellOffY;
                flat[dropletIndex + size + 1] += amountToDeposit * cellOffX * cellOffY;
            } else {
                float amountToErode = Math.min((sedimentCapacity - sediment) * erodeSpeed, -deltaHeight);
                for (int b = 0; b < bn; b++) {
                    int bi = dropletIndex + bOff[b];
                    if (bi >= 0 && bi < size * size) {
                        float weighedErodeAmount = amountToErode * bWgt[b];
                        float deltaSediment = Math.min(flat[bi], weighedErodeAmount);
                        flat[bi] -= deltaSediment;
                        sediment += deltaSediment;
                    }
                }
            }

            float speed2 = speed * speed + deltaHeight * gravity;
            if (speed2 <= 0) return;
            speed = (float) Math.sqrt(speed2);
            water *= (1 - evaporate);
        }
    }

    private void applySingleScaleWithPad(float[][] map, int size, int drops, float strength,
                                          int radius, float fallOff, float inertia,
                                          float gravity, float sea,
                                          float erodeSpeed, float depositSpeed,
                                          int ox, int oz) {
        int pad = Math.max(radius * 2, 8);
        float[][] padded = padGridMirror(map, size, pad);
        applySingleScale(padded, size + pad * 2, drops, strength, radius, fallOff, inertia, gravity, sea, erodeSpeed, depositSpeed, ox, oz, pad, size);
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                map[z][x] = padded[z + pad][x + pad];
        gaussianBlur(map, size, 1.0f);
    }

    private void applySingleScale(float[][] map, int size, int drops, float strength,
                                   int radius, float fallOff, float inertia,
                                   float gravity, float sea,
                                   float erodeSpeed, float depositSpeed,
                                   int ox, int oz, int pad, int baseSize) {
        if (drops <= 0 || size < 4) return;

        float capFactor = 10f;
        float minCap = 0.005f;
        float evaporate = 0.35f;

        // Flatten
        float[] flat = new float[size * size];
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                flat[z * size + x] = map[z][x];

        // Precompute brush
        int r = Math.min(radius, size / 2 - 1);
        if (r < 1) r = 1;
        int[][] brushIdx = new int[size * size][];
        float[][] brushW = new float[size * size][];
        for (int cy = 0; cy < size; cy++)
            for (int cx = 0; cx < size; cx++) {
                ArrayList<Integer> xs = new ArrayList<>();
                ArrayList<Integer> ys = new ArrayList<>();
                ArrayList<Float> ws = new ArrayList<>();
                float wSum = 0;
                for (int dy = -r; dy <= r; dy++)
                    for (int dx = -r; dx <= r; dx++) {
                        float d2 = dx * dx + dy * dy;
                        if (d2 < r * r) {
                            int nx = cx + dx, ny = cy + dy;
                            if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                                float w = 1f - (float) Math.sqrt(d2) / r;
                                wSum += w;
                                xs.add(dx); ys.add(dy); ws.add(w);
                            }
                        }
                    }
                int n = xs.size();
                int idx = cy * size + cx;
                brushIdx[idx] = new int[n];
                brushW[idx] = new float[n];
                for (int i = 0; i < n; i++) {
                    brushIdx[idx][i] = (cy + ys.get(i)) * size + (cx + xs.get(i));
                    brushW[idx][i] = ws.get(i) / wSum;
                }
            }

        // ===== 使用基于世界坐标的粒子生成（密度法）=====
        // 遍历侵蚀区域内部（非 padding 区）的每个像素
        // 每个像素基于其世界坐标的确定性哈希决定是否生成粒子
        // 同一世界坐标在所有 tile 中生成相同粒子 → 完全消除边界不连续
        int interiorStart = pad;
        int interiorEnd = pad + baseSize;
        int margin = 1;

        float pixelCount = (float)((baseSize - margin * 2) * (baseSize - margin * 2));
        float density = drops / pixelCount;
        long densityThreshold = (long)(density * (1L << 20));

        for (int py = interiorStart + margin; py < interiorEnd - margin; py++) {
            for (int px = interiorStart + margin; px < interiorEnd - margin; px++) {
                // 计算该像素对应的世界坐标
                int worldX = ox + (px - pad);
                int worldZ = oz + (py - pad);
                // 世界坐标决定的确定性哈希
                long h = hashCoarse(worldX * 31 + 1, worldZ * 73 + 1);
                // 密度检查：前 20 位哈希值决定是否在该位置生成粒子
                if ((h & ((1L << 20) - 1)) >= densityThreshold) continue;
                if (flat[py * size + px] <= sea - 0.02f) continue;

                drop(flat, size, px, py, sea, 60, inertia, gravity, capFactor, minCap, evaporate, fallOff, erodeSpeed, depositSpeed, brushIdx, brushW, r);
            }
        }

        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                map[z][x] = clamp(flat[z * size + x], 0f, 1f);
    }

    /** Optimized erosion with shared brush offsets + world-coordinate seeding (seamless) */
    private void terraforgedErosionSeamless(float[][] map, int bufSize, int drops, float strength,
                                             int radius, float fallOff, float inertia, float gravity,
                                             float erodeSpeed, float depositSpeed,
                                             int ox, int oz, int pad) {
        float capFactor = 10f, minCap = 0.005f, evaporate = 0.35f;
        float[] flat = new float[bufSize * bufSize];
        for (int z = 0; z < bufSize; z++)
            for (int x = 0; x < bufSize; x++)
                flat[z * bufSize + x] = map[z][x];

        // Precompute shared brush offsets
        int r2 = radius * radius;
        int maxB = (2*radius+1)*(2*radius+1);
        int[] bOff = new int[maxB];
        float[] bWgt = new float[maxB];
        int bn = 0;
        for (int dy = -radius; dy <= radius; dy++)
            for (int dx = -radius; dx <= radius; dx++) {
                float d2 = dx*dx + dy*dy;
                if (d2 < r2) { bOff[bn] = dy*bufSize+dx; bWgt[bn] = 1f-(float)Math.sqrt(d2)/radius; bn++; }
            }
        { float s=0; for(int i=0;i<bn;i++)s+=bWgt[i]; for(int i=0;i<bn;i++)bWgt[i]/=s; }

        // Full interior: particles can start anywhere within the padded area
        // Edge pixels also participate in erosion (they become shared vertices)
        int baseSize = bufSize - pad*2;
        int interiorStart = pad;
        int interiorEnd = pad + baseSize;
        int margin = 1;
        float pixelCount = (float)((baseSize - margin*2) * (baseSize - margin*2));
        float density = drops / pixelCount;
        long densityThreshold = (long)(density * (1L << 20));

        for (int py = interiorStart + margin; py < interiorEnd - margin; py++) {
            for (int px_ = interiorStart + margin; px_ < interiorEnd - margin; px_++) {
                int worldX = ox + (px_ - pad);
                int worldZ = oz + (py - pad);
                long h = hashCoarse(worldX * 31 + 1, worldZ * 73 + 1);
                if ((h & ((1L << 20) - 1)) >= densityThreshold) continue;
                int idx = py * bufSize + px_;
                if (flat[idx] <= 0.02f) continue;

                float dirX=0, dirZ=0, sed=0, spd=1f, wat=1f;
                float px = px_ + 0.5f, pz = py + 0.5f;
                for (int step = 0; step < 30; step++) {
                    int ix = (int)px, iz = (int)pz;
                    if (ix < 1 || ix >= bufSize-2 || iz < 1 || iz >= bufSize-2) break;
                    idx = iz*bufSize+ix;
                    float fx = px-ix, fz = pz-iz;
                    float hNW=flat[idx],hNE=flat[idx+1],hSW=flat[idx+bufSize],hSE=flat[idx+bufSize+1];
                    float h0 = hNW*(1-fx)*(1-fz)+hNE*fx*(1-fz)+hSW*(1-fx)*fz+hSE*fx*fz;
                    if (h0 <= 0.02f) break;
                    float gx = (hNE-hNW)*(1-fz)+(hSE-hSW)*fz, gz = (hSW-hNW)*(1-fx)+(hSE-hNE)*fx;
                    float glen = (float)Math.sqrt(gx*gx + gz*gz);
                    if (glen < 1e-12f) break;
                    dirX = dirX*inertia - gx*(1-inertia);
                    dirZ = dirZ*inertia - gz*(1-inertia);
                    float dlen = (float)Math.sqrt(dirX*dirX+dirZ*dirZ);
                    if (dlen < 1e-12f) break; dirX/=dlen; dirZ/=dlen;
                    float npx = px+dirX, npz = pz+dirZ;
                    if (npx < 1 || npx >= bufSize-2 || npz < 1 || npz >= bufSize-2) break;
                    int nix=(int)npx, niz=(int)npz;
                    float fnx=npx-nix, fnz=npz-niz;
                    int nidx = niz*bufSize + nix;
                    float h1 = flat[nidx]*(1-fnx)*(1-fnz)+flat[nidx+1]*fnx*(1-fnz)
                             + flat[nidx+bufSize]*(1-fnx)*fnz+flat[nidx+bufSize+1]*fnx*fnz;
                    float dh = (h1-h0)*Math.min(1, h0/fallOff);
                    float cap = Math.max(-dh*spd*wat*capFactor*strength, minCap);
                    if (sed > cap || dh > 0) {
                        float dep = dh>0 ? Math.min(dh,sed) : (sed-cap)*depositSpeed;
                        sed -= dep;
                        flat[idx] += dep*(1-fx)*(1-fz); flat[idx+1] += dep*fx*(1-fz);
                        flat[idx+bufSize] += dep*(1-fx)*fz; flat[idx+bufSize+1] += dep*fx*fz;
                    } else {
                        float eroAmt = Math.min((cap-sed)*erodeSpeed, -dh);
                        for (int b=0; b<bn; b++) {
                            int bi = idx + bOff[b];
                            if (bi >= 0 && bi < bufSize*bufSize) {
                                float delta = Math.min(flat[bi], eroAmt*bWgt[b]);
                                flat[bi] -= delta; sed += delta;
                            }
                        }
                    }
                    px=npx; pz=npz;
                    spd = (float)Math.sqrt(spd*spd + dh*gravity);
                    if (spd <= 0) break; wat *= (1-evaporate);
                }
            }
        }
        for (int z=0; z<bufSize; z++) for (int x=0; x<bufSize; x++)
            map[z][x] = clamp(flat[z*bufSize+x], 0f, 1f);
    }

    /** 金字塔级侵蚀：共享笔刷偏移+世界坐标缩放粒子。
     *  @param worldScale 像素→世界块缩放因子（14级=8, 28级=4, 56级=2）
     *  @param locked 锁定掩码：true=此像素来自已缓存邻居，不生成粒子也不被笔刷修改
     *  @param margin 粒子生成区与缓冲边界的最小距离（≥brushRadius时无需padding） */
    public void pyramidErosion(float[][] map, int bufSize, int drops, float strength,
                                 int radius, float fallOff, float inertia,
                                 float gravity, float erodeSpeed, float depositSpeed,
                                 int ox, int oz, int pad, int baseSize, int worldScale,
                                 boolean[][] locked, int margin) {
        float capFactor = 10f, minCap = 0.005f, evaporate = 0.35f;
        float[] flat = new float[bufSize * bufSize];
        for (int z = 0; z < bufSize; z++)
            for (int x = 0; x < bufSize; x++)
                flat[z*bufSize+x] = map[z][x];

        // 共享笔刷偏移（所有位置复用，类似测试工具优化）
        int r2 = radius * radius;
        int maxB = (2*radius+1)*(2*radius+1);
        int[] bOff = new int[maxB];
        float[] bWgt = new float[maxB];
        int bn = 0;
        for (int dy = -radius; dy <= radius; dy++)
            for (int dx = -radius; dx <= radius; dx++) {
                float d2 = dx*dx + dy*dy;
                if (d2 < r2) { bOff[bn]=dy*bufSize+dx; bWgt[bn]=1f-(float)Math.sqrt(d2)/radius; bn++; }
            }
        { float s=0; for(int i=0;i<bn;i++)s+=bWgt[i]; for(int i=0;i<bn;i++)bWgt[i]/=s; }

        // 随机粒子生成（替代密度法，避免逐像素遍历开销）
        int interiorStart = pad;
        int interiorEnd = pad + baseSize;
        int effMargin = margin;
        int spawnMin = interiorStart + effMargin;
        int spawnMax = interiorEnd - effMargin;
        int spawnRange = spawnMax - spawnMin;

        Random rng = new Random(worldSeed);

        for (int i = 0; i < drops; i++) {
            int px = spawnMin + rng.nextInt(spawnRange);
            int py = spawnMin + rng.nextInt(spawnRange);
            int idx = py*bufSize + px;
            if (flat[idx] <= 0.02f) continue;
            if (locked != null && locked[py][px]) continue;

            float dirX=0, dirZ=0, sed=0, spd=1f, wat=1f;
            float fpx = px+0.5f, fpz = py+0.5f;
            for (int step=0; step<30; step++) {
                int ix=(int)fpx, iz=(int)fpz;
                if (ix<1||ix>=bufSize-2||iz<1||iz>=bufSize-2) break;
                idx = iz*bufSize+ix;
                float fx=fpx-ix, fz=fpz-iz;
                float hNW=flat[idx], hNE=flat[idx+1], hSW=flat[idx+bufSize], hSE=flat[idx+bufSize+1];
                float h0 = hNW*(1-fx)*(1-fz)+hNE*fx*(1-fz)+hSW*(1-fx)*fz+hSE*fx*fz;
                if (h0 <= 0.02f) break;
                float gx = (hNE-hNW)*(1-fz)+(hSE-hSW)*fz;
                float gz = (hSW-hNW)*(1-fx)+(hSE-hNE)*fx;
                float glen = (float)Math.sqrt(gx*gx+gz*gz);
                if (glen < 1e-12f) break;
                dirX = dirX*inertia - gx*(1-inertia);
                dirZ = dirZ*inertia - gz*(1-inertia);
                float dlen = (float)Math.sqrt(dirX*dirX+dirZ*dirZ);
                if (dlen < 1e-12f) break; dirX/=dlen; dirZ/=dlen;
                float npx = fpx+dirX, npz = fpz+dirZ;
                if (npx<1||npx>=bufSize-2||npz<1||npz>=bufSize-2) break;
                int nix=(int)npx, niz=(int)npz;
                float fnx=npx-nix, fnz=npz-niz;
                int nidx = niz*bufSize+nix;
                float h1 = flat[nidx]*(1-fnx)*(1-fnz)+flat[nidx+1]*fnx*(1-fnz)
                         + flat[nidx+bufSize]*(1-fnx)*fnz+flat[nidx+bufSize+1]*fnx*fnz;
                float dh = (h1-h0)*Math.min(1, h0/fallOff);
                float cap = Math.max(-dh*spd*wat*capFactor*strength, minCap);
                if (sed>cap||dh>0) {
                    float dep = dh>0?Math.min(dh,sed):(sed-cap)*depositSpeed;
                    sed-=dep;
                    if (locked == null || !locked[iz][ix]) flat[idx]+=dep*(1-fx)*(1-fz);
                    if (locked == null || !locked[iz][ix+1]) flat[idx+1]+=dep*fx*(1-fz);
                    if (locked == null || !locked[iz+1][ix]) flat[idx+bufSize]+=dep*(1-fx)*fz;
                    if (locked == null || !locked[iz+1][ix+1]) flat[idx+bufSize+1]+=dep*fx*fz;
                } else {
                    float eroAmt = Math.min((cap-sed)*erodeSpeed, -dh);
                    for (int b=0; b<bn; b++) {
                        int bi = idx+bOff[b];
                        if (bi>=0 && bi<bufSize*bufSize) {
                            int bz = bi / bufSize, bx = bi % bufSize;
                            if (locked != null && locked[bz][bx]) continue;
                            float delta = Math.min(flat[bi], eroAmt*bWgt[b]);
                            flat[bi]-=delta; sed+=delta;
                        }
                    }
                }
                fpx=npx; fpz=npz;
                spd = (float)Math.sqrt(spd*spd + dh*gravity);
                if (spd<=0) break; wat*=(1-evaporate);
            }
        }
        for (int z=0;z<bufSize;z++) for (int x=0;x<bufSize;x++)
            map[z][x] = clamp(flat[z*bufSize+x], 0f, 1f);
    }

    private void drop(float[] flat, int size, float px, float pz, float sea, int maxLife,
                       float inertia, float gravity, float capFactor, float minCap,
                       float evaporate, float fallOff,
                       float erodeSpeed, float depositSpeed,
                       int[][] brushIdx, float[][] brushW, int radius) {
        float dirX = 0, dirZ = 0, sed = 0, spd = 1f, wat = 1f;

        // 边界检查使用更宽松的范围，配合镜像填充使用
        int minBound = 0;
        int maxBound = size - 1;

        for (int step = 0; step < maxLife; step++) {
            int ix = (int) px, iz = (int) pz;
            // 放宽边界检查：允许粒子在边界附近移动（镜像填充会处理越界）
            if (ix < minBound || ix >= maxBound || iz < minBound || iz >= maxBound) return;
            int idx = iz * size + ix;

            float fx = px - ix, fz = pz - iz;

            float hNW = flat[idx], hNE = flat[idx + 1];
            float hSW = flat[idx + size], hSE = flat[idx + size + 1];
            float h0 = hNW * (1 - fx) * (1 - fz) + hNE * fx * (1 - fz)
                     + hSW * (1 - fx) * fz + hSE * fx * fz;
            // 允许粒子在海平面附近继续移动，但限制侵蚀深度
            if (h0 <= sea - 0.05f) return;

            float gx = (hNE - hNW) * (1 - fz) + (hSE - hSW) * fz;
            float gz = (hSW - hNW) * (1 - fx) + (hSE - hNE) * fx;
            float glen = (float) Math.sqrt(gx * gx + gz * gz);
            if (glen < 1e-12f) return;

            dirX = dirX * inertia - gx * (1 - inertia);
            dirZ = dirZ * inertia - gz * (1 - inertia);
            float dlen = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (dlen < 1e-12f) return;
            dirX /= dlen; dirZ /= dlen;

            float npx = px + dirX;
            float npz = pz + dirZ;
            // 放宽边界检查：允许粒子在边界附近移动（镜像填充会处理越界）
            if (npx < minBound || npx >= maxBound || npz < minBound || npz >= maxBound) return;

            int nix = (int) npx, niz = (int) npz;
            float fnx = npx - nix, fnz = npz - niz;
            int nidx = niz * size + nix;

            float h1 = flat[nidx] * (1 - fnx) * (1 - fnz) + flat[nidx + 1] * fnx * (1 - fnz)
                     + flat[nidx + size] * (1 - fnx) * fnz + flat[nidx + size + 1] * fnx * fnz;

            float dh = (h1 - h0) * Math.min(1, h0 / fallOff);
            float cap = Math.max(-dh * spd * wat * capFactor, minCap);

            if (sed > cap || dh > 0) {
                float dep = dh > 0 ? Math.min(dh, sed) : (sed - cap) * depositSpeed;
                sed -= dep;
                flat[idx] += dep * (1 - fx) * (1 - fz);
                flat[idx + 1] += dep * fx * (1 - fz);
                flat[idx + size] += dep * (1 - fx) * fz;
                flat[idx + size + 1] += dep * fx * fz;
            } else {
                float eroAmt = Math.min((cap - sed) * erodeSpeed, -dh);
                int[] bi = brushIdx[idx];
                float[] bw = brushW[idx];
                if (bi != null) {
                    for (int b = 0; b < bi.length; b++) {
                        int ni = bi[b];
                        if (ni < 0 || ni >= flat.length) continue;
                        float delta = Math.min(flat[ni], eroAmt * bw[b]);
                        flat[ni] -= delta;
                        sed += delta;
                    }
                }
            }

            px = npx; pz = npz;
            spd = (float) Math.sqrt(spd * spd + dh * gravity);
            if (spd <= 0) return;
            wat *= (1 - evaporate);
        }
    }

    // ===== Helpers =====

    // 公开辅助方法（供 TerrainCache 调用）
    public static float[][] padMirror(float[][] src, int size, int pad) {
        return padGridMirror(src, size, pad);
    }
    public static float[][] bilinearUpsample(float[][] src, int srcSize, int dstSize) {
        return upsampleBilinear(src, srcSize, dstSize);
    }

    private static float[][] upsampleBilinear(float[][] src, int srcSize, int dstSize) {
        float[][] dst = new float[dstSize][dstSize];
        float scale = (float) srcSize / dstSize;
        for (int dz = 0; dz < dstSize; dz++) {
            for (int dx = 0; dx < dstSize; dx++) {
                float sx = dx * scale, sy = dz * scale;
                int ix = (int) sx, iy = (int) sy;
                float fx = sx - ix, fy = sy - iy;
                int ix1 = Math.min(ix + 1, srcSize - 1);
                int iy1 = Math.min(iy + 1, srcSize - 1);
                float v00 = src[iy][ix], v10 = src[iy][ix1];
                float v01 = src[iy1][ix], v11 = src[iy1][ix1];
                dst[dz][dx] = v00 * (1 - fx) * (1 - fy) + v10 * fx * (1 - fy)
                            + v01 * (1 - fx) * fy + v11 * fx * fy;
            }
        }
        return dst;
    }

    private static float[] bicubicUpsample(float[] src, int srcRes, int dstRes) {
        float[] dst = new float[dstRes * dstRes];
        float scale = (float) srcRes / dstRes;
        for (int dy = 0; dy < dstRes; dy++) {
            for (int dx = 0; dx < dstRes; dx++) {
                float sx = dx * scale, sy = dy * scale;
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
                    col[i + 1] = cubic(catmullRow(row[0], row[1], row[2], row[3]), fx);
                }
                dst[dy * dstRes + dx] = cubic(catmullRow(col[0], col[1], col[2], col[3]), fy);
            }
        }
        return dst;
    }

    private static float[] catmullRow(float a, float b, float c, float d) {
        return new float[]{a, b, c, d};
    }

    private static float cubic(float[] v, float t) {
        return v[1] + 0.5f * t * (v[2] - v[0] + t * (2 * v[0] - 5 * v[1] + 4 * v[2] - v[3] + t * (3 * (v[1] - v[2]) + v[3] - v[0])));
    }

    private static float[] flatten(float[][] grid, int size) {
        float[] flat = new float[size * size];
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                flat[z * size + x] = grid[z][x];
        return flat;
    }

    private static float[][] unflatten(float[] flat, int size) {
        float[][] grid = new float[size][size];
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                grid[z][x] = flat[z * size + x];
        return grid;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp(float v, float min, float max) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    private static long hashCoarse(int a, int b) {
        long h = a * 0x9e3779b9L + b * 0x9e3779b9L * 31;
        h = (h ^ (h >>> 16)) * 0x85ebca6bL;
        h = h ^ (h >>> 13);
        h = h * 0xc2b2ae35L;
        h = h ^ (h >>> 16);
        return h;
    }

    // 边界钳制填充（repeat edge），不创建虚假地形
    private static float[][] padGridMirror(float[][] src, int size, int pad) {
        int newSize = size + pad * 2;
        float[][] dst = new float[newSize][newSize];
        for (int z = 0; z < newSize; z++) {
            for (int x = 0; x < newSize; x++) {
                int sz = clamp(z - pad, 0, size - 1);
                int sx = clamp(x - pad, 0, size - 1);
                dst[z][x] = src[sz][sx];
            }
        }
        return dst;
    }

    private static void gaussianBlur(float[][] map, int size, float sigma) {
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
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++) {
                float s = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sx = clamp(x + k, 0, size - 1);
                    s += map[z][sx] * kernel[k + radius];
                }
                tmp[z][x] = s;
            }
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
