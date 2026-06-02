package com.geogenesis.worldgen.hydrology;

import java.util.Arrays;
import java.util.Random;

public class SimpleHydrologyEngine {

    private static final float LRATE = 0.1f;
    private static final float GRAVITY = 1.5f;
    private static final float MOMENTUM = 0.15f;
    private static final float EVAP = 0.001f;
    private static final float DEPOSITION = 0.1f;
    private static final float ENTRAINMENT = 6.0f;
    private static final float SLOPE_ATTENUATION_HEIGHT = 0.3f;
    private static final float MIN_VOL = 0.01f;
    private static final float MAX_AGE = 128;
    private static final float MAXDIFF = 0.01f;
    private static final float SETTLING = 0.6f;
    private static final int CASCADE_INTERVAL = 3;
    private static final int UPDATE_INTERVAL = 5;
    private static final float VALLEY_SPAWN_FRACTION = 0.15f;

    private final long seed;
    private boolean usePointMode = false;

    public SimpleHydrologyEngine(long seed) {
        this.seed = seed;
    }

    public void setPointMode(boolean pointMode) {
        this.usePointMode = pointMode;
    }

    private float[] volumeNormal(float[] flatH, float[] flatD, int size, float fx, float fy, float radius) {
        int ix = (int)fx, iy = (int)fy;
        int r = Math.max(1, (int)radius);

        float gx = flatH[iy * size + Math.min(size-1, ix + r)] - flatH[iy * size + Math.max(0, ix - r)];
        float gy = flatH[Math.min(size-1, iy + r) * size + ix] - flatH[Math.max(0, iy - r) * size + ix];

        float dRight = ix + r < size ? fastErf(0.4f * flatD[iy * size + ix + r]) : 0;
        float dLeft  = ix - r >= 0 ? fastErf(0.4f * flatD[iy * size + ix - r]) : 0;
        float dDown  = iy + r < size ? fastErf(0.4f * flatD[Math.min(size-1, iy + r) * size + ix]) : 0;
        float dUp    = iy - r >= 0 ? fastErf(0.4f * flatD[Math.max(0, iy - r) * size + ix]) : 0;

        float localSlope = (float)Math.sqrt(gx*gx + gy*gy + 0.0001f);
        float attractionFactor = 0.005f * (1.0f + 5.0f * Math.max(0, 1.0f - localSlope / 0.02f));

        gx -= (dRight - dLeft) * attractionFactor;
        gy -= (dDown - dUp) * attractionFactor;

        float len = (float)Math.sqrt(gx*gx + gy*gy + 0.0001f);
        return new float[]{-gx/len, -gy/len};
    }

    private float sampleVolumeHeight(float[] flatH, int size, float fx, float fy, float radius) {
        int cx = (int)fx, cy = (int)fy;
        int r = (int)Math.ceil(radius);
        if (r <= 1) return flatH[cy * size + cx];

        float sum = 0, weightSum = 0;
        float r2 = radius * radius;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                float d2 = dx*dx + dy*dy;
                if (d2 > r2) continue;
                int nx = cx + dx, ny = cy + dy;
                if (nx < 0 || nx >= size || ny < 0 || ny >= size) continue;

                float t = (float)Math.sqrt(d2) / radius;
                float wgt = 1.0f - t * t * (3 - 2 * t);
                sum += flatH[ny * size + nx] * wgt;
                weightSum += wgt;
            }
        }
        return weightSum > 0 ? sum / weightSum : flatH[cy * size + cx];
    }

    private void applyTerrainBrush(float[] flatH, int size, int cx, int cy, float radius, float amount) {
        int r = (int)Math.ceil(radius);
        if (r <= 1) {
            flatH[cy * size + cx] -= amount;
            return;
        }

        float r2 = radius * radius;
        float weightSum = 0;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                float d2 = dx*dx + dy*dy;
                if (d2 > r2) continue;
                int nx = cx + dx, ny = cy + dy;
                if (nx < 0 || nx >= size || ny < 0 || ny >= size) continue;
                float t = (float)Math.sqrt(d2) / radius;
                weightSum += (1.0f - t * t * (3 - 2 * t));
            }
        }

        if (weightSum <= 0) return;

        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                float d2 = dx*dx + dy*dy;
                if (d2 > r2) continue;
                int nx = cx + dx, ny = cy + dy;
                if (nx < 0 || nx >= size || ny < 0 || ny >= size) continue;

                float t = (float)Math.sqrt(d2) / radius;
                float wgt = (1.0f - t * t * (3 - 2 * t)) / weightSum;

                int nidx = ny * size + nx;
                flatH[nidx] -= amount * wgt;
            }
        }
    }

    public float[][] applyHydrology(float[][] heights, int size, int iterations, int dropsPerIter, float seaNorm, int ox, int oz) {
        int totalSize = size * size;
        float[] flatH = new float[totalSize];
        float[] flatD = new float[totalSize];
        float[] flatDT = new float[totalSize];
        float[] flatMX = new float[totalSize];
        float[] flatMY = new float[totalSize];
        float[] flatMXT = new float[totalSize];
        float[] flatMYT = new float[totalSize];

        for (int y = 0; y < size; y++) {
            System.arraycopy(heights[y], 0, flatH, y * size, size);
        }

        Random rng = new Random(seed ^ (ox * 31L) ^ (oz * 73L));

        if (usePointMode) {
            simulatePointMode(flatH, flatD, flatDT, flatMX, flatMY, flatMXT, flatMYT, size, seaNorm, rng);
        } else {
            simulateBrushMode(flatH, flatD, flatDT, flatMX, flatMY, flatMXT, flatMYT, size, iterations, dropsPerIter, seaNorm, rng);
        }

        float[][] resultD = new float[size][size];
        for (int y = 0; y < size; y++) {
            System.arraycopy(flatD, y * size, resultD[y], 0, size);
            System.arraycopy(flatH, y * size, heights[y], 0, size);
        }
        return resultD;
    }

    private void simulatePointMode(float[] flatH, float[] flatD, float[] flatDT, float[] flatMX, float[] flatMY, float[] flatMXT, float[] flatMYT, int size, float seaNorm, Random rng) {
        int iterations = 60;
        int dropsPerIter = 1500;
        float trackScale = 5000.0f / dropsPerIter;
        int totalSize = size * size;

        for (int iter = 0; iter < iterations; iter++) {
            Arrays.fill(flatDT, 0);
            Arrays.fill(flatMXT, 0);
            Arrays.fill(flatMYT, 0);

            int activeDrops = 0;
            while (activeDrops < dropsPerIter) {
                float px, py;
                int ix, iy;

                if (rng.nextFloat() < VALLEY_SPAWN_FRACTION) {
                    float bestD = -1;
                    int bestIdx = 0;
                    for (int s = 0; s < 8; s++) {
                        int sx = rng.nextInt(size - 2) + 1;
                        int sy = rng.nextInt(size - 2) + 1;
                        float d = flatD[sy * size + sx];
                        if (d > bestD) { bestD = d; bestIdx = sy * size + sx; }
                    }
                    iy = bestIdx / size;
                    ix = bestIdx % size;
                    px = ix + rng.nextFloat();
                    py = iy + rng.nextFloat();
                } else {
                    px = rng.nextFloat() * (size - 1);
                    py = rng.nextFloat() * (size - 1);
                    ix = (int)px;
                    iy = (int)py;
                }

                if (flatH[iy * size + ix] < seaNorm) continue;

                activeDrops++;
                simulateDropPoint(flatH, flatD, flatDT, flatMX, flatMY, flatMXT, flatMYT, size, px, py, seaNorm, (iter % CASCADE_INTERVAL == 0));
            }

            if (UPDATE_INTERVAL <= 1 || iter % UPDATE_INTERVAL == 0) {
                for (int i = 0; i < totalSize; i++) {
                    flatD[i] = (1f - LRATE) * flatD[i] + LRATE * (flatDT[i] * trackScale);
                    flatMX[i] = (1f - LRATE) * flatMX[i] + LRATE * (flatMXT[i] * trackScale);
                    flatMY[i] = (1f - LRATE) * flatMY[i] + LRATE * (flatMYT[i] * trackScale);
                }
            }
        }
    }

    private void simulateBrushMode(float[] flatH, float[] flatD, float[] flatDT, float[] flatMX, float[] flatMY, float[] flatMXT, float[] flatMYT, int size, int iterations, int dropsPerIter, float seaNorm, Random rng) {
        int totalSize = size * size;

        int[] iterLayers = {iterations/2, iterations/2};
        int[] dropsLayers = {dropsPerIter, dropsPerIter/2};
        float[] baseRadiusLayers = {1.5f, 0.0f};
        float[] maxRadiusLayers = {8.0f, 1.5f};
        float[] erosionMultipliers = {4.0f, 2.0f};

        for (int li = 0; li < iterLayers.length; li++) {
            float baseRadius = baseRadiusLayers[li];
            float maxRadius = maxRadiusLayers[li];
            float eroMul = erosionMultipliers[li];
            float trackScale = 5000.0f / dropsLayers[li];

            for (int iter = 0; iter < iterLayers[li]; iter++) {
                Arrays.fill(flatDT, 0);
                Arrays.fill(flatMXT, 0);
                Arrays.fill(flatMYT, 0);

                int activeDrops = 0;
                while (activeDrops < dropsLayers[li]) {
                    float px = rng.nextFloat() * (size - 1);
                    float py = rng.nextFloat() * (size - 1);

                    int ix = (int)px, iy = (int)py;
                    float hVal = flatH[iy * size + ix];
                    if (hVal < seaNorm) continue;

                    float gx = flatH[iy * size + Math.min(size-1, ix+1)] - flatH[iy * size + Math.max(0, ix-1)];
                    float gy = flatH[Math.min(size-1, iy+1) * size + ix] - flatH[Math.max(0, iy-1) * size + ix];
                    float localSlope = (float)Math.sqrt(gx*gx + gy*gy);

                    float spawnChance = 0.1f + 0.9f * Math.min(1.0f, localSlope / 0.02f);
                    if (rng.nextFloat() > spawnChance) continue;

                    activeDrops++;
                    simulateDropBrush(flatH, flatD, flatDT, flatMX, flatMY, flatMXT, flatMYT, size, px, py, seaNorm, baseRadius, maxRadius, eroMul, (iter % CASCADE_INTERVAL == 0));
                }

                if (UPDATE_INTERVAL <= 1 || iter % UPDATE_INTERVAL == 0) {
                    for (int i = 0; i < totalSize; i++) {
                        flatD[i] = (1f - LRATE) * flatD[i] + LRATE * (flatDT[i] * trackScale);
                        flatMX[i] = (1f - LRATE) * flatMX[i] + LRATE * (flatMXT[i] * trackScale);
                        flatMY[i] = (1f - LRATE) * flatMY[i] + LRATE * (flatMYT[i] * trackScale);
                    }
                }
            }
        }
    }

    private void simulateDropPoint(float[] flatH, float[] flatD, float[] flatDT, float[] flatMX, float[] flatMY, float[] flatMXT, float[] flatMYT,
                                   int size, float px, float py, float seaNorm, boolean doCascade) {
        float vx = 0, vy = 0;
        float vol = 1.0f, sediment = 0, age = 0;

        while (age < MAX_AGE && vol >= MIN_VOL) {
            int ix = (int)px, iy = (int)py;
            if (ix < 1 || ix >= size - 1 || iy < 1 || iy >= size - 1) break;
            int idx = iy * size + ix;

            float h0 = flatH[idx];
            if (h0 < seaNorm) {
                flatH[idx] += sediment;
                flatDT[idx] += vol;
                flatMXT[idx] += vol * vx;
                flatMYT[idx] += vol * vy;
                break;
            }

            float gx = flatH[iy * size + ix + 1] - flatH[iy * size + ix - 1];
            float gy = flatH[(iy + 1) * size + ix] - flatH[(iy - 1) * size + ix];

            float dRight = fastErf(0.4f * flatD[iy * size + ix + 1]);
            float dLeft  = fastErf(0.4f * flatD[iy * size + ix - 1]);
            float dDown  = fastErf(0.4f * flatD[(iy + 1) * size + ix]);
            float dUp    = fastErf(0.4f * flatD[(iy - 1) * size + ix]);

            float localSlope = (float)Math.sqrt(gx*gx + gy*gy + 0.0001f);
            float attractionFactor = 0.005f * (1.0f + 5.0f * Math.max(0, 1.0f - localSlope / 0.02f));
            gx -= (dRight - dLeft) * attractionFactor;
            gy -= (dDown - dUp) * attractionFactor;

            float len = (float)Math.sqrt(gx*gx + gy*gy + 0.0001f);
            float nx = -gx / len, ny = -gy / len;

            vx += GRAVITY * nx / vol;
            vy += GRAVITY * ny / vol;

            float localDischarge = flatD[idx];
            float mdx = flatMX[idx], mdy = flatMY[idx];
            float mlen = (float)Math.sqrt(mdx*mdx + mdy*mdy + 1e-6f);
            float vlen = (float)Math.sqrt(vx*vx + vy*vy + 1e-6f);
            if (mlen > 0) {
                float dot = (vx * mdx + vy * mdy) / (vlen * mlen);
                if (dot > 0) {
                    float factor = MOMENTUM * dot / (vol + localDischarge + 1f);
                    vx += factor * mdx;
                    vy += factor * mdy;
                }
            }

            vlen = (float)Math.sqrt(vx*vx + vy*vy + 1e-6f);
            if (vlen > 0) {
                float step = 1.414f;
                vx = (vx / vlen) * step;
                vy = (vy / vlen) * step;
            }

            px += vx; py += vy;
            int nix = (int)px, niy = (int)py;
            if (nix < 0 || nix >= size || niy < 0 || niy >= size) break;

            vlen = (float)Math.sqrt(vx*vx + vy*vy + 1e-6f);
            int nidx = niy * size + nix;
            flatDT[nidx] += vol * vlen;
            flatMXT[nidx] += vol * vx;
            flatMYT[nidx] += vol * vy;

            float h2 = flatH[nidx];
            float h1 = flatH[idx];
            float slope = Math.max(0, h1 - h2);
            float slopeAttenuation = Math.min(1.0f, h0 / SLOPE_ATTENUATION_HEIGHT);
            float nodeDischarge = fastErf(0.4f * localDischarge);
            float c_eq = (1.0f + ENTRAINMENT * nodeDischarge * slopeAttenuation) * slope;
            float cdiff = c_eq - sediment;
            float currentDeposition = DEPOSITION;

            if (cdiff < 0) {
                if (slope < 0.015f) {
                    float flatFactor = 1.0f - slope / 0.015f;
                    float lowFlowFactor = Math.max(0, 1.0f - nodeDischarge / 0.5f);
                    currentDeposition *= (1.0f - 0.95f * (flatFactor * lowFlowFactor));
                }
            }

            float delta = currentDeposition * cdiff;
            sediment += delta;
            flatH[idx] -= delta;

            sediment /= (1.0f - EVAP);
            vol *= (1.0f - EVAP);
            age++;

            if (doCascade && ix > 0 && ix < size - 1 && iy > 0 && iy < size - 1) {
                float totalOut = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nidx2 = (iy + dy) * size + (ix + dx);
                        float diff = flatH[idx] - flatH[nidx2];
                        if (diff > MAXDIFF) {
                            float transfer = SETTLING * diff;
                            flatH[nidx2] += transfer;
                            totalOut += transfer;
                        }
                    }
                }
                flatH[idx] -= totalOut;
            }
        }
    }

    private void simulateDropBrush(float[] flatH, float[] flatD, float[] flatDT, float[] flatMX, float[] flatMY, float[] flatMXT, float[] flatMYT,
                                   int size, float px, float py, float seaNorm, float baseRadius, float maxRadius, float eroMul, boolean doCascade) {
        float vx = 0, vy = 0;
        float vol = 1.0f, sediment = 0, age = 0;

        while (age < MAX_AGE && vol >= MIN_VOL) {
            int ix = (int)px, iy = (int)py;
            if (ix < 1 || ix >= size - 1 || iy < 1 || iy >= size - 1) break;
            int idx = iy * size + ix;

            float localDischarge = flatD[idx];
            float radius = baseRadius + (float)Math.sqrt(localDischarge + vol) * 0.6f;
            radius = Math.min(radius, maxRadius);

            float h0 = flatH[idx];
            if (h0 < seaNorm) {
                applyTerrainBrush(flatH, size, ix, iy, radius, -sediment);
                flatDT[idx] += vol;
                flatMXT[idx] += vol * vx;
                flatMYT[idx] += vol * vy;
                break;
            }

            float[] n = volumeNormal(flatH, flatD, size, px, py, radius);
            vx += GRAVITY * n[0] / vol;
            vy += GRAVITY * n[1] / vol;

            float mdx = flatMX[idx], mdy = flatMY[idx];
            float mlen = (float)Math.sqrt(mdx*mdx + mdy*mdy + 1e-6f);
            float vlen = (float)Math.sqrt(vx*vx + vy*vy + 1e-6f);
            if (mlen > 0) {
                float dot = (vx * mdx + vy * mdy) / (vlen * mlen);
                if (dot > 0) {
                    float factor = MOMENTUM * dot / (vol + localDischarge + 1f);
                    vx += factor * mdx;
                    vy += factor * mdy;
                }
            }

            vlen = (float)Math.sqrt(vx*vx + vy*vy + 1e-6f);
            if (vlen > 0) {
                float step = 1.414f;
                vx = (vx / vlen) * step;
                vy = (vy / vlen) * step;
            }

            px += vx; py += vy;
            int nix = (int)px, niy = (int)py;
            if (nix < 0 || nix >= size || niy < 0 || niy >= size) break;

            vlen = (float)Math.sqrt(vx*vx + vy*vy + 1e-6f);
            int nidx_center = niy * size + nix;
            flatDT[nidx_center] += vol * vlen;
            flatMXT[nidx_center] += vol * vx;
            flatMYT[nidx_center] += vol * vy;

            float h2 = sampleVolumeHeight(flatH, size, px, py, radius);
            float h1 = sampleVolumeHeight(flatH, size, ix, iy, radius);

            float slope = Math.max(0, h1 - h2);
            float slopeAttenuation = Math.min(1.0f, flatH[idx] / SLOPE_ATTENUATION_HEIGHT);
            float nodeDischarge = fastErf(0.4f * localDischarge);
            float c_eq = (1.0f + ENTRAINMENT * nodeDischarge * slopeAttenuation) * slope;
            float cdiff = c_eq - sediment;
            float currentDeposition = DEPOSITION;

            if (cdiff < 0) {
                if (slope < 0.015f) {
                    float flatFactor = 1.0f - slope / 0.015f;
                    float lowFlowFactor = Math.max(0, 1.0f - nodeDischarge / 0.5f);
                    currentDeposition *= (1.0f - 0.95f * (flatFactor * lowFlowFactor));
                }
            }

            float delta = currentDeposition * cdiff * eroMul;
            sediment += delta;

            applyTerrainBrush(flatH, size, ix, iy, radius, delta);

            sediment /= (1.0f - EVAP);
            vol *= (1.0f - EVAP);
            age++;

            if (doCascade && ix > 0 && ix < size - 1 && iy > 0 && iy < size - 1) {
                float totalOut = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nidx = (iy + dy) * size + (ix + dx);
                        float diff = flatH[idx] - flatH[nidx];
                        if (diff > MAXDIFF) {
                            float transfer = SETTLING * diff;
                            flatH[nidx] += transfer;
                            totalOut += transfer;
                        }
                    }
                }
                flatH[idx] -= totalOut;
            }
        }
    }

    private float fastErf(float x) {
        float a = Math.abs(x);
        float t = 1.0f / (1.0f + 0.47047f * a);
        float result = 1.0f - t*(0.3480242f + t*(-0.0958798f + t*0.7478556f))*(float)Math.exp(-a*a);
        return x >= 0 ? result : -result;
    }
}
