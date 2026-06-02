package com.erosiontest;

import java.util.Random;

/**
 * 地形侵蚀对比测试：GeoGenesis 当前 vs TerraForged 风格
 *
 * 测试目标：
 *   1. 验证 TerraForged 风格（少粒子+预计算笔刷+高度衰减）能否达到同等效果
 *   2. 测量性能提升
 *   3. 用地形统计指标（方差、梯度、粗糙度）评估侵蚀质量
 */
public class ErosionCompareTest {

    static int SIZE = 112;

    // ===== GeoGenesis 当前参数 =====
    static int[] GG_DROPS = {4000, 2500, 1200};
    static int[] GG_RADIUS = {8, 4, 2};
    static float[] GG_STRENGTH = {1.5f, 1.0f, 0.6f};
    static float[] GG_ERODE = {0.3f, 0.2f, 0.1f};
    static float[] GG_DEPOSIT = {0.06f, 0.12f, 0.18f};

    // ===== TerraForged 风格参数 =====
    // 关键差异：少粒子 + 大半径 + 高衰减 + 高惯性
    static int TF_RADIUS = 4;
    static int TF_DROPS = 350;       // TerraForged 每chunk 350
    static int TF_LIFETIME = 30;
    static float TF_INERTIA = 0.05f;  // TerraForged 用 0.05
    static float TF_CAP_FACTOR = 4f;   // TerraForged 用 4
    static float TF_MIN_CAP = 0.01f;
    static float TF_EVAPORATE = 0.01f; // TerraForged 用 0.01（几乎不蒸发）
    static float TF_GRAVITY = 3f;      // TerraForged 用 3
    static float TF_ERODE = 0.3f;
    static float TF_DEPOSIT = 0.3f;
    static float TF_FALLOFF = 0.4f;    // TerraForged 高度衰减阈值

    // ===== 生成测试地形 =====
    static float[][] generateTerrain(int size, long seed) {
        Random rng = new Random(seed);
        float[][] h = new float[size][size];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                float nx = x * 0.02f, nz = z * 0.02f;
                h[z][x] = 0.3f + 0.4f * (float)(Math.sin(nx * 3) * Math.cos(nz * 2)
                    + Math.sin(nx * 7 + nz * 5) * 0.3);
                h[z][x] = Math.max(0, Math.min(1, h[z][x]));
            }
        }
        return h;
    }

    // ===== 复制地形 =====
    static float[][] copyTerrain(float[][] src, int size) {
        float[][] dst = new float[size][size];
        for (int z = 0; z < size; z++)
            System.arraycopy(src[z], 0, dst[z], 0, size);
        return dst;
    }

    // ===== GeoGenesis 当前侵蚀 =====
    static long applyGeoGenesisErosion(float[][] heights, int size) {
        long totalMs = 0;

        for (int li = 0; li < GG_DROPS.length; li++) {
            int drops = GG_DROPS[li];
            int radius = GG_RADIUS[li];
            float strength = GG_STRENGTH[li];
            float erodeSpeed = GG_ERODE[li];
            float depositSpeed = GG_DEPOSIT[li];

            int pad = Math.max(radius * 2, 4);
            int bufSize = size + pad * 2;
            float[] flat = new float[bufSize * bufSize];

            // 填充
            for (int z = 0; z < bufSize; z++)
                for (int x = 0; x < bufSize; x++) {
                    int sz = Math.max(0, Math.min(size - 1, z - pad));
                    int sx = Math.max(0, Math.min(size - 1, x - pad));
                    flat[z * bufSize + x] = heights[sz][sx];
                }

            // 共享笔刷
            int[] bOff; float[] bWgt; int bn;
            int[] brushResult = initSharedBrush(radius, bufSize);
            bn = brushResult[0];
            bOff = new int[bn]; bWgt = new float[bn];
            // 重新计算（简化）
            int r2 = radius * radius;
            int maxB = (2*radius+1)*(2*radius+1);
            int[] tmpOff = new int[maxB]; float[] tmpWgt = new float[maxB];
            bn = 0;
            for (int dy = -radius; dy <= radius; dy++)
                for (int dx = -radius; dx <= radius; dx++) {
                    float d2 = dx*dx + dy*dy;
                    if (d2 < r2) { tmpOff[bn] = dy*bufSize+dx; tmpWgt[bn] = 1f-(float)Math.sqrt(d2)/radius; bn++; }
                }
            { float s=0; for(int i=0;i<bn;i++)s+=tmpWgt[i]; for(int i=0;i<bn;i++)tmpWgt[i]/=s; }
            bOff = tmpOff; bWgt = tmpWgt;

            long t1 = System.nanoTime();
            Random rng = new Random(12345L);
            int spawnMin = pad, spawnMax = pad + size, spawnRange = spawnMax - spawnMin;

            for (int i = 0; i < drops; i++) {
                int px = spawnMin + rng.nextInt(spawnRange);
                int py = spawnMin + rng.nextInt(spawnRange);
                int idx = py * bufSize + px;
                if (flat[idx] <= 0.02f) continue;
                simulateDropGG(flat, bufSize, px, py, strength, erodeSpeed, depositSpeed, bOff, bWgt, bn);
            }
            long t2 = System.nanoTime();
            totalMs += (t2 - t1) / 1_000_000;

            // 写回
            for (int z = 0; z < size; z++)
                for (int x = 0; x < size; x++)
                    heights[z][x] = clamp(flat[(z+pad)*bufSize+(x+pad)], 0f, 1f);
        }
        return totalMs;
    }

    // ===== TerraForged 风格侵蚀 =====
    static long applyTerraForgedErosion(float[][] heights, int size) {
        int radius = TF_RADIUS;
        int pad = radius + 2;
        int bufSize = size + pad * 2;
        float[] flat = new float[bufSize * bufSize];

        // 填充
        for (int z = 0; z < bufSize; z++)
            for (int x = 0; x < bufSize; x++) {
                int sz = Math.max(0, Math.min(size - 1, z - pad));
                int sx = Math.max(0, Math.min(size - 1, x - pad));
                flat[z * bufSize + x] = heights[sz][sx];
            }

        // 预计算每个位置的笔刷（TerraForged 方式）
        int[][] brushIndices = new int[bufSize * bufSize][];
        float[][] brushWeights = new float[bufSize * bufSize][];
        initPerPixelBrushes(bufSize, radius, brushIndices, brushWeights);

        long t1 = System.nanoTime();

        // TerraForged 方式：按 chunk 分组生成粒子
        // 每个 16x16 chunk 生成 TF_DROPS 个粒子
        int chunksX = size / 16;
        int chunksZ = size / 16;
        FastRandom fastRng = new FastRandom(12345L);

        for (int iteration = 0; iteration < 1; iteration++) {
            long iterSeed = seed(12345, iteration);
            for (int cz = 0; cz < chunksZ; cz++) {
                int relZ = cz << 4;
                for (int cx = 0; cx < chunksX; cx++) {
                    int relX = cx << 4;
                    long chunkSeed = seed(cx, cz);
                    fastRng.seed(chunkSeed, iterSeed);

                    for (int d = 0; d < TF_DROPS; d++) {
                        float posX = pad + relX + fastRng.nextInt(16);
                        float posY = pad + relZ + fastRng.nextInt(16);
                        posX = clampF(posX, 1, bufSize - 2);
                        posY = clampF(posY, 1, bufSize - 2);

                        simulateDropTF(flat, bufSize, posX, posY, brushIndices, brushWeights);
                    }
                }
            }
        }

        long t2 = System.nanoTime();
        long totalMs = (t2 - t1) / 1_000_000;

        // 写回
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                heights[z][x] = clamp(flat[(z+pad)*bufSize+(x+pad)], 0f, 1f);

        return totalMs;
    }

    // ===== TerraForged 风格侵蚀（多轮迭代版）=====
    static long applyTerraForgedMultiPass(float[][] heights, int size, int passes) {
        int radius = TF_RADIUS;
        int pad = radius + 2;
        int bufSize = size + pad * 2;
        float[] flat = new float[bufSize * bufSize];

        // 填充
        for (int z = 0; z < bufSize; z++)
            for (int x = 0; x < bufSize; x++) {
                int sz = Math.max(0, Math.min(size - 1, z - pad));
                int sx = Math.max(0, Math.min(size - 1, x - pad));
                flat[z * bufSize + x] = heights[sz][sx];
            }

        // 预计算每个位置的笔刷
        int[][] brushIndices = new int[bufSize * bufSize][];
        float[][] brushWeights = new float[bufSize * bufSize][];
        initPerPixelBrushes(bufSize, radius, brushIndices, brushWeights);

        long t1 = System.nanoTime();

        int chunksX = size / 16;
        int chunksZ = size / 16;
        FastRandom fastRng = new FastRandom(12345L);

        for (int iteration = 0; iteration < passes; iteration++) {
            long iterSeed = seed(12345, iteration);
            for (int cz = 0; cz < chunksZ; cz++) {
                int relZ = cz << 4;
                for (int cx = 0; cx < chunksX; cx++) {
                    int relX = cx << 4;
                    long chunkSeed = seed(cx, cz);
                    fastRng.seed(chunkSeed, iterSeed);

                    for (int d = 0; d < TF_DROPS; d++) {
                        float posX = pad + relX + fastRng.nextInt(16);
                        float posY = pad + relZ + fastRng.nextInt(16);
                        posX = clampF(posX, 1, bufSize - 2);
                        posY = clampF(posY, 1, bufSize - 2);

                        simulateDropTF(flat, bufSize, posX, posY, brushIndices, brushWeights);
                    }
                }
            }
        }

        long t2 = System.nanoTime();
        long totalMs = (t2 - t1) / 1_000_000;

        // 写回
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                heights[z][x] = clamp(flat[(z+pad)*bufSize+(x+pad)], 0f, 1f);

        return totalMs;
    }

    // ===== GeoGenesis 粒子模拟 =====
    static void simulateDropGG(float[] flat, int bufSize, int px, int py,
                                float strength, float erodeSpeed, float depositSpeed,
                                int[] bOff, float[] bWgt, int bn) {
        float dirX = 0, dirZ = 0, sed = 0, spd = 1f, wat = 1f;
        float inertia = 0.001f, gravity = 2.5f, capFactor = 10f, minCap = 0.005f;
        float evaporate = 0.35f, fallOff = 0.5f;
        float fpx = px + 0.5f, fpz = py + 0.5f;

        for (int step = 0; step < 30; step++) {
            int ix = (int)fpx, iz = (int)fpz;
            if (ix < 1 || ix >= bufSize-2 || iz < 1 || iz >= bufSize-2) break;
            int idx = iz*bufSize+ix;
            float fx = fpx-ix, fz = fpz-iz;
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
                flat[idx]+=dep*(1-fx)*(1-fz); flat[idx+1]+=dep*fx*(1-fz);
                flat[idx+bufSize]+=dep*(1-fx)*fz; flat[idx+bufSize+1]+=dep*fx*fz;
            } else {
                float eroAmt = Math.min((cap-sed)*erodeSpeed, -dh);
                for (int b=0; b<bn; b++) {
                    int bi = idx+bOff[b];
                    if (bi>=0 && bi<bufSize*bufSize) {
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

    // ===== TerraForged 风格粒子模拟 =====
    // 关键差异：高惯性(0.05)、高度衰减(0.4)、低蒸发(0.01)、逐像素笔刷
    static void simulateDropTF(float[] flat, int bufSize, float posX, float posY,
                                int[][] brushIndices, float[][] brushWeights) {
        float dirX = 0, dirY = 0, sediment = 0;
        float speed = 1f, water = 1f;

        for (int lifetime = 0; lifetime < TF_LIFETIME; lifetime++) {
            int nodeX = (int) posX;
            int nodeY = (int) posY;
            int dropletIndex = nodeY * bufSize + nodeX;

            if (nodeX < 1 || nodeX >= bufSize-2 || nodeY < 1 || nodeY >= bufSize-2) return;

            float cellOffsetX = posX - nodeX;
            float cellOffsetY = posY - nodeY;

            // 双线性插值计算高度和梯度
            float hNW = flat[dropletIndex], hNE = flat[dropletIndex + 1];
            float hSW = flat[dropletIndex + bufSize], hSE = flat[dropletIndex + bufSize + 1];

            float height = hNW*(1-cellOffsetX)*(1-cellOffsetY) + hNE*cellOffsetX*(1-cellOffsetY)
                         + hSW*(1-cellOffsetX)*cellOffsetY + hSE*cellOffsetX*cellOffsetY;
            float gradX = (hNE - hNW)*(1-cellOffsetY) + (hSE - hSW)*cellOffsetY;
            float gradY = (hSW - hNW)*(1-cellOffsetX) + (hSE - hNE)*cellOffsetX;

            // 更新方向（高惯性：0.05）
            dirX = dirX * TF_INERTIA - gradX * (1 - TF_INERTIA);
            dirY = dirY * TF_INERTIA - gradY * (1 - TF_INERTIA);

            // 归一化
            float len = (float) Math.sqrt(dirX*dirX + dirY*dirY);
            if (len < 1e-12f) return;
            dirX /= len; dirY /= len;

            posX += dirX; posY += dirY;

            if (posX < 1 || posX >= bufSize-2 || posY < 1 || posY >= bufSize-2) return;

            // 计算新高度
            int newNodeX = (int) posX, newNodeY = (int) posY;
            float newOffX = posX - newNodeX, newOffY = posY - newNodeY;
            int newIdx = newNodeY * bufSize + newNodeX;
            float newHeight = flat[newIdx]*(1-newOffX)*(1-newOffY) + flat[newIdx+1]*newOffX*(1-newOffY)
                            + flat[newIdx+bufSize]*(1-newOffX)*newOffY + flat[newIdx+bufSize+1]*newOffX*newOffY;

            // 高度衰减（TerraForged 关键优化）
            float falloff = getFalloff(height);
            float deltaHeight = (newHeight - height) * falloff;

            // 搬运能力
            float sedimentCapacity = Math.max(-deltaHeight * speed * water * TF_CAP_FACTOR, TF_MIN_CAP);

            if (sediment > sedimentCapacity || deltaHeight > 0) {
                // 沉积
                float amountToDeposit = (deltaHeight > 0)
                    ? Math.min(deltaHeight, sediment)
                    : (sediment - sedimentCapacity) * TF_DEPOSIT;
                sediment -= amountToDeposit;

                // 双线性插值沉积到4个节点
                flat[dropletIndex] += amountToDeposit * (1-cellOffsetX) * (1-cellOffsetY);
                flat[dropletIndex + 1] += amountToDeposit * cellOffsetX * (1-cellOffsetY);
                flat[dropletIndex + bufSize] += amountToDeposit * (1-cellOffsetX) * cellOffsetY;
                flat[dropletIndex + bufSize + 1] += amountToDeposit * cellOffsetX * cellOffsetY;
            } else {
                // 侵蚀（使用逐像素预计算笔刷）
                float amountToErode = Math.min((sedimentCapacity - sediment) * TF_ERODE, -deltaHeight);

                int[] bi = brushIndices[dropletIndex];
                float[] bw = brushWeights[dropletIndex];
                for (int i = 0; i < bi.length; i++) {
                    int nodeIndex = bi[i];
                    if (nodeIndex >= 0 && nodeIndex < flat.length) {
                        float weighedErodeAmount = amountToErode * bw[i];
                        float deltaSediment = Math.min(flat[nodeIndex], weighedErodeAmount);
                        flat[nodeIndex] -= deltaSediment;
                        sediment += deltaSediment;
                    }
                }
            }

            // 更新速度和水量
            float speed2 = speed * speed + deltaHeight * TF_GRAVITY;
            if (speed2 <= 0) return;
            speed = (float) Math.sqrt(speed2);
            water *= (1 - TF_EVAPORATE);
        }
    }

    // ===== TerraForged 高度衰减 =====
    static float getFalloff(float height) {
        if (height >= TF_FALLOFF) return 1f;
        return height / TF_FALLOFF;
    }

    // ===== 预计算逐像素笔刷（TerraForged 方式）=====
    static void initPerPixelBrushes(int size, int radius, int[][] indices, float[][] weights) {
        int[] xOffsets = new int[radius * radius * 4];
        int[] yOffsets = new int[radius * radius * 4];
        float[] ws = new float[radius * radius * 4];
        float weightSum = 0;
        int addIndex = 0;

        for (int i = 0; i < size * size; i++) {
            int centreX = i % size;
            int centreY = i / size;

            // 边缘像素需要重新计算笔刷
            if (centreY <= radius || centreY >= size - radius
                || centreX <= radius + 1 || centreX >= size - radius) {
                weightSum = 0;
                addIndex = 0;
                for (int y = -radius; y <= radius; y++) {
                    for (int x = -radius; x <= radius; x++) {
                        float sqrDst = x * x + y * y;
                        if (sqrDst < radius * radius) {
                            int coordX = centreX + x;
                            int coordY = centreY + y;
                            if (coordX >= 0 && coordX < size && coordY >= 0 && coordY < size) {
                                float weight = 1 - (float) Math.sqrt(sqrDst) / radius;
                                weightSum += weight;
                                ws[addIndex] = weight;
                                xOffsets[addIndex] = x;
                                yOffsets[addIndex] = y;
                                addIndex++;
                            }
                        }
                    }
                }
            }

            int numEntries = addIndex;
            indices[i] = new int[numEntries];
            weights[i] = new float[numEntries];
            for (int j = 0; j < numEntries; j++) {
                indices[i][j] = (yOffsets[j] + centreY) * size + xOffsets[j] + centreX;
                weights[i][j] = ws[j] / weightSum;
            }
        }
    }

    // ===== 地形统计指标 =====
    static float variance(float[][] h, int size) {
        float mean = 0;
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                mean += h[z][x];
        mean /= size * size;
        float var = 0;
        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++) {
                float d = h[z][x] - mean;
                var += d * d;
            }
        return var / (size * size);
    }

    static float avgGradient(float[][] h, int size) {
        float total = 0;
        int count = 0;
        for (int z = 1; z < size - 1; z++) {
            for (int x = 1; x < size - 1; x++) {
                float gx = h[z][x+1] - h[z][x-1];
                float gz = h[z+1][x] - h[z-1][x];
                total += (float) Math.sqrt(gx*gx + gz*gz);
                count++;
            }
        }
        return total / count;
    }

    static float roughness(float[][] h, int size) {
        float total = 0;
        int count = 0;
        for (int z = 1; z < size - 1; z++) {
            for (int x = 1; x < size - 1; x++) {
                float avg = (h[z-1][x] + h[z+1][x] + h[z][x-1] + h[z][x+1]) * 0.25f;
                float d = h[z][x] - avg;
                total += d * d;
                count++;
            }
        }
        return (float) Math.sqrt(total / count);
    }

    // ===== 辅助方法 =====
    static int[] initSharedBrush(int radius, int bufSize) {
        int r2 = radius * radius;
        int maxB = (2*radius+1)*(2*radius+1);
        int count = 0;
        for (int dy = -radius; dy <= radius; dy++)
            for (int dx = -radius; dx <= radius; dx++)
                if (dx*dx + dy*dy < r2) count++;
        return new int[]{count};
    }

    static long seed(int a, int b) {
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

    static float clampF(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    // ===== 快速随机数生成器（模拟 TerraForged 的 FastRandom）=====
    static class FastRandom {
        private long state1;
        private long state2;

        FastRandom(long seed) {
            this.state1 = seed;
            this.state2 = seed ^ 0x5DEECE66DL;
        }

        void seed(long a, long b) {
            this.state1 = a ^ b;
            this.state2 = (a * 31 + b * 17) ^ 0x5DEECE66DL;
        }

        int nextInt(int bound) {
            state1 = state1 * 0x5DEECE66DL + 0xBL;
            state2 = state2 * 0x5DEECE66DL + 0xBL;
            long mixed = state1 ^ state2;
            return (int)((mixed & 0x7FFFFFFFL) % bound);
        }
    }

    // ===== 主测试 =====
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  地形侵蚀对比测试");
        System.out.println("  GeoGenesis 当前 vs TerraForged 风格");
        System.out.println("========================================");
        System.out.println("Tile size: " + SIZE + "x" + SIZE);
        System.out.println();

        // 生成基础地形
        float[][] baseTerrain = generateTerrain(SIZE, 12345L);

        // ===== 测试1: GeoGenesis 当前 =====
        float[][] ggTerrain = copyTerrain(baseTerrain, SIZE);
        long ggMs = applyGeoGenesisErosion(ggTerrain, SIZE);
        float ggVar = variance(ggTerrain, SIZE);
        float ggGrad = avgGradient(ggTerrain, SIZE);
        float ggRough = roughness(ggTerrain, SIZE);

        System.out.println("--- GeoGenesis 当前 ---");
        System.out.println("  总粒子数: " + (GG_DROPS[0]+GG_DROPS[1]+GG_DROPS[2]));
        System.out.println("  耗时: " + ggMs + "ms");
        System.out.println("  方差: " + String.format("%.6f", ggVar));
        System.out.println("  平均梯度: " + String.format("%.6f", ggGrad));
        System.out.println("  粗糙度: " + String.format("%.6f", ggRough));
        System.out.println();

        // ===== 测试2: TerraForged 风格（1轮）=====
        float[][] tf1Terrain = copyTerrain(baseTerrain, SIZE);
        long tf1Ms = applyTerraForgedErosion(tf1Terrain, SIZE);
        float tf1Var = variance(tf1Terrain, SIZE);
        float tf1Grad = avgGradient(tf1Terrain, SIZE);
        float tf1Rough = roughness(tf1Terrain, SIZE);

        System.out.println("--- TerraForged 风格 (1轮, " + TF_DROPS + "粒子/chunk) ---");
        System.out.println("  总粒子数: " + (TF_DROPS * (SIZE/16) * (SIZE/16)));
        System.out.println("  耗时: " + tf1Ms + "ms");
        System.out.println("  方差: " + String.format("%.6f", tf1Var));
        System.out.println("  平均梯度: " + String.format("%.6f", tf1Grad));
        System.out.println("  粗糙度: " + String.format("%.6f", tf1Rough));
        System.out.println();

        // ===== 测试3: TerraForged 风格（多轮）=====
        int[] passes = {2, 3, 5};
        for (int p : passes) {
            float[][] tfpTerrain = copyTerrain(baseTerrain, SIZE);
            long tfpMs = applyTerraForgedMultiPass(tfpTerrain, SIZE, p);
            float tfpVar = variance(tfpTerrain, SIZE);
            float tfpGrad = avgGradient(tfpTerrain, SIZE);
            float tfpRough = roughness(tfpTerrain, SIZE);

            int totalDrops = TF_DROPS * (SIZE/16) * (SIZE/16) * p;
            System.out.println("--- TerraForged 风格 (" + p + "轮, " + totalDrops + "粒子) ---");
            System.out.println("  耗时: " + tfpMs + "ms");
            System.out.println("  方差: " + String.format("%.6f", tfpVar));
            System.out.println("  平均梯度: " + String.format("%.6f", tfpGrad));
            System.out.println("  粗糙度: " + String.format("%.6f", tfpRough));
            System.out.println("  加速比: " + String.format("%.1f", (float)ggMs / Math.max(1, tfpMs)) + "x");
            System.out.println();
        }

        // ===== 测试4: GeoGenesis 参数调优（减少粒子数）=====
        System.out.println("--- GeoGenesis 减少粒子测试 ---");
        float[] mulRatios = {0.5f, 0.25f, 0.1f};
        for (float mul : mulRatios) {
            float[][] testTerrain = copyTerrain(baseTerrain, SIZE);
            // 临时修改粒子数
            int[] origDrops = GG_DROPS.clone();
            for (int i = 0; i < GG_DROPS.length; i++)
                GG_DROPS[i] = (int)(origDrops[i] * mul);

            long testMs = applyGeoGenesisErosion(testTerrain, SIZE);
            float testVar = variance(testTerrain, SIZE);
            float testGrad = avgGradient(testTerrain, SIZE);
            float testRough = roughness(testTerrain, SIZE);

            int totalDrops = GG_DROPS[0]+GG_DROPS[1]+GG_DROPS[2];
            System.out.println("  乘数=" + mul + ", 粒子=" + totalDrops
                + ", 耗时=" + testMs + "ms"
                + ", 方差=" + String.format("%.6f", testVar)
                + ", 梯度=" + String.format("%.6f", testGrad)
                + ", 粗糙度=" + String.format("%.6f", testRough)
                + ", 加速比=" + String.format("%.1f", (float)ggMs / Math.max(1, testMs)) + "x");

            // 恢复
            System.arraycopy(origDrops, 0, GG_DROPS, 0, origDrops.length);
        }

        System.out.println();
        System.out.println("========================================");
        System.out.println("  原始地形参考");
        System.out.println("========================================");
        float baseVar = variance(baseTerrain, SIZE);
        float baseGrad = avgGradient(baseTerrain, SIZE);
        float baseRough = roughness(baseTerrain, SIZE);
        System.out.println("  方差: " + String.format("%.6f", baseVar));
        System.out.println("  平均梯度: " + String.format("%.6f", baseGrad));
        System.out.println("  粗糙度: " + String.format("%.6f", baseRough));
    }
}
