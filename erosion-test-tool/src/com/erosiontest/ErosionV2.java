package com.erosiontest;

/**
 * 粒子侵蚀 v2：随机落点、大小笔刷、多层侵蚀
 * 基于旧版 Erosion.java 的粒子机制，新增多层不同尺寸笔刷
 */
public class ErosionV2 {

    /**
     * 多层粒子侵蚀
     * @param heights 高度图 [size][size]，[0,1] 范围，原地修改
     * @param size    尺寸
     * @param layers  侵蚀层配置：每层 {滴数密度倍数, 笔刷半径, 蚀刻速度, 沉积速度}
     *                例：new float[][]{{1.0f, 7, 0.3f, 0.3f}, {0.5f, 3, 0.3f, 0.3f}, {0.25f, 1, 0.3f, 0.3f}}
     * @param baseDrops 基础滴数（乘以密度倍数）
     */
    public void applyMultiLayer(float[][] heights, int size, float[][] layers, int baseDrops) {
        for (int l = 0; l < layers.length; l++) {
            float densityMul = layers[l][0];
            int radius = (int)layers[l][1];
            float erodeSpeed = layers[l][2];
            float depositSpeed = layers[l][3];
            int drops = (int)(baseDrops * densityMul);

            applyErosion(heights, size, drops, radius, erodeSpeed, depositSpeed, l);
        }
    }

    private void applyErosion(float[][] heights, int size, int drops, int radius,
                               float erodeSpeed, float depositSpeed, int layer) {
        int maxLife = 30;
        float INERTIA = 0.005f;
        float CAPACITY_FACTOR = 7f;
        float MIN_CAPACITY = 0.008f;
        float EVAPORATE = 0.35f;
        float GRAVITY = 2.5f;

        // 预计算笔刷
        int[] brushIdx;
        float[] brushW;
        {
            int[] xo = new int[radius * radius * 4];
            int[] yo = new int[radius * radius * 4];
            float[] w = new float[radius * radius * 4];
            float ws = 0; int n = 0;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    float d2 = dx * dx + dy * dy;
                    if (d2 < radius * radius) {
                        float wt = 1f - (float)Math.sqrt(d2) / radius;
                        ws += wt; xo[n] = dx; yo[n] = dy; w[n] = wt; n++;
                    }
                }
            }
            brushIdx = new int[n];
            brushW = new float[n];
            for (int i = 0; i < n; i++) {
                brushIdx[i] = yo[i] * size + xo[i];
                brushW[i] = w[i] / ws;
            }
        }

        int layerOffset = layer * 100000;

        for (int i = 0; i < drops; i++) {
            long h = hash(i * 31 + layerOffset, i * 73 + layerOffset * 7);
            int sx = (int)((h & 0xFFFFFFFFL) % (size - radius * 2 - 2)) + radius + 1;
            int sz = (int)(((h >>> 32) & 0xFFFFFFFFL) % (size - radius * 2 - 2)) + radius + 1;

            if (heights[sz][sx] <= 0.01f) continue;

            // 滴落
            float px = sx + 0.5f, pz = sz + 0.5f;
            float dx = 0, dz = 0, sed = 0, spd = 1f, wat = 1f;

            for (int step = 0; step < maxLife; step++) {
                int ix = (int)px, iz = (int)pz;
                if (ix < radius || ix >= size - radius - 1 || iz < radius || iz >= size - radius - 1) break;
                int idx = iz * size + ix;
                float fx = px - ix, fz = pz - iz;

                float h0 = heights[iz][ix] * (1-fx)*(1-fz) + heights[iz][ix+1] * fx*(1-fz)
                         + heights[iz+1][ix] * (1-fx)*fz + heights[iz+1][ix+1] * fx*fz;
                if (h0 <= 0.01f) break;

                float gx = (heights[iz][ix+1] - heights[iz][ix])*(1-fz) + (heights[iz+1][ix+1] - heights[iz+1][ix])*fz;
                float gz = (heights[iz+1][ix] - heights[iz][ix])*(1-fx) + (heights[iz+1][ix+1] - heights[iz][ix+1])*fx;

                dx = dx * INERTIA - gx * (1 - INERTIA);
                dz = dz * INERTIA - gz * (1 - INERTIA);
                float glen = (float)Math.sqrt(dx*dx + dz*dz);
                if (glen < 1e-12f) break;
                dx /= glen; dz /= glen;

                px += dx; pz += dz;
                if (px < radius || px >= size - radius - 1 || pz < radius || pz >= size - radius - 1) break;

                int nix = (int)px, niz = (int)pz;
                float fnx = px - nix, fnz = pz - niz;

                float h1 = heights[niz][nix] * (1-fnx)*(1-fnz) + heights[niz][nix+1] * fnx*(1-fnz)
                         + heights[niz+1][nix] * (1-fnx)*fnz + heights[niz+1][nix+1] * fnx*fnz;

                float dh = h1 - h0;
                float fo = h1 > 0.01f ? Math.min(1f, h1 / 0.4f) : 0;
                dh *= fo;

                float cap = Math.max(-dh * spd * wat * CAPACITY_FACTOR, MIN_CAPACITY);

                if (sed > cap || dh > 0) {
                    float dep = dh > 0 ? Math.min(dh, sed) : (sed - cap) * depositSpeed;
                    sed -= dep;
                    heights[iz][ix] += dep * (1-fx)*(1-fz);
                    heights[iz][ix+1] += dep * fx*(1-fz);
                    heights[iz+1][ix] += dep * (1-fx)*fz;
                    heights[iz+1][ix+1] += dep * fx*fz;
                } else {
                    float eroAmt = Math.min((cap - sed) * erodeSpeed, -dh);
                    for (int b = 0; b < brushIdx.length; b++) {
                        int ni = idx + brushIdx[b];
                        if (ni < 0 || ni >= size * size) continue;
                        int nz = ni / size;
                        int nx = ni % size;
                        if (nz < radius || nz >= size - radius - 1 || nx < radius || nx >= size - radius - 1) continue;
                        float delta = Math.min(heights[nz][nx], eroAmt * brushW[b]);
                        heights[nz][nx] -= delta;
                        sed += delta;
                    }
                }

                spd = (float)Math.sqrt(spd*spd + dh * GRAVITY);
                if (spd <= 0) break;
                wat *= (1 - EVAPORATE);
            }
        }
    }

    private static long hash(int a, int b) {
        long h = a * 0x9e3779b9L + b * 0x9e3779b9L * 31;
        h = (h ^ (h >>> 16)) * 0x85ebca6bL;
        h = h ^ (h >>> 13);
        h = h * 0xc2b2ae35L;
        h = h ^ (h >>> 16);
        return h;
    }
}
