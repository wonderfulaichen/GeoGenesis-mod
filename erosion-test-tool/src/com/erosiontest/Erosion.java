package com.erosiontest;

public class Erosion {
    private static final float INERTIA = 0.005f;
    private static final float CAPACITY_FACTOR = 7f;
    private static final float MIN_CAPACITY = 0.008f;
    private static final float EVAPORATE = 0.35f;
    private static final float GRAVITY = 2.5f;
    private static final int RADIUS = 7;

    private int[][] brushIdx;
    private float[][] brushW;
    private int brushSize = 0;

    public Erosion(Noise noise, int seed) {}

    private void ensureBrush(int size, int radius) {
        if (brushSize == size) return;
        brushSize = size;
        brushIdx = new int[size * size][];
        brushW = new float[size * size][];
        for (int cy = 0; cy < size; cy++) {
            for (int cx = 0; cx < size; cx++) {
                int idx = cy * size + cx;
                int[] xo = new int[radius * radius * 4];
                int[] yo = new int[radius * radius * 4];
                float[] w = new float[radius * radius * 4];
                float ws = 0; int n = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        float d2 = dx * dx + dy * dy;
                        if (d2 < radius * radius) {
                            int nx = cx + dx, ny = cy + dy;
                            if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                                float wt = 1f - (float)Math.sqrt(d2) / radius;
                                ws += wt; xo[n] = dx; yo[n] = dy; w[n] = wt; n++;
                            }
                        }
                    }
                }
                brushIdx[idx] = new int[n];
                brushW[idx] = new float[n];
                for (int i = 0; i < n; i++) {
                    brushIdx[idx][i] = (cy + yo[i]) * size + (cx + xo[i]);
                    brushW[idx][i] = w[i] / ws;
                }
            }
        }
    }

    public void applyErosion(float[][] heights, int size, float strength) {
        int drops = Math.round(80000 * strength);
        int maxLife = 30;
        ensureBrush(size, RADIUS);

        for (int i = 0; i < drops; i++) {
            long h = hash(i * 31, i * 73);
            int sx = (int)((h & 0xFFFFFFFFL) % (size - 6)) + 3;
            int sz = (int)(((h >>> 32) & 0xFFFFFFFFL) % (size - 6)) + 3;
            if (heights[sz][sx] <= 0.01f) continue;
            drop(heights, size, sx, sz, maxLife);
        }
    }

    private void drop(float[][] map, int size, int sx, int sz, int maxLife) {
        float px = sx + 0.5f, pz = sz + 0.5f;
        float dx = 0, dz = 0, sed = 0, spd = 1f, wat = 1f;

        for (int step = 0; step < maxLife; step++) {
            int ix = (int)px, iz = (int)pz;
            if (ix < 1 || ix >= size - 2 || iz < 1 || iz >= size - 2) return;
            int idx = iz * size + ix;
            float fx = px - ix, fz = pz - iz;

            float h0 = map[iz][ix] * (1-fx)*(1-fz) + map[iz][ix+1] * fx*(1-fz)
                     + map[iz+1][ix] * (1-fx)*fz + map[iz+1][ix+1] * fx*fz;
            if (h0 <= 0.01f) return;

            float gx = (map[iz][ix+1] - map[iz][ix])*(1-fz) + (map[iz+1][ix+1] - map[iz+1][ix])*fz;
            float gz = (map[iz+1][ix] - map[iz][ix])*(1-fx) + (map[iz+1][ix+1] - map[iz][ix+1])*fx;

            dx = dx * INERTIA - gx * (1 - INERTIA);
            dz = dz * INERTIA - gz * (1 - INERTIA);
            float glen = (float)Math.sqrt(dx*dx + dz*dz);
            if (glen < 1e-12f) return;
            dx /= glen; dz /= glen;

            px += dx; pz += dz;
            if (px < 1 || px >= size - 2 || pz < 1 || pz >= size - 2) return;

            int nix = (int)px, niz = (int)pz;
            float fnx = px - nix, fnz = pz - niz;

            float h1 = map[niz][nix] * (1-fnx)*(1-fnz) + map[niz][nix+1] * fnx*(1-fnz)
                     + map[niz+1][nix] * (1-fnx)*fnz + map[niz+1][nix+1] * fnx*fnz;

            float dh = h1 - h0;
            float fo = h1 > 0.01f ? Math.min(1f, h1 / 0.4f) : 0;
            dh *= fo;

            float cap = Math.max(-dh * spd * wat * CAPACITY_FACTOR, MIN_CAPACITY);

            // Deposit at OLD position (behind), erode at OLD position (around)
            if (sed > cap || dh > 0) {
                float dep = dh > 0 ? Math.min(dh, sed) : (sed - cap) * 0.3f;
                sed -= dep;
                map[iz][ix] += dep * (1-fx)*(1-fz);
                map[iz][ix+1] += dep * fx*(1-fz);
                map[iz+1][ix] += dep * (1-fx)*fz;
                map[iz+1][ix+1] += dep * fx*fz;
            } else {
                float eroAmt = Math.min((cap - sed) * 0.3f, -dh);
                int[] brush = brushIdx[idx];
                float[] weights = brushW[idx];
                for (int b = 0; b < brush.length; b++) {
                    int ni = brush[b];
                    float delta = Math.min(map[ni / size][ni % size], eroAmt * weights[b]);
                    map[ni / size][ni % size] -= delta;
                    sed += delta;
                }
            }

            spd = (float)Math.sqrt(spd*spd + dh * GRAVITY);
            if (spd <= 0) return;
            wat *= (1 - EVAPORATE);
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
