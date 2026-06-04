package com.geogenesis.worldgen.hydrology;

import java.util.Arrays;
import java.util.Random;

/**
 * SimpleHydrology Engine — 基于 V30 调试工具的完整移植版。
 *
 * 核心原理：
 * 1. 粒子从1格宽的源头出发，沿梯度下流，discharge 累积 → 河流自然从窄到宽
 * 2. 动量地图 mx/my — 累积水流方向，形成"河道记忆"
 * 3. erf(0.4 * discharge) 压缩 — 非线性放大主干道
 * 4. delta 步 — 粒子入海后继续记录5步 discharge，防止河口断流
 * 5. cascade 热侵蚀 — 排序邻居+距离加权平滑，防止地形锯齿
 * 6. valley spawn — laplacian 谷地检测 + CDF 采样，优先在谷地放粒子
 */
public class SimpleHydrologyEngine {

    // ===== 参数（来自 SimpleHydrology V30） =====
    private static final float LRATE = 0.1f;
    private static final float GRAVITY = 1.0f;
    private static final float MOMENTUM = 1.0f;
    private static final float EVAP = 0.001f;
    private static final float DEPOSITION = 0.1f;
    private static final float ENTRAINMENT = 10.0f;
    private static final float MIN_VOL = 0.01f;
    private static final float MAX_AGE = 500;
    private static final float MAXDIFF = 0.01f;
    private static final float SETTLING = 0.8f;
    private static final int DELTA_STEPS = 5;
    private static final float MIN_DISCHARGE_THRESHOLD = 0.5f;

    private final long seed;
    private float entrainment = ENTRAINMENT;
    private float evap = EVAP;
    private float slopeAttenuationHeight = 0.0f;
    private float depositionRate = DEPOSITION;
    private int cascadeInterval = 1;
    private boolean erosionEnabled = true; // 默认开启侵蚀

    public SimpleHydrologyEngine(long seed) {
        this.seed = seed;
    }

    /** 设置是否允许水文粒子侵蚀地形。关闭时粒子只记录 discharge，不修改高度图。 */
    public SimpleHydrologyEngine setErosionEnabled(boolean enabled) {
        this.erosionEnabled = enabled;
        return this;
    }

    /**
     * 运行水文模拟，返回 discharge 图。
     *
     * @param heights 高度图（会被修改：水文粒子侵蚀河道）
     * @param size 高度图尺寸
     * @param iterations 迭代次数
     * @param dropsPerIter 每次迭代粒子数
     * @param seaNorm 海平面归一化高度
     * @param ox tile 世界 X 原点
     * @param oz tile 世界 Z 原点
     * @return discharge 图（与 heights 同尺寸）
     */
    public float[][] applyHydrology(float[][] heights, int size, int iterations, int dropsPerIter,
                                     float seaNorm, int ox, int oz) {
        int totalSize = size * size;
        float[] flatH = new float[totalSize];
        float[] flatD = new float[totalSize];
        float[] flatDT = new float[totalSize];
        float[] flatMX = new float[totalSize];
        float[] flatMY = new float[totalSize];
        float[] flatMXT = new float[totalSize];
        float[] flatMYT = new float[totalSize];

        // 展平高度图
        for (int y = 0; y < size; y++) {
            System.arraycopy(heights[y], 0, flatH, y * size, size);
        }

        // 使用全局种子（不按 tile 偏移），确保相邻 tile 的粒子行为一致
        // 不同 tile 的地形不同 → 粒子流向不同 → discharge 仍因地形而异
        // 但边界处不会有系统性不连续（之前 ox/oz 偏移导致边界断裂）
        Random rng = new Random(seed);

        // Valley spawn: 预计算谷地像素（laplacian > 0 的像素是谷地）
        int[] valleyX = null, valleyY = null;
        float[] valleyCDF = null;
        int valleyCount = 0;
        float riverSpawnFraction = 0.15f;

        {
            java.util.List<int[]> cells = new java.util.ArrayList<>();
            java.util.List<Float> weights = new java.util.ArrayList<>();
            for (int y = 2; y < size - 2; y++) {
                for (int x = 2; x < size - 2; x++) {
                    if (flatH[y * size + x] < seaNorm) continue;
                    float lap = flatH[(y-1)*size+x] + flatH[(y+1)*size+x]
                              + flatH[y*size+x-1] + flatH[y*size+x+1]
                              - 4 * flatH[y*size+x];
                    if (lap > 0.002f) {
                        cells.add(new int[]{x, y});
                        weights.add(lap);
                    }
                }
            }
            valleyCount = cells.size();
            if (valleyCount > 0) {
                valleyX = new int[valleyCount];
                valleyY = new int[valleyCount];
                valleyCDF = new float[valleyCount];
                float cumSum = 0;
                for (int i = 0; i < valleyCount; i++) {
                    valleyX[i] = cells.get(i)[0];
                    valleyY[i] = cells.get(i)[1];
                    cumSum += weights.get(i);
                    valleyCDF[i] = cumSum;
                }
            }
        }

        float trackScale = 5000.0f / dropsPerIter;
        int valleyDrops = (valleyCount > 0) ? (int)(dropsPerIter * riverSpawnFraction) : 0;
        int randomDrops = dropsPerIter - valleyDrops;

        for (int iter = 0; iter < iterations; iter++) {
            Arrays.fill(flatDT, 0);
            Arrays.fill(flatMXT, 0);
            Arrays.fill(flatMYT, 0);

            int activeDrops = 0;

            // 随机放置粒子
            for (int i = 0; i < randomDrops; i++) {
                float px = rng.nextFloat() * (size - 1);
                float py = rng.nextFloat() * (size - 1);
                int ix = (int)px, iy = (int)py;
                if (flatH[iy * size + ix] < seaNorm) { randomDrops++; continue; }
                activeDrops++;
                descendPoint(flatH, flatD, flatDT, flatMX, flatMY, flatMXT, flatMYT,
                    size, px, py, seaNorm, 0.0f, rng);
            }

            // 谷地放置粒子（CDF 采样）
            for (int i = 0; i < valleyDrops; i++) {
                float r = rng.nextFloat() * valleyCDF[valleyCount - 1];
                int lo = 0, hi = valleyCount - 1;
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (valleyCDF[mid] < r) lo = mid + 1; else hi = mid;
                }
                int sx = valleyX[lo], sy = valleyY[lo];
                float px = sx + rng.nextFloat(), py = sy + rng.nextFloat();
                if (flatH[sy * size + sx] < seaNorm) continue;
                activeDrops++;
                descendPoint(flatH, flatD, flatDT, flatMX, flatMY, flatMXT, flatMYT,
                    size, px, py, seaNorm, 0.0f, rng);
            }

            // 指数平滑更新 discharge 和 momentum
            for (int i = 0; i < totalSize; i++) {
                flatD[i] = (1f - LRATE) * flatD[i] + LRATE * (flatDT[i] * trackScale);
                flatMX[i] = (1f - LRATE) * flatMX[i] + LRATE * flatMXT[i] * trackScale;
                flatMY[i] = (1f - LRATE) * flatMY[i] + LRATE * flatMYT[i] * trackScale;
            }
        }

        // 写回高度和 discharge
        float[][] dischargeMap = new float[size][size];
        for (int y = 0; y < size; y++) {
            if (erosionEnabled) {
                System.arraycopy(flatH, y * size, heights[y], 0, size);
            }
            for (int x = 0; x < size; x++) {
                dischargeMap[y][x] = flatD[y * size + x];
            }
        }

        return dischargeMap;
    }

    /**
     * V30 descendPoint 核心算法。
     * 粒子从1格宽出发，沿梯度下流，discharge 累积 → 河流自然从窄到宽。
     *
     * @param radius 笔刷半径（0 = 点模式）
     */
    private void descendPoint(float[] flatH, float[] flatD, float[] flatDT,
                               float[] flatMX, float[] flatMY, float[] flatMXT, float[] flatMYT,
                               int size, float px, float py, float seaNorm, float radius, Random rng) {
        float vx = 0, vy = 0;
        float vol = 1.0f, sediment = 0, age = 0;

        while (age < MAX_AGE && vol >= MIN_VOL) {
            int ix = (int)px, iy = (int)py;
            if (ix < 0 || ix >= size || iy < 0 || iy >= size) break;
            int idx = iy * size + ix;

            // 终止条件
            if (age > MAX_AGE || vol < MIN_VOL) {
                if (erosionEnabled) {
                    if (radius > 0.5f) applyHydroBrush(flatH, size, ix, iy, radius, -sediment);
                    else flatH[idx] += sediment;
                }
                return;
            }

            // 到达海洋：delta 步延续（防止河口断流）
            if (flatH[idx] < seaNorm) {
                for (int step = 0; step < DELTA_STEPS; step++) {
                    if (vol < MIN_VOL) break;
                    vol *= 0.6f; sediment *= 0.6f;
                    float vlen = (float)Math.sqrt(vx*vx + vy*vy);
                    if (vlen > 0) { vx = (vx/vlen)*1.5f; vy = (vy/vlen)*1.5f; }
                    px += vx; py += vy;
                    int nx = (int)px, ny = (int)py;
                    if (nx < 0 || nx >= size || ny < 0 || ny >= size) break;
                    flatDT[ny * size + nx] += vol;
                }
                return;
            }

            // 计算法线方向
            float gx = flatH[iy * size + Math.min(size-1, ix + 1)] - flatH[iy * size + Math.max(0, ix - 1)];
            float gy = flatH[Math.min(size-1, iy + 1) * size + ix] - flatH[Math.max(0, iy - 1) * size + ix];
            float len = (float)Math.sqrt(gx*gx + gy*gy + 0.0001f);
            float nx = -gx / len, ny = -gy / len;

            // 重力 + 动量
            vx += GRAVITY * nx / vol;
            vy += GRAVITY * ny / vol;

            // momentum map 反馈：沿已有河道方向偏转
            float mdx = flatMX[idx], mdy = flatMY[idx];
            float mlen = (float)Math.sqrt(mdx*mdx + mdy*mdy);
            float vlen = (float)Math.sqrt(vx*vx + vy*vy);
            if (mlen > 0 && vlen > 0) {
                float dot = (vx*mdx + vy*mdy) / (vlen * mlen);
                float factor = MOMENTUM * dot / (vol + flatD[idx]);
                vx += factor * mdx;
                vy += factor * mdy;
            }

            // 归一化步长为 sqrt(2)
            vlen = (float)Math.sqrt(vx*vx + vy*vy);
            if (vlen > 0) {
                vx = (vx / vlen) * 1.414f;
                vy = (vy / vlen) * 1.414f;
            }

            px += vx; py += vy;
            if (px < 0 || px >= size || py < 0 || py >= size) return;

            // 记录 discharge 和 momentum
            flatDT[idx] += vol;
            flatMXT[idx] += vol * vx;
            flatMYT[idx] += vol * vy;

            // 双线性插值计算新高度
            int nix = (int)px, niy = (int)py;
            float h2;
            if (nix >= size - 1 || niy >= size - 1) {
                h2 = flatH[idx] - 0.002f;
            } else {
                float dx = px - nix, dy = py - niy;
                int newIdx = niy * size + nix;
                h2 = flatH[newIdx]*(1-dx)*(1-dy) + flatH[newIdx+1]*dx*(1-dy)
                   + flatH[newIdx+size]*(1-dx)*dy + flatH[newIdx+size+1]*dx*dy;
            }

            // 侵蚀/沉积计算
            float slope = Math.max(0, flatH[idx] - h2);
            float nodeDischarge = fastErf(0.4f * flatD[idx]);
            float c_eq = (1.0f + entrainment * nodeDischarge) * slope;
            float cdiff = c_eq - sediment;
            float delta = depositionRate * cdiff;

            // 高度衰减
            if (slopeAttenuationHeight > 0 && delta > 0) {
                float fo = Math.min(1.0f, flatH[idx] / slopeAttenuationHeight);
                delta *= fo;
            }

            sediment += delta;

            // 点侵蚀（仅当 erosionEnabled 时修改地形）
            if (erosionEnabled) {
                flatH[idx] -= delta;
            }

            // 蒸发
            float effectiveEvap = evap;
            sediment /= (1.0f - effectiveEvap);
            vol *= (1.0f - effectiveEvap);
            age++;

            // cascade 热侵蚀（仅当 erosionEnabled 时执行）
            if (erosionEnabled && (cascadeInterval <= 1 || (int)age % cascadeInterval == 0)) {
                cascade(flatH, size, ix, iy, seaNorm);
            }
        }
    }

    /**
     * V30 cascade 热侵蚀：排序邻居+距离加权+海陆区分。
     * 比 mod 版的简单3×1平滑更精确。
     */
    private void cascade(float[] flatH, int size, int ix, int iy, float seaNorm) {
        if (ix < 1 || ix >= size - 1 || iy < 1 || iy >= size - 1) return;

        // 收集邻居并按高度排序
        int[] nIdx = new int[8];
        float[] nH = new float[8];
        float[] nDist = new float[8];
        int count = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = ix + dx, ny = iy + dy;
                nIdx[count] = ny * size + nx;
                nH[count] = flatH[ny * size + nx];
                nDist[count] = (dx != 0 && dy != 0) ? 1.4142135f : 1.0f;
                count++;
            }
        }

        // 简单排序（8个元素，插入排序即可）
        for (int i = 1; i < count; i++) {
            for (int j = i; j > 0 && nH[j] < nH[j-1]; j--) {
                float th = nH[j]; nH[j] = nH[j-1]; nH[j-1] = th;
                int ti = nIdx[j]; nIdx[j] = nIdx[j-1]; nIdx[j-1] = ti;
                float td = nDist[j]; nDist[j] = nDist[j-1]; nDist[j-1] = td;
            }
        }

        int idx = iy * size + ix;
        float currentH = flatH[idx];

        for (int i = 0; i < count; i++) {
            float diff = currentH - nH[i];
            if (diff == 0) continue;

            float excess;
            if (nH[i] > 0.1f) {
                excess = Math.abs(diff) - nDist[i] * MAXDIFF;
            } else {
                excess = Math.abs(diff);
            }

            if (excess <= 0) continue;

            float transfer = SETTLING * excess / 2.0f;

            if (diff > 0) {
                flatH[idx] -= transfer;
                flatH[nIdx[i]] += transfer;
            } else {
                flatH[idx] += transfer;
                flatH[nIdx[i]] -= transfer;
            }
        }
    }

    /** 水文笔刷侵蚀（用于 brush 模式） */
    private void applyHydroBrush(float[] flatH, int size, int cx, int cy, float radius, float amount) {
        if (amount == 0 || radius <= 0) return;
        int r = (int)Math.ceil(radius);
        float weightSum = 0;

        // 先计算权重总和
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                float dist2 = dx*dx + dy*dy;
                if (dist2 > radius*radius) continue;
                int nx = cx + dx, ny = cy + dy;
                if (nx < 0 || nx >= size || ny < 0 || ny >= size) continue;
                float t = (float)Math.sqrt(dist2) / radius;
                weightSum += 1.0f - t * t * (3 - 2 * t);
            }
        }

        if (weightSum <= 0) return;

        // 应用笔刷
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                float dist2 = dx*dx + dy*dy;
                if (dist2 > radius*radius) continue;
                int nx = cx + dx, ny = cy + dy;
                if (nx < 0 || nx >= size || ny < 0 || ny >= size) continue;
                float t = (float)Math.sqrt(dist2) / radius;
                float wgt = (1.0f - t * t * (3 - 2 * t)) / weightSum;
                flatH[ny * size + nx] -= amount * wgt;
            }
        }
    }

    /** erf 近似（来自 V30） */
    private static float fastErf(float x) {
        float a = Math.abs(x);
        float t = 1.0f / (1.0f + 0.47047f * a);
        float result = 1.0f - t*(0.3480242f + t*(-0.0958798f + t*0.7478556f))*(float)Math.exp(-a*a);
        return x >= 0 ? result : -result;
    }
}
