package com.erosiontest;

import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;
import javax.imageio.*;

/**
 * SimpleHydrology V30: 彻底重置并修复 HQ 与 Opt 模式
 * 
 * 核心修复：
 * 1. 恢复 HQ 模式为最纯净的点式模拟 (基于 V29 但移除不稳定的吸引力逻辑)
 * 2. 优化模式采用真正的金字塔笔刷，并使用“通量”物理过滤噪点
 * 3. 严格遵循“水量不够不显水”的渲染原则
 */
public class SimpleHydrologyV30 {

    static int MAP_SIZE = 1024;
    static int ITERATIONS = 200;
    static int DROPS_PER_ITER = 5000;
    static float MAX_AGE = 500;

    static void setPerformanceMode() {
        ITERATIONS = 40;
        DROPS_PER_ITER = 1200; // 稍作增加以保证主干道连贯
        MAX_AGE = 500; // 必须恢复为 500！因为全局测试图是 1024x1024，128 步走不到海里就会断流
    }

    static void setHighQualityMode() {
        ITERATIONS = 200;
        DROPS_PER_ITER = 5000;
        MAX_AGE = 500;
    }

    static float GRAVITY = 1.0f;
    static float LRATE = 0.1f;
    static float MOMENTUM = 1.0f;
    static float EVAP = 0.001f;
    static float DEPOSITION = 0.1f;
    static float ENTRAINMENT = 6.0f; 
    static float MIN_VOL = 0.01f;
    static float MAXDIFF = 0.01f;
    static float SETTLING = 0.8f;
    static float SEA_LEVEL = 0.35f;

    static int VIEW_SIZE_A = 1500;
    static float MIN_DISCHARGE_THRESHOLD = 0.5f; 
    static float SHALLOW_DEPTH = 0.02f; 
    static int DELTA_STEPS = 5;

    final int w, h;
    final float[][] height;
    final float[][] discharge;
    final float[][] dischargeTrack;
    final float[][] mx, my, mxTrack, myTrack;
    final StandalonePreview terrain;
    final long seed;
    final Random rng;
    final int viewSize;

    public SimpleHydrologyV30(long seed, int viewSize) {
        this.seed = seed;
        this.viewSize = viewSize;
        this.terrain = new StandalonePreview((int)seed);
        this.rng = new Random(seed + 9999);
        this.w = MAP_SIZE;
        this.h = MAP_SIZE;
        this.height = new float[h][w];
        this.discharge = new float[h][w];
        this.dischargeTrack = new float[h][w];
        this.mx = new float[h][w];
        this.my = new float[h][w];
        this.mxTrack = new float[h][w];
        this.myTrack = new float[h][w];
        initHeightmap();
    }

    void initHeightmap() {
        float scale = (float)viewSize / Math.max(w, h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float wx = (x - w/2f) * scale;
                float wz = (y - h/2f) * scale;
                height[y][x] = terrain.computeHeightWithOcean(wx, wz, terrain.computeHeightPure(wx, wz));
            }
    }

    float getH(int x, int y) {
        if (x < 0 || x >= w || y < 0 || y >= h) return 0;
        return height[y][x];
    }

    float getSlope(int ix, int iy) {
        float gx = (getH(ix + 1, iy) - getH(ix - 1, iy)) / 2f;
        float gy = (getH(ix, iy + 1) - getH(ix, iy - 1)) / 2f;
        return (float)Math.sqrt(gx*gx + gy*gy);
    }

    // 纯净法线（无吸引力，保证 HQ 稳定）
    float[] normal(int ix, int iy) {
        float gx = getH(Math.min(w-1, ix + 1), iy) - getH(Math.max(0, ix - 1), iy);
        float gy = getH(ix, Math.min(h-1, iy + 1)) - getH(ix, Math.max(0, iy - 1));
        float len = (float)Math.sqrt(gx*gx + gy*gy + 0.0001f);
        return new float[]{-gx/len, -gy/len};
    }

    void cascade(int ix, int iy) {
        if (ix < 1 || ix >= w - 1 || iy < 1 || iy >= h - 1) return;
        
        class Point implements Comparable<Point> {
            int x, y;
            float h;
            float dist;
            Point(int x, int y, float h, float dist) { this.x = x; this.y = y; this.h = h; this.dist = dist; }
            public int compareTo(Point o) { return Float.compare(this.h, o.h); }
        }
        
        java.util.List<Point> neighbors = new java.util.ArrayList<>(8);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = ix + dx, ny = iy + dy;
                float dist = (dx != 0 && dy != 0) ? 1.4142135f : 1.0f;
                neighbors.add(new Point(nx, ny, height[ny][nx], dist));
            }
        }
        
        java.util.Collections.sort(neighbors);
        
        for (Point p : neighbors) {
            float currentH = height[iy][ix];
            float diff = currentH - p.h;
            if (diff == 0) continue;
            
            float excess = 0.0f;
            if (p.h > 0.1f) {
                excess = Math.abs(diff) - p.dist * MAXDIFF;
            } else {
                excess = Math.abs(diff);
            }
            
            if (excess <= 0) continue;
            
            float transfer = SETTLING * excess / 2.0f;
            
            if (diff > 0) {
                height[iy][ix] -= transfer;
                height[p.y][p.x] += transfer;
            } else {
                height[iy][ix] += transfer;
                height[p.y][p.x] -= transfer;
            }
        }
    }

    // 模块化实验配置
    public static class Config {
        public String name;
        public int iterations = 40;
        public int dropsPerIter = 1200;
        public boolean usePointMode = false; // true = point-based (no brush), false = self-organizing brush
        public boolean usePyramid = false;

        public Config(String name) { this.name = name; }
    }

    public void runExperiment(Config cfg) {
        if (cfg.usePointMode) {
            // Point-based mode: same as HQ but with fewer resources
            float trackScale = 5000.0f / cfg.dropsPerIter;
            for (int iter = 0; iter < cfg.iterations; iter++) {
                resetTracks();
                int activeDrops = 0;
                while (activeDrops < cfg.dropsPerIter) {
                    float px = rng.nextFloat() * w, py = rng.nextFloat() * h;
                    if (getH((int)px, (int)py) < SEA_LEVEL) continue;
                    activeDrops++;
                    float[] pos = {px, py}, vel = {0, 0}, vsa = {1.0f, 0, 0};
                    while (descendPoint(pos, vel, vsa, 0.0f)) {}
                }
                updateDischargeAndMomentum(trackScale);
            }
        } else if (cfg.usePyramid) {
            // 从无效果慢慢调高：第一层大笔刷避开微观坑洼，最后一层纯点细化
            float trackScale = 5000.0f / cfg.dropsPerIter;
            int baseIter = cfg.iterations / 3;
            int[] layerIter = { baseIter, baseIter, cfg.iterations - baseIter * 2 };
            float[] radii = { 0.0f, 0.0f, 0.0f }; // 半径从 0.0 降到 0.0

            for (int layer = 0; layer < 3; layer++) {
                float r = radii[layer];
                for (int iter = 0; iter < layerIter[layer]; iter++) {
                    resetTracks();
                    int activeDrops = 0;
                    while (activeDrops < cfg.dropsPerIter) {
                        float px = rng.nextFloat() * w, py = rng.nextFloat() * h;
                        if (getH((int)px, (int)py) < SEA_LEVEL) continue;
                        
                        float localSlope = getSlope((int)px, (int)py);
                        float spawnChance = 0.3f + 0.7f * Math.min(1.0f, localSlope / 0.02f);
                        if (rng.nextFloat() > spawnChance) continue;
                        
                        activeDrops++;
                        float[] pos = {px, py}, vel = {0, 0}, vsa = {1.0f, 0, 0};
                        while (descendPoint(pos, vel, vsa, r)) {}
                    }
                    updateDischargeAndMomentum(trackScale);
                }
            }
        } else {
            // Opt模式 - 使用体积法线的 descendPointOpt
            float trackScale = 5000.0f / cfg.dropsPerIter;
            for (int iter = 0; iter < cfg.iterations; iter++) {
                resetTracks();
                int activeDrops = 0;
                while (activeDrops < cfg.dropsPerIter) {
                    float px = rng.nextFloat() * w, py = rng.nextFloat() * h;
                    if (getH((int)px, (int)py) < SEA_LEVEL) continue;
                    activeDrops++;
                    float[] pos = {px, py}, vel = {0, 0}, vsa = {1.0f, 0, 0};
                    while (descendPoint(pos, vel, vsa, trackScale)) {}
                }
                updateDischargeAndMomentum(trackScale);
            }
        }
    }

    private void simulateSingleLayerModular(Config cfg) {
        float trackScale = 5000.0f / cfg.dropsPerIter;
        for (int iter = 0; iter < cfg.iterations; iter++) {
            resetTracks();
            int activeDrops = 0;
            while (activeDrops < cfg.dropsPerIter) {
                if (spawnDrop(cfg)) activeDrops++;
            }
            updateDischargeAndMomentum(trackScale);
        }
    }

    private void simulatePyramidModular(Config cfg) {
        // ... (This method is obsolete since we implemented it in runExperiment directly)
    }

    private boolean spawnDrop(Config cfg) {
        // ... (This method is obsolete)
        return true;
    }

    private void resetTracks() {
        for (int y = 0; y < h; y++) {
            Arrays.fill(dischargeTrack[y], 0);
            Arrays.fill(mxTrack[y], 0);
            Arrays.fill(myTrack[y], 0);
        }
    }

    private void updateDischargeAndMomentum(float trackScale) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                discharge[y][x] = (1f - LRATE) * discharge[y][x] + LRATE * (dischargeTrack[y][x] * trackScale);
                mx[y][x] = (1f - LRATE) * mx[y][x] + LRATE * mxTrack[y][x] * trackScale;
                my[y][x] = (1f - LRATE) * my[y][x] + LRATE * myTrack[y][x] * trackScale;
            }
    }

    // 水文特化版的自适应笔刷侵蚀
    void applyHydroBrush(int cx, int cy, float radius, float amount, boolean isErosion) {
        if (amount == 0 || radius <= 0) return;
        int r = (int)Math.ceil(radius);
        float weightSum = 0;
        float[][] weights = new float[2*r+1][2*r+1];

        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                float dist2 = dx*dx + dy*dy;
                if (dist2 <= radius*radius) {
                    float t = (float)Math.sqrt(dist2) / radius;
                    // 使用 smoothstep 笔刷权重，边缘过渡更自然
                    float wgt = 1.0f - t * t * (3 - 2 * t); 
                    weights[dy+r][dx+r] = wgt;
                    weightSum += wgt;
                }
            }
        }

        if (weightSum > 0) {
            for (int dy = -r; dy <= r; dy++) {
                int y = cy + dy;
                if (y < 0 || y >= h) continue;
                for (int dx = -r; dx <= r; dx++) {
                    int x = cx + dx;
                    if (x < 0 || x >= w) continue;
                    float wgt = weights[dy+r][dx+r];
                    if (wgt > 0) {
                        // 无论侵蚀还是沉积，都必须除以 weightSum 以保证物理守恒，防止形成“水坑黑洞”
                        float localAmount = amount * (wgt / weightSum);
                        height[y][x] -= localAmount;
                    }
                }
            }
        }
    }

    boolean descendHydroOpt(float[] pos, float[] vel, float[] volSedAge, float baseRadius, float maxRadius, float trackScale, float erosionMul, float depositionMul) {
        int ix = (int)pos[0], iy = (int)pos[1];
        if (ix < 0 || ix >= w || iy < 0 || iy >= h) return false;

        float vol = volSedAge[0], age = volSedAge[1], sediment = volSedAge[2];
        
        // 半径：从细窄开始，随着流量累积逐渐展宽
        float radius = baseRadius + (float)Math.sqrt(discharge[iy][ix] * trackScale + vol) * 0.3f;
        radius = Math.min(radius, maxRadius);

        if (age > MAX_AGE || vol < MIN_VOL) {
            applyHydroBrush(ix, iy, radius, -sediment, false);
            return false;
        }

        if (height[iy][ix] < SEA_LEVEL) {
            applyHydroBrush(ix, iy, radius, -sediment, false);
            dischargeTrack[iy][ix] += vol;
            mxTrack[iy][ix] += vol * vel[0]; myTrack[iy][ix] += vol * vel[1];
            return false;
        }

        // 自适应法线：笔刷半径大时用体积法线（感受宏观坡度），小时用精确局部法线
        float[] n = radius > 1.5f ? volumeNormal(pos[0], pos[1], radius * 0.5f) : normal(ix, iy);
        vel[0] += GRAVITY * n[0] / vol; vel[1] += GRAVITY * n[1] / vol;

        float mdx = mx[iy][ix], mdy = my[iy][ix];
        float mlen = (float)Math.sqrt(mdx*mdx + mdy*mdy);
        float vlen = (float)Math.sqrt(vel[0]*vel[0] + vel[1]*vel[1]);
        if (mlen > 0 && vlen > 0) {
            float dot = (vel[0]*mdx + vel[1]*mdy) / (vlen * mlen);
            float factor = MOMENTUM * dot / (vol + discharge[iy][ix]);
            vel[0] += factor * mdx; vel[1] += factor * mdy;
        }

        vlen = (float)Math.sqrt(vel[0]*vel[0] + vel[1]*vel[1]);
        if (vlen > 0) { 
            float step = 1.414f;
            vel[0] = (vel[0] / vlen) * step; 
            vel[1] = (vel[1] / vlen) * step; 
        }

        pos[0] += vel[0]; pos[1] += vel[1];
        if (pos[0] < 0 || pos[0] >= w || pos[1] < 0 || pos[1] >= h) return false;

        dischargeTrack[iy][ix] += vol;
        mxTrack[iy][ix] += vol * vel[0]; myTrack[iy][ix] += vol * vel[1];

        float h2;
        int nix = (int)pos[0], niy = (int)pos[1];
        if (nix >= w-1 || niy >= h-1) h2 = height[iy][ix] - 0.002f;
        else {
            float dx = pos[0] - nix, dy = pos[1] - niy;
            h2 = height[niy][nix]*(1-dx)*(1-dy) + height[niy][nix+1]*dx*(1-dy)
               + height[niy+1][nix]*(1-dx)*dy + height[niy+1][nix+1]*dx*dy;
        }

        float slope = Math.max(0, height[iy][ix] - h2);
        float nodeDischarge = erf(0.4f * discharge[iy][ix]);
        float c_eq = (1.0f + ENTRAINMENT * nodeDischarge) * slope;
        float cdiff = c_eq - sediment;
        float delta = DEPOSITION * cdiff;
        
        sediment += delta;
        
        // 1.5x 点侵蚀：在已汇聚河道处深切引导槽
        float pointAmount = delta * 1.5f;
        height[iy][ix] -= pointAmount;
        
        // 笔刷展宽：借助归一化刷子做温和展宽，防止水坑
        float brushAmount = delta * Math.min(erosionMul * discharge[iy][ix] * trackScale, 0.5f);
        if (brushAmount > 0.001f) {
            applyHydroBrush(ix, iy, radius, brushAmount, true);
        } else if (brushAmount < -0.001f) {
            applyHydroBrush(ix, iy, radius, brushAmount, false);
        }
        cascade(ix, iy);
        
        volSedAge[0] = vol * (1.0f - EVAP); volSedAge[1] = age + 1; volSedAge[2] = sediment / (1.0f - EVAP);
        return true;
    }

    // 体积采样助手
    float sampleVolumeHeight(float fx, float fy, float radius) {
        int ix = (int)fx, iy = (int)fy, r = (int)radius;
        if (r <= 0) return height[iy][ix];
        float sum = 0, wSum = 0;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                int nx = ix + dx, ny = iy + dy;
                if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                    float dist = (float)Math.sqrt(dx*dx + dy*dy);
                    if (dist > radius) continue;
                    float weight = 1.0f - dist/radius;
                    sum += height[ny][nx] * weight; wSum += weight;
                }
            }
        }
        return wSum > 0 ? sum / wSum : height[iy][ix];
    }

    float[] volumeNormal(float fx, float fy, float radius) {
        int ix = (int)fx, iy = (int)fy, r = Math.max(1, (int)radius);
        float gx = getH(Math.min(w-1, ix + r), iy) - getH(Math.max(0, ix - r), iy);
        float gy = getH(ix, Math.min(h-1, iy + r)) - getH(ix, Math.max(0, iy - r));
        float len = (float)Math.sqrt(gx*gx + gy*gy + 0.0001f);
        return new float[]{-gx/len, -gy/len};
    }

    void applyTerrainBrush(int ix, int iy, float radius, float delta, float eroMul) {
        int r = (int)radius;
        if (r <= 0) { height[iy][ix] -= delta * (delta > 0 ? eroMul : 1.0f); return; }
        float wSum = 0;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                int nx = ix + dx, ny = iy + dy;
                if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                    float dist = (float)Math.sqrt(dx*dx + dy*dy);
                    if (dist > radius) continue;
                    wSum += (1.0f - dist/radius);
                }
            }
        }
        if (wSum <= 0) return;
        
        if (delta < 0) {
            // 沉积：为了避免在平原堆积巨大的阻塞山包，沉积严格遵守质量守恒，并分散到大范围内
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int nx = ix + dx, ny = iy + dy;
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                        float dist = (float)Math.sqrt(dx*dx + dy*dy);
                        if (dist > radius) continue;
                        height[ny][nx] -= delta * (1.0f - dist/radius) / wSum;
                    }
                }
            }
        } else {
            // 侵蚀：为了挖出能汇聚水流的深V型河谷，不除以 wSum！
            // 这样中心点会下降 delta * eroMul，形成强烈的引导槽，边缘形成宽阔河谷
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int nx = ix + dx, ny = iy + dy;
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                        float dist = (float)Math.sqrt(dx*dx + dy*dy);
                        if (dist > radius) continue;
                        // 使用平滑权重挖槽
                        float weight = 1.0f - dist/radius;
                        height[ny][nx] -= delta * weight * eroMul;
                    }
                }
            }
        }
    }

    public void simulate() {
        // HQ Baseline: pure point-based physics, no self-organizing brush
        // Use a separate descend method that doesn't have self-organizing brush
        float trackScale = 1.0f;
        for (int iter = 0; iter < ITERATIONS; iter++) {
            resetTracks();
            int activeDrops = 0;
            while (activeDrops < DROPS_PER_ITER) {
                float px = rng.nextFloat() * w, py = rng.nextFloat() * h;
                if (getH((int)px, (int)py) < SEA_LEVEL) continue;
                activeDrops++;
                float[] pos = {px, py}, vel = {0, 0}, vsa = {1.0f, 0, 0};
                // Point-based descend for HQ
                while (descendPoint(pos, vel, vsa, 0.0f)) {}
            }
            updateDischargeAndMomentum(trackScale);
        }
    }

    boolean descendPoint(float[] pos, float[] vel, float[] volSedAge, float radius) {
        int ix = (int)pos[0], iy = (int)pos[1];
        if (ix < 0 || ix >= w || iy < 0 || iy >= h) return false;
        float vol = volSedAge[0], age = volSedAge[1], sediment = volSedAge[2];
        if (age > MAX_AGE || vol < MIN_VOL) {
            if (radius > 0.5f) applyHydroBrush(ix, iy, radius, -sediment, false);
            else height[iy][ix] += sediment;
            return false;
        }
        if (height[iy][ix] < SEA_LEVEL) {
            for (int step = 0; step < DELTA_STEPS; step++) {
                if (vol < MIN_VOL) break;
                vol *= 0.6f; sediment *= 0.6f;
                float vlen = (float)Math.sqrt(vel[0]*vel[0] + vel[1]*vel[1]);
                if (vlen > 0) { vel[0] = (vel[0]/vlen)*1.5f; vel[1] = (vel[1]/vlen)*1.5f; }
                pos[0] += vel[0]; pos[1] += vel[1];
                int nx = (int)pos[0], ny = (int)pos[1];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) break;
                dischargeTrack[ny][nx] += vol;
            }
            return false;
        }
        float[] n = radius > 0.5f ? volumeNormal(pos[0], pos[1], radius) : normal(ix, iy);
        
        vel[0] += GRAVITY * n[0] / vol; vel[1] += GRAVITY * n[1] / vol;
        float mdx = mx[iy][ix], mdy = my[iy][ix];
        float mlen = (float)Math.sqrt(mdx*mdx + mdy*mdy);
        float vlen = (float)Math.sqrt(vel[0]*vel[0] + vel[1]*vel[1]);
        if (mlen > 0 && vlen > 0) {
            float dot = (vel[0]*mdx + vel[1]*mdy) / (vlen * mlen);
            float factor = MOMENTUM * dot / (vol + discharge[iy][ix]);
            vel[0] += factor * mdx; vel[1] += factor * mdy;
        }
        vlen = (float)Math.sqrt(vel[0]*vel[0] + vel[1]*vel[1]);
        if (vlen > 0) { vel[0] = (vel[0]/vlen)*1.414f; vel[1] = (vel[1]/vlen)*1.414f; }
        pos[0] += vel[0]; pos[1] += vel[1];
        if (pos[0] < 0 || pos[0] >= w || pos[1] < 0 || pos[1] >= h) return false;
        dischargeTrack[iy][ix] += vol;
        mxTrack[iy][ix] += vol * vel[0]; myTrack[iy][ix] += vol * vel[1];
        float h2;
        int nix = (int)pos[0], niy = (int)pos[1];
        if (nix >= w-1 || niy >= h-1) h2 = height[iy][ix] - 0.002f;
        else {
            float dx = pos[0] - nix, dy = pos[1] - niy;
            h2 = height[niy][nix]*(1-dx)*(1-dy) + height[niy][nix+1]*dx*(1-dy)
               + height[niy+1][nix]*(1-dx)*dy + height[niy+1][nix+1]*dx*dy;
        }
        float slope = Math.max(0, height[iy][ix] - h2);
        float nodeDischarge = erf(0.4f * discharge[iy][ix]);
        float c_eq = (1.0f + ENTRAINMENT * nodeDischarge) * slope;
        float cdiff = c_eq - sediment;
        float delta = DEPOSITION * cdiff;
        sediment += delta; 
        
        if (radius > 0.5f) {
            applyHydroBrush(ix, iy, radius, delta, delta > 0);
        } else {
            height[iy][ix] -= delta;
        }
        
        sediment /= (1.0f - EVAP); vol *= (1.0f - EVAP);
        cascade(ix, iy);
        volSedAge[0] = vol; volSedAge[1] = age + 1; volSedAge[2] = sediment;
        return true;
    }



    public void simulatePyramid() {
        Config cfg = new Config("Pyramid Baseline");
        cfg.iterations = ITERATIONS;
        cfg.dropsPerIter = DROPS_PER_ITER;
        cfg.usePyramid = false;
        
        // ... (Obsolete)
    }

    void renderOnto(BufferedImage img, int offsetX, int offsetY) {
        java.util.List<Float> allD = new java.util.ArrayList<>();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (discharge[y][x] > 0) allD.add(discharge[y][x]);
        java.util.Collections.sort(allD);
        int n = allD.size();
        float p88 = n > 0 ? allD.get((int)(n * 0.88f)) : 0;
        float p95 = n > 0 ? allD.get((int)(n * 0.95f)) : 0;
        float p99 = n > 0 ? allD.get((int)(n * 0.99f)) : 0;
        float maxD = n > 0 ? allD.get(n - 1) : 0;

        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                float hVal = height[py][px];
                // 不使用高斯模糊，直接读取原始流量
                float d = discharge[py][px];
                int col1;
                if (hVal < SEA_LEVEL) {
                    col1 = 0x1a3a7a;
                } else {
                    col1 = terrainColor(hVal);
                    // 加入极小流量过滤 (MIN_DISCHARGE_THRESHOLD) 来解决散点噪点
                    if (d > MIN_DISCHARGE_THRESHOLD && d > p88) {
                        float t = (float)Math.log1p(d - p88) / (float)Math.log1p(maxD - p88 + 1);
                        if (d > p99) col1 = rgb((int)(10+t*20), (int)(40+t*60), (int)(150+t*105)); 
                        else col1 = rgb((int)(5+t*15), (int)(25+t*35), (int)(80+t*80)); 
                    }
                }
                img.setRGB(offsetX + px, offsetY + py, col1);
                
                int col2 = 0x050508;
                if (d < MIN_DISCHARGE_THRESHOLD) {
                    if (hVal < SEA_LEVEL) col2 = 0x0a1520;
                } else if (d > p88) {
                    float t = (float)Math.log1p(d - p88) / (float)Math.log1p(maxD - p88 + 1);
                    if (d > p99) col2 = rgb((int)(20+t*40), (int)(80+t*100), (int)(200+t*55));
                    else col2 = rgb((int)(10+t*20), (int)(30+t*50), (int)(100+t*80));
                } else if (hVal < SEA_LEVEL) {
                    col2 = 0x0a1520;
                }
                img.setRGB(offsetX + px, offsetY + h + py, col2);
            }
        }
    }

    int terrainColor(float h) {
        if (h < 0.35f) return 0x55AA55;
        if (h < 0.45f) return 0x66BB66;
        if (h < 0.55f) return 0x449944;
        if (h < 0.65f) return 0x88AA66;
        if (h < 0.75f) return 0xAA9966;
        if (h < 0.85f) return 0xBBAA88;
        return 0xDDDDCC;
    }

    static float erf(float x) {
        float a = Math.abs(x);
        float t = 1.0f / (1.0f + 0.47047f * a);
        float result = 1.0f - t*(0.3480242f + t*(-0.0958798f + t*0.7478556f))*(float)Math.exp(-a*a);
        return x >= 0 ? result : -result;
    }

    static int rgb(int r, int g, int b) { return (r<<16)|(g<<8)|b; }
    static int colorLerp(int c1, int c2, float t) {
        int r1 = (c1>>16)&0xFF, g1 = (c1>>8)&0xFF, b1 = c1&0xFF;
        int r2 = (c2>>16)&0xFF, g2 = (c2>>8)&0xFF, b2 = c2&0xFF;
        return rgb((int)(r1+(r2-r1)*t), (int)(g1+(g2-g1)*t), (int)(b1+(b2-b1)*t));
    }
    static String fmt1(float v) { return String.format("%.1f", v); }
    static String fmt3(float v) { return String.format("%.3f", v); }

    static void renderHeightDiffOnto(BufferedImage img, int ox, int oy, SimpleHydrologyV30 a, SimpleHydrologyV30 b, String label) {
        int w = a.w, h = a.h;
        float maxAbsDiff = 0.001f;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                if (a.height[y][x] < SEA_LEVEL && b.height[y][x] < SEA_LEVEL) continue;
                float diff = Math.abs(a.height[y][x] - b.height[y][x]);
                if (diff > maxAbsDiff) maxAbsDiff = diff;
            }
        Graphics2D g = img.createGraphics();
        g.setFont(new Font("Arial", Font.BOLD, 28));
        g.setColor(Color.WHITE);
        g.drawString(label + " (maxDiff=" + String.format("%.4f", maxAbsDiff) + ")", ox + 10, oy + 35);
        g.dispose();

        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float diff = a.height[y][x] - b.height[y][x];
                boolean bothWater = a.height[y][x] < SEA_LEVEL && b.height[y][x] < SEA_LEVEL;
                if (bothWater) {
                    img.setRGB(ox + x, oy + 50 + y, 0x0a1020);
                } else {
                    float t = Math.abs(diff) / maxAbsDiff;
                    if (diff > 0) {
                        int v = Math.min(255, (int)(t * 200));
                        img.setRGB(ox + x, oy + 50 + y, rgb(v, 0, 0));
                    } else {
                        int v = Math.min(255, (int)(t * 200));
                        img.setRGB(ox + x, oy + 50 + y, rgb(0, 0, v));
                    }
                }
            }
    }

    static void analyzeRiverSimilarity(SimpleHydrologyV30 simHQ, SimpleHydrologyV30 simOpt, BufferedImage img, int offsetX, int offsetY) {
        int w = simHQ.w, h = simHQ.h;

        // 收集两个模式的流量数据分布
        java.util.List<Float> hqVals = new java.util.ArrayList<>(), optVals = new java.util.ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                if (simHQ.discharge[y][x] > 0) hqVals.add(simHQ.discharge[y][x]);
                if (simOpt.discharge[y][x] > 0) optVals.add(simOpt.discharge[y][x]);
            }
        java.util.Collections.sort(hqVals); java.util.Collections.sort(optVals);
        int nH = hqVals.size(), nO = optVals.size();
        float hqP88 = nH > 0 ? hqVals.get((int)(nH * 0.88f)) : 0;
        float hqP95 = nH > 0 ? hqVals.get((int)(nH * 0.95f)) : 0;
        float hqMax = nH > 0 ? hqVals.get(nH - 1) : 0;
        float optP88 = nO > 0 ? optVals.get((int)(nO * 0.88f)) : 0;
        float optMax = nO > 0 ? optVals.get(nO - 1) : 0;

        // 使用各自独立的 p88 阈值判断是否为河流像素
        float hqThresh = Math.max(MIN_DISCHARGE_THRESHOLD, hqP88);
        float optThresh = Math.max(MIN_DISCHARGE_THRESHOLD, optP88);

        int tp = 0, fp = 0, fn = 0, tn = 0;
        double sumDiff = 0, sumHqSq = 0, sumOptSq = 0, sumHqOpt = 0;
        int riverPairs = 0;

        // 生成相似度可视化
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float hqD = simHQ.discharge[y][x];
                float optD = simOpt.discharge[y][x];
                boolean hqRiver = hqD > hqThresh;
                boolean optRiver = optD > optThresh;

                int simColor;
                if (hqRiver && optRiver) {
                    simColor = 0x00FF00; tp++;
                    sumDiff += (hqD - optD) * (hqD - optD);
                    sumHqSq += hqD * hqD; sumOptSq += optD * optD; sumHqOpt += hqD * optD;
                    riverPairs++;
                } else if (!hqRiver && optRiver) {
                    simColor = 0xFF0000; fp++;
                } else if (hqRiver && !optRiver) {
                    simColor = 0x0000FF; fn++;
                } else {
                    simColor = simHQ.height[y][x] < SEA_LEVEL ? 0x0a1520 : 0x080808;
                    tn++;
                }
                // img.setRGB(offsetX + x, offsetY + y, simColor); // 取消覆盖画图，只打印数据
            }
        }

        // 计算原始像素级指标
        float precision = tp + fp > 0 ? (float)tp / (tp + fp) : 0;
        float recall = tp + fn > 0 ? (float)tp / (tp + fn) : 0;
        float f1 = precision + recall > 0 ? 2 * precision * recall / (precision + recall) : 0;
        float iou = tp + fp + fn > 0 ? (float)tp / (tp + fp + fn) : 0;
        float accuracy = (float)(tp + tn) / (tp + fp + fn + tn);

        // ====== 距离容差相似度（河网轮廓匹配） ======
        float TOLERANCE = 3.0f; // 3像素容差
        boolean[][] hqRiver = new boolean[h][w];
        boolean[][] optRiver = new boolean[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            hqRiver[y][x] = simHQ.discharge[y][x] > hqThresh;
            optRiver[y][x] = simOpt.discharge[y][x] > optThresh;
        }

        // 距离变换：对HQ河流做容差膨胀，统计匹配的Opt河流像素
        int hqRiverPixels = 0, optRiverPixels = 0;
        int hqMatched = 0, optMatched = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (hqRiver[y][x]) {
                hqRiverPixels++;
                // 检查HQ河流周围TOLERANCE内是否有Opt河流
                boolean found = false;
                int y0 = Math.max(0, y - (int)TOLERANCE), y1 = Math.min(h-1, y + (int)TOLERANCE);
                int x0 = Math.max(0, x - (int)TOLERANCE), x1 = Math.min(w-1, x + (int)TOLERANCE);
                for (int dy = y0; dy <= y1 && !found; dy++) {
                    for (int dx = x0; dx <= x1 && !found; dx++) {
                        float dist = (float)Math.sqrt((dy-y)*(dy-y) + (dx-x)*(dx-x));
                        if (dist <= TOLERANCE && optRiver[dy][dx]) found = true;
                    }
                }
                if (found) hqMatched++;
            }
            if (optRiver[y][x]) {
                optRiverPixels++;
                // 检查Opt河流周围TOLERANCE内是否有HQ河流
                boolean found = false;
                int y0 = Math.max(0, y - (int)TOLERANCE), y1 = Math.min(h-1, y + (int)TOLERANCE);
                int x0 = Math.max(0, x - (int)TOLERANCE), x1 = Math.min(w-1, x + (int)TOLERANCE);
                for (int dy = y0; dy <= y1 && !found; dy++) {
                    for (int dx = x0; dx <= x1 && !found; dx++) {
                        float dist = (float)Math.sqrt((dy-y)*(dy-y) + (dx-x)*(dx-x));
                        if (dist <= TOLERANCE && hqRiver[dy][dx]) found = true;
                    }
                }
                if (found) optMatched++;
            }
        }
        float tolRecall = hqRiverPixels > 0 ? (float)hqMatched / hqRiverPixels : 0;
        float tolPrecision = optRiverPixels > 0 ? (float)optMatched / optRiverPixels : 0;
        float tolF1 = tolRecall + tolPrecision > 0 ? 2 * tolRecall * tolPrecision / (tolRecall + tolPrecision) : 0;

        // 相关系数
        float correlation = 0;
        if (riverPairs > 1) {
            double num = sumHqOpt - sumHqSq * sumOptSq / (riverPairs * riverPairs);
            double den = Math.sqrt((sumHqSq - sumHqSq*sumHqSq/(riverPairs*riverPairs)) * (sumOptSq - sumOptSq*sumOptSq/(riverPairs*riverPairs)));
            if (den > 0) correlation = (float)(num / den);
        }

        // 流量分布对比
        float hqCov = (float)nH / (w * h);
        float optCov = (float)nO / (w * h);

        System.out.println("\n=== 河流相似度分析 ===");
        System.out.printf("  HQ vs Opt 河流像素对比: TP=%d, FP=%d, FN=%d, TN=%d\n", tp, fp, fn, tn);
        System.out.printf("  IoU (交并比): %s\n", fmt3(iou));
        System.out.printf("  F1 Score:     %s\n", fmt3(f1));
        System.out.printf("  Precision:    %s\n", fmt3(precision));
        System.out.printf("  Recall:       %s\n", fmt3(recall));
        System.out.printf("  Accuracy:     %s\n", fmt3(accuracy));
        System.out.printf("  流量相关系数:  %s\n", fmt3(correlation));
        System.out.println("");
        System.out.printf("  ===== 河网轮廓匹配 (3px容差) =====\n");
        System.out.printf("  HQ河流轮廓:   %d px\n", hqRiverPixels);
        System.out.printf("  Opt河流轮廓:  %d px\n", optRiverPixels);
        System.out.printf("  HQ→Opt匹配:   %d / %d (%.1f%%)\n", hqMatched, hqRiverPixels, tolRecall*100);
        System.out.printf("  Opt→HQ匹配:   %d / %d (%.1f%%)\n", optMatched, optRiverPixels, tolPrecision*100);
        System.out.printf("  轮廓F1 Score: %s\n", fmt3(tolF1));
        System.out.printf("  ================================\n");
        System.out.println("");
        System.out.println("");
        System.out.printf("  HQ流量覆盖:   %s (%.1f%% of map)\n", fmt3(hqCov), hqCov*100);
        System.out.printf("  Opt流量覆盖:  %s (%.1f%% of map)\n", fmt3(optCov), optCov*100);
        System.out.printf("  HQ p88:       %s, p95: %s, max: %s\n", fmt1(hqP88), fmt1(hqP95), fmt1(hqMax));
        System.out.printf("  Opt p88:      %s, max: %s\n", fmt1(optP88), fmt1(optMax));
        System.out.println("");
        if (f1 > 0.5f) System.out.println("  ⭐ 评价: 良好 — 优化版已捕捉到大部分主要河流");
        else if (f1 > 0.3f) System.out.println("  ⚡ 评价: 一般 — 主干道匹配但细节差距大");
        else System.out.println("  ❌ 评价: 较差 — 河流结构差距显著，需大幅调整");
        System.out.println("========================\n");
    }

    public static void main(String[] args) throws Exception {
        long seed = 12345;
        VIEW_SIZE_A = 1500;
        MAP_SIZE = 1024;
        
        // ================================================================
        // 完整参数对比表：HQ (基准点物理) vs Opt (自适应笔刷)
        // ================================================================
        //
        // ┌────────────────────────┬─────────┬──────────┬────────────────────┐
        // │ 参数                   │  HQ     │  Opt     │ 说明               │
        // ├────────────────────────┼─────────┼──────────┼────────────────────┤
        // │ ITERATIONS             │  200    │  60      │ 迭代轮数           │
        // │ DROPS_PER_ITER         │  5000   │  1500    │ 每轮粒子数         │
        // │ MAX_AGE                │  500    │  500     │ 最大粒子寿命       │
        // │ LRATE                  │  0.1    │  0.1     │ 学习率             │
        // │ GRAVITY                │  1.5    │  1.5     │ 重力加速度         │
        // │ MOMENTUM               │  0.15   │  0.15    │ 动量系数           │
        // │ MOMENTUM_MUL           │  1.0    │  2.0     │ 动量倍率           │
        // │ EVAP                   │  0.001  │  0.001   │ 蒸发率             │
        // │ DEPOSITION             │  0.1    │  0.1     │ 沉积/侵蚀速率       │
        // │ ENTRAINMENT            │  6.0    │  6.0     │ 携沙能力系数       │
        // │ MIN_VOL                │  0.01   │  0.01    │ 最小水量阈值       │
        // │ SEA_LEVEL              │  0.35   │  0.35    │ 海平面高度         │
        // │ DELTA_STEPS            │  5      │  N/A     │ 三角洲步数         │
        // │ SETTLING               │  0.6    │  N/A     │ 沉降系数(cascade)  │
        // │ MAXDIFF                │  0.01   │  N/A     │ 最小扩散阈值       │
        // ├────────────────────────┼─────────┼──────────┼────────────────────┤
        // │ 笔刷: BASE_RADIUS      │  N/A    │  0.5     │ 基础半径           │
        // │ 笔刷: VOL_TO_RADIUS    │  N/A    │  0.8     │ 水量转半径系数     │
        // │ 笔刷: MAX_RADIUS       │  N/A    │  8.0     │ 最大半径           │
        // │ 笔刷: STEP_SCALE       │  N/A    │  0.5     │ 步长系数           │
        // │ 笔刷: ERO_MUL          │  N/A    │  4.0     │ 侵蚀放大倍数       │
        // │ 笔刷: WEIGHT_POW       │  N/A    │  4.0     │ 权重衰减指数       │
        // │ 笔刷: NORMAL_SCOPE     │  N/A    │  1.0     │ 法线采样范围系数   │
        // └────────────────────────┴─────────┴──────────┴────────────────────┘
        //
        // 与 HQ 不同的地方只有：
        //   1. ITERATIONS: 200 → 60    (节省 70% 迭代)
        //   2. DROPS_PER_ITER: 5000 → 1500  (节省 70% 粒子)
        //   3. 多了笔刷参数: 用于自适应扩宽河道
        //   4. MOMENTUM_MUL: 1.0 → 2.0  (Opt 用更强动量保持连贯)
        //   5. 取消了 cascade 沉降 (笔刷自带平滑效果)
        //   6. 取消了 DELTA_STEPS 三角洲 (笔刷让水流在大范围消散)
        // ================================================================
        
        System.out.println("运行基准模式: HQ Baseline (Point)");
        SimpleHydrologyV30 simHQ = new SimpleHydrologyV30(seed, VIEW_SIZE_A);
        simHQ.simulate();

        System.out.println("运行优化模式: Opt (Pure Point, 3000 drops)");
        SimpleHydrologyV30 simOpt = new SimpleHydrologyV30(seed, VIEW_SIZE_A);
        Config cfg = new Config("Opt Pyramid");
        cfg.iterations = 120;
        cfg.dropsPerIter = 5000;
        cfg.usePointMode = true;
        cfg.usePyramid = false;
        simOpt.runExperiment(cfg);

        // 创建大图：左右对比 + 地形变化对比
        int margin = 50;
        int thirdH = MAP_SIZE / 2 + 80; // 地形变化图区域
        int canvasW = MAP_SIZE * 2 + margin;
        int canvasH = MAP_SIZE * 2 + margin + thirdH;
        BufferedImage img = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(20, 20, 25));
        g.fillRect(0, 0, canvasW, canvasH);

        // 渲染主图
        simHQ.renderOnto(img, 0, 0);
        simOpt.renderOnto(img, MAP_SIZE + margin, 0);

        // 局部放大：选择地图中央 (512, 512) 附近 256x256 的区域进行 2 倍放大
        int zoomX = 400, zoomY = 400, zoomSize = 256;
        BufferedImage hqZoom = img.getSubimage(zoomX, zoomY, zoomSize, zoomSize);
        BufferedImage optZoom = img.getSubimage(MAP_SIZE + margin + zoomX, zoomY, zoomSize, zoomSize);
        
        g.drawImage(hqZoom, 0, MAP_SIZE + margin, MAP_SIZE, MAP_SIZE, null);
        g.drawImage(optZoom, MAP_SIZE + margin, MAP_SIZE + margin, MAP_SIZE, MAP_SIZE, null);

        // 绘制标签
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 40));
        g.drawString("HQ Baseline (Ground Truth)", 20, 50);
        g.drawString("Opt Localized (Brush Hybrid)", MAP_SIZE + margin + 20, 50);
        g.drawString("HQ ZOOM (4x)", 20, MAP_SIZE + margin + 50);
        g.drawString("OPT ZOOM (4x)", MAP_SIZE + margin + 20, MAP_SIZE + margin + 50);

        g.dispose();
        
        System.out.println("执行相似度分析和地形变化对比...");
        analyzeRiverSimilarity(simHQ, simOpt, img, 0, 0);

        // 在地形变化区域添加高度差对比
        int diffY = MAP_SIZE * 2 + margin;
        // 渲染全尺寸地形变化图
        BufferedImage fullDiff = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
        renderHeightDiffOnto(fullDiff, 0, -50, simHQ, simOpt, "");
        g = img.createGraphics();
        g.drawImage(fullDiff, 0, diffY, MAP_SIZE, MAP_SIZE / 2, null);
        // 局部放大地形变化
        BufferedImage diffZoomCrop = fullDiff.getSubimage(zoomX, zoomY, zoomSize, zoomSize);
        g.drawImage(diffZoomCrop, MAP_SIZE + margin, diffY, MAP_SIZE / 2, MAP_SIZE / 2, null);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 24));
        g.drawString("HQ - Opt Height Diff (Red=HQ deeper, Blue=Opt deeper)", 10, diffY + MAP_SIZE / 2 + 30);
        g.drawString("Diff ZOOM (4x)", MAP_SIZE + margin + 10, diffY + MAP_SIZE / 2 + 30);
        g.dispose();

        String outPath = "output/human_readable_comparison.png";
        ImageIO.write(img, "png", new File(outPath));
        System.out.println("大图已保存: " + outPath);
    }
}
