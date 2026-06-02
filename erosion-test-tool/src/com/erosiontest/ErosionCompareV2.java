package com.erosiontest;

import java.util.Random;

/**
 * 地形侵蚀对比测试 v2
 * 修复：GeoGenesis 方差=0 说明侵蚀过度压平地形
 * 优化：TerraForged 风格改用共享笔刷（不预计算逐像素笔刷）
 */
public class ErosionCompareV2 {

    static int SIZE = 112;

    // GeoGenesis 当前参数
    static int[] GG_DROPS = {4000, 2500, 1200};
    static int[] GG_RADIUS = {8, 4, 2};
    static float[] GG_STRENGTH = {1.5f, 1.0f, 0.6f};
    static float[] GG_ERODE = {0.3f, 0.2f, 0.1f};
    static float[] GG_DEPOSIT = {0.06f, 0.12f, 0.18f};

    // TerraForged 风格参数（单层+共享笔刷）
    static int TF_RADIUS = 4;
    static int TF_DROPS_PER_CHUNK = 350;
    static int TF_LIFETIME = 30;
    static float TF_INERTIA = 0.05f;
    static float TF_CAP_FACTOR = 4f;
    static float TF_MIN_CAP = 0.01f;
    static float TF_EVAPORATE = 0.01f;
    static float TF_GRAVITY = 3f;
    static float TF_ERODE = 0.3f;
    static float TF_DEPOSIT = 0.3f;
    static float TF_FALLOFF = 0.4f;

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

    static float[][] copyTerrain(float[][] src, int size) {
        float[][] dst = new float[size][size];
        for (int z = 0; z < size; z++)
            System.arraycopy(src[z], 0, dst[z], 0, size);
        return dst;
    }

    // ===== 共享笔刷初始化 =====
    static int[][] initSharedBrush(int radius, int bufSize) {
        int r2 = radius * radius;
        int count = 0;
        for (int dy = -radius; dy <= radius; dy++)
            for (int dx = -radius; dx <= radius; dx++)
                if (dx*dx + dy*dy < r2) count++;

        int[] offsets = new int[count];
        float[] weights = new float[count];
        int i = 0;
        float wSum = 0;
        for (int dy = -radius; dy <= radius; dy++)
            for (int dx = -radius; dx <= radius; dx++) {
                float d2 = dx*dx + dy*dy;
                if (d2 < r2) {
                    offsets[i] = dy * bufSize + dx;
                    weights[i] = 1f - (float)Math.sqrt(d2) / radius;
                    wSum += weights[i];
                    i++;
                }
            }
        for (int j = 0; j < count; j++) weights[j] /= wSum;
        return new int[][]{offsets, new int[]{count}};
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

            for (int z = 0; z < bufSize; z++)
                for (int x = 0; x < bufSize; x++) {
                    int sz = Math.max(0, Math.min(size - 1, z - pad));
                    int sx = Math.max(0, Math.min(size - 1, x - pad));
                    flat[z * bufSize + x] = heights[sz][sx];
                }

            // 共享笔刷
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

            for (int z = 0; z < size; z++)
                for (int x = 0; x < size; x++)
                    heights[z][x] = clamp(flat[(z+pad)*bufSize+(x+pad)], 0f, 1f);
        }
        return totalMs;
    }

    // ===== TerraForged 风格侵蚀（共享笔刷版）=====
    static long applyTFShared(float[][] heights, int size, int passes) {
        int radius = TF_RADIUS;
        int pad = radius + 2;
        int bufSize = size + pad * 2;
        float[] flat = new float[bufSize * bufSize];

        for (int z = 0; z < bufSize; z++)
            for (int x = 0; x < bufSize; x++) {
                int sz = Math.max(0, Math.min(size - 1, z - pad));
                int sx = Math.max(0, Math.min(size - 1, x - pad));
                flat[z * bufSize + x] = heights[sz][sx];
            }

        // 共享笔刷
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

                    for (int d = 0; d < TF_DROPS_PER_CHUNK; d++) {
                        float posX = pad + relX + fastRng.nextInt(16);
                        float posY = pad + relZ + fastRng.nextInt(16);
                        posX = clampF(posX, 1, bufSize - 2);
                        posY = clampF(posY, 1, bufSize - 2);

                        simulateDropTF(flat, bufSize, posX, posY, bOff, bWgt, bn);
                    }
                }
            }
        }

        long t2 = System.nanoTime();
        long totalMs = (t2 - t1) / 1_000_000;

        for (int z = 0; z < size; z++)
            for (int x = 0; x < size; x++)
                heights[z][x] = clamp(flat[(z+pad)*bufSize+(x+pad)], 0f, 1f);

        return totalMs;
    }

    // ===== GeoGenesis 粒子模拟 =====
    static void simulateDropGG(float[] flat, int bufSize, int px, int py,
                                float strength, float erodeSpeed, float depositSpeed,
                                int[] bOff, float[] bWgt, int bn) {
        float dirX=0, dirZ=0, sed=0, spd=1f, wat=1f;
        float inertia=0.001f, gravity=2.5f, capFactor=10f, minCap=0.005f;
        float evaporate=0.35f, fallOff=0.5f;
        float fpx = px+0.5f, fpz = py+0.5f;

        for (int step=0; step<30; step++) {
            int ix=(int)fpx, iz=(int)fpz;
            if (ix<1||ix>=bufSize-2||iz<1||iz>=bufSize-2) break;
            int idx = iz*bufSize+ix;
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

    // ===== TerraForged 风格粒子模拟（共享笔刷）=====
    static void simulateDropTF(float[] flat, int bufSize, float posX, float posY,
                                int[] bOff, float[] bWgt, int bn) {
        float dirX=0, dirY=0, sediment=0;
        float speed=1f, water=1f;

        for (int lifetime=0; lifetime<TF_LIFETIME; lifetime++) {
            int nodeX=(int)posX, nodeY=(int)posY;
            if (nodeX<1||nodeX>=bufSize-2||nodeY<1||nodeY>=bufSize-2) return;
            int dropletIndex = nodeY*bufSize+nodeX;
            float cellOffX = posX-nodeX, cellOffY = posY-nodeY;

            // 双线性插值
            float hNW=flat[dropletIndex], hNE=flat[dropletIndex+1];
            float hSW=flat[dropletIndex+bufSize], hSE=flat[dropletIndex+bufSize+1];
            float height = hNW*(1-cellOffX)*(1-cellOffY)+hNE*cellOffX*(1-cellOffY)
                         +hSW*(1-cellOffX)*cellOffY+hSE*cellOffX*cellOffY;
            float gradX = (hNE-hNW)*(1-cellOffY)+(hSE-hSW)*cellOffY;
            float gradY = (hSW-hNW)*(1-cellOffX)+(hSE-hNE)*cellOffX;

            // 高惯性方向更新
            dirX = dirX*TF_INERTIA - gradX*(1-TF_INERTIA);
            dirY = dirY*TF_INERTIA - gradY*(1-TF_INERTIA);
            float len = (float)Math.sqrt(dirX*dirX+dirY*dirY);
            if (len < 1e-12f) return;
            dirX/=len; dirY/=len;

            posX+=dirX; posY+=dirY;
            if (posX<1||posX>=bufSize-2||posY<1||posY>=bufSize-2) return;

            // 新高度
            int nNodeX=(int)posX, nNodeY=(int)posY;
            float nOffX=posX-nNodeX, nOffY=posY-nNodeY;
            int newIdx = nNodeY*bufSize+nNodeX;
            float newHeight = flat[newIdx]*(1-nOffX)*(1-nOffY)+flat[newIdx+1]*nOffX*(1-nOffY)
                            +flat[newIdx+bufSize]*(1-nOffX)*nOffY+flat[newIdx+bufSize+1]*nOffX*nOffY;

            // 高度衰减
            float falloff = getFalloff(height);
            float deltaHeight = (newHeight - height) * falloff;

            float sedimentCapacity = Math.max(-deltaHeight*speed*water*TF_CAP_FACTOR, TF_MIN_CAP);

            if (sediment>sedimentCapacity||deltaHeight>0) {
                float amountToDeposit = (deltaHeight>0)?Math.min(deltaHeight,sediment):(sediment-sedimentCapacity)*TF_DEPOSIT;
                sediment-=amountToDeposit;
                flat[dropletIndex]+=amountToDeposit*(1-cellOffX)*(1-cellOffY);
                flat[dropletIndex+1]+=amountToDeposit*cellOffX*(1-cellOffY);
                flat[dropletIndex+bufSize]+=amountToDeposit*(1-cellOffX)*cellOffY;
                flat[dropletIndex+bufSize+1]+=amountToDeposit*cellOffX*cellOffY;
            } else {
                float amountToErode = Math.min((sedimentCapacity-sediment)*TF_ERODE, -deltaHeight);
                // 使用共享笔刷（从当前位置偏移）
                for (int b=0; b<bn; b++) {
                    int bi = dropletIndex+bOff[b];
                    if (bi>=0 && bi<bufSize*bufSize) {
                        float weighedErodeAmount = amountToErode*bWgt[b];
                        float deltaSediment = Math.min(flat[bi], weighedErodeAmount);
                        flat[bi]-=deltaSediment; sediment+=deltaSediment;
                    }
                }
            }

            float speed2 = speed*speed+deltaHeight*TF_GRAVITY;
            if (speed2<=0) return;
            speed = (float)Math.sqrt(speed2);
            water *= (1-TF_EVAPORATE);
        }
    }

    static float getFalloff(float height) {
        if (height >= TF_FALLOFF) return 1f;
        return height / TF_FALLOFF;
    }

    // ===== 统计指标 =====
    static float variance(float[][] h, int size) {
        float mean=0;
        for (int z=0;z<size;z++) for (int x=0;x<size;x++) mean+=h[z][x];
        mean/=size*size;
        float var=0;
        for (int z=0;z<size;z++) for (int x=0;x<size;x++) { float d=h[z][x]-mean; var+=d*d; }
        return var/(size*size);
    }

    static float avgGradient(float[][] h, int size) {
        float total=0; int count=0;
        for (int z=1;z<size-1;z++) for (int x=1;x<size-1;x++) {
            float gx=h[z][x+1]-h[z][x-1], gz=h[z+1][x]-h[z-1][x];
            total+=(float)Math.sqrt(gx*gx+gz*gz); count++;
        }
        return total/count;
    }

    static float roughness(float[][] h, int size) {
        float total=0; int count=0;
        for (int z=1;z<size-1;z++) for (int x=1;x<size-1;x++) {
            float avg=(h[z-1][x]+h[z+1][x]+h[z][x-1]+h[z][x+1])*0.25f;
            float d=h[z][x]-avg; total+=d*d; count++;
        }
        return (float)Math.sqrt(total/count);
    }

    // ===== 辅助 =====
    static long seed(int a, int b) {
        long h = a*0x9e3779b9L+b*0x9e3779b9L*31;
        h=(h^(h>>>16))*0x85ebca6bL; h=h^(h>>>13); h=h*0xc2b2ae35L; h=h^(h>>>16);
        return h;
    }
    static float clamp(float v, float min, float max) {
        if (Float.isNaN(v)||Float.isInfinite(v)) return min;
        return Math.max(min, Math.min(max, v));
    }
    static float clampF(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }

    static class FastRandom {
        private long state1, state2;
        FastRandom(long seed) { state1=seed; state2=seed^0x5DEECE66DL; }
        void seed(long a, long b) { state1=a^b; state2=(a*31+b*17)^0x5DEECE66DL; }
        int nextInt(int bound) {
            state1=state1*0x5DEECE66DL+0xBL; state2=state2*0x5DEECE66DL+0xBL;
            long mixed=state1^state2; return (int)((mixed&0x7FFFFFFFL)%bound);
        }
    }

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  地形侵蚀对比测试 v2");
        System.out.println("========================================");
        System.out.println("Tile size: " + SIZE + "x" + SIZE);
        System.out.println();

        float[][] baseTerrain = generateTerrain(SIZE, 12345L);
        float baseVar = variance(baseTerrain, SIZE);
        float baseGrad = avgGradient(baseTerrain, SIZE);
        float baseRough = roughness(baseTerrain, SIZE);
        System.out.println("原始地形: 方差=" + String.format("%.6f", baseVar)
            + ", 梯度=" + String.format("%.6f", baseGrad)
            + ", 粗糙度=" + String.format("%.6f", baseRough));
        System.out.println();

        // GeoGenesis 当前
        float[][] gg = copyTerrain(baseTerrain, SIZE);
        long ggMs = applyGeoGenesisErosion(gg, SIZE);
        System.out.println("--- GeoGenesis 当前 (7700粒子, 3层) ---");
        System.out.println("  耗时: " + ggMs + "ms");
        System.out.println("  方差: " + String.format("%.6f", variance(gg, SIZE))
            + ", 梯度: " + String.format("%.6f", avgGradient(gg, SIZE))
            + ", 粗糙度: " + String.format("%.6f", roughness(gg, SIZE)));
        System.out.println();

        // TerraForged 风格（共享笔刷，不同轮数）
        int[] passesArr = {1, 2, 3, 5, 8};
        for (int p : passesArr) {
            float[][] tf = copyTerrain(baseTerrain, SIZE);
            long tfMs = applyTFShared(tf, SIZE, p);
            int totalDrops = TF_DROPS_PER_CHUNK * (SIZE/16) * (SIZE/16) * p;
            System.out.println("--- TF风格 (" + p + "轮, " + totalDrops + "粒子, 共享笔刷) ---");
            System.out.println("  耗时: " + tfMs + "ms, 加速比: " + String.format("%.1f", (float)ggMs/Math.max(1,tfMs)) + "x");
            System.out.println("  方差: " + String.format("%.6f", variance(tf, SIZE))
                + ", 梯度: " + String.format("%.6f", avgGradient(tf, SIZE))
                + ", 粗糙度: " + String.format("%.6f", roughness(tf, SIZE)));
        }

        System.out.println();

        // GeoGenesis 减少粒子
        System.out.println("--- GeoGenesis 减少粒子 ---");
        float[] mulRatios = {0.5f, 0.25f, 0.1f};
        for (float mul : mulRatios) {
            float[][] test = copyTerrain(baseTerrain, SIZE);
            int[] origDrops = GG_DROPS.clone();
            for (int i=0;i<GG_DROPS.length;i++) GG_DROPS[i]=(int)(origDrops[i]*mul);
            long testMs = applyGeoGenesisErosion(test, SIZE);
            int totalDrops = GG_DROPS[0]+GG_DROPS[1]+GG_DROPS[2];
            System.out.println("  乘数=" + mul + ", 粒子=" + totalDrops
                + ", 耗时=" + testMs + "ms"
                + ", 方差=" + String.format("%.6f", variance(test, SIZE))
                + ", 梯度=" + String.format("%.6f", avgGradient(test, SIZE))
                + ", 粗糙度=" + String.format("%.6f", roughness(test, SIZE))
                + ", 加速比=" + String.format("%.1f", (float)ggMs/Math.max(1,testMs)) + "x");
            System.arraycopy(origDrops, 0, GG_DROPS, 0, origDrops.length);
        }
    }
}
