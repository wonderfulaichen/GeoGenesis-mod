package com.erosiontest;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrology V16: 真·回归 V7 巅峰版
 * 1. 物理引擎完全恢复到 V7 最初版 (v1) 的逻辑
 * 2. VIEW_SIZE = 6000 (让大陆变小，找回比例感)
 * 3. 减少粒子数 (300) 使河流变稀疏、变细，不占满地图
 */
public class SimpleHydrologyV16 {

    static int MAP_SIZE = 512;
    static int ITERATIONS = 150;
    static int DROPS_PER_ITER = 600; 
    static float LRATE = 0.1f;
    static float GRAVITY = 1.0f;
    static float MOMENTUM = 0.7f;
    static float EVAP = 0.02f;        // 提高蒸发，抹除细碎纹路
    static float DEPOSITION = 0.1f;
    static float ENTRAINMENT = 6.0f;  // 增强侵蚀，深挖主干
    static float SETTLING = 0.8f;
    static float SEA_LEVEL = 0.35f;
    static int VIEW_SIZE = 3000;      // 适中的大陆尺度

    // ... (在 descend 函数中增加物理步长)
    // vel[0] = (vel[0] / vlen) * 3.0f; 
    // vel[1] = (vel[1] / vlen) * 3.0f;

    final int w, h;
    final float[][] height;
    final float[][] heightOrig;
    final float[][] heightOcean;
    final float[][] discharge;
    final float[][] dischargeTrack;
    final float[][] mx, my, mxTrack, myTrack;

    final StandalonePreview terrain;
    final long seed;
    final Random rng;

    public SimpleHydrologyV16(int seed) {
        this.seed = seed;
        this.rng = new Random(seed + 9999);
        this.terrain = new StandalonePreview(seed);
        this.w = MAP_SIZE;
        this.h = MAP_SIZE;
        this.height = new float[h][w];
        this.heightOrig = new float[h][w];
        this.heightOcean = new float[h][w];
        this.discharge = new float[h][w];
        this.dischargeTrack = new float[h][w];
        this.mx = new float[h][w];
        this.my = new float[h][w];
        this.mxTrack = new float[h][w];
        this.myTrack = new float[h][w];
    }

    void initHeightmap() {
        float scale = (float) VIEW_SIZE / Math.max(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float wx = (x - w / 2f) * scale;
                float wz = (y - h / 2f) * scale;
                float hPure = terrain.computeHeightPure(wx, wz);
                heightOrig[y][x] = hPure;
                heightOcean[y][x] = terrain.computeHeightWithOcean(wx, wz, hPure);
                height[y][x] = hPure;
            }
        }
    }

    float getH(int x, int y) {
        if (x < 0 || x >= w || y < 0 || y >= h) return 0;
        return height[y][x];
    }

    float[] normal(int ix, int iy) {
        float gx = getH(ix + 1, iy) - getH(ix - 1, iy);
        float gy = getH(ix, iy + 1) - getH(ix, iy - 1);
        float len = (float) Math.sqrt(gx * gx + gy * gy + 0.0001f);
        return new float[]{-gx / len, -gy / len};
    }

    void cascade(int ix, int iy) {
        if (ix < 1 || ix >= w - 1 || iy < 1 || iy >= h - 1) return;
        float h0 = height[iy][ix];
        float totalOut = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                float h1 = height[iy + dy][ix + dx];
                float diff = h0 - h1;
                if (Math.abs(diff) <= MAXDIFF) continue;
                float maxdiff = SETTLING * diff;
                height[iy + dy][ix + dx] += maxdiff;
                totalOut += maxdiff;
            }
        }
        height[iy][ix] -= totalOut;
    }

    boolean descend(float[] pos, float[] vel, float[] volSedAge) {
        int ix = (int) pos[0];
        int iy = (int) pos[1];
        if (ix < 0 || ix >= w || iy < 0 || iy >= h) return false;

        float vol = volSedAge[0];
        float age = volSedAge[1];
        float sediment = volSedAge[2];

        if (age > MAX_AGE) { height[iy][ix] += sediment; return false; }
        if (vol < MIN_VOL) { height[iy][ix] += sediment; return false; }
        if (height[iy][ix] < SEA_LEVEL) { height[iy][ix] += sediment; return false; }

        float[] n = normal(ix, iy);
        vel[0] += GRAVITY * n[0] / vol;
        vel[1] += GRAVITY * n[1] / vol;

        float mdx = mx[iy][ix], mdy = my[iy][ix];
        float mlen = (float) Math.sqrt(mdx * mdx + mdy * mdy);
        float vlen = (float) Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1]);
        if (mlen > 0 && vlen > 0) {
            float dot = (vel[0] * mdx + vel[1] * mdy) / (vlen * mlen);
            float factor = MOMENTUM * dot / (vol + discharge[iy][ix] + 1f);
            vel[0] += factor * mdx;
            vel[1] += factor * mdy;
        }

        vlen = (float) Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1]);
        if (vlen > 0) {
            vel[0] = (vel[0] / vlen) * (float) Math.sqrt(2.0f);
            vel[1] = (vel[1] / vlen) * (float) Math.sqrt(2.0f);
        }

        pos[0] += vel[0];
        pos[1] += vel[1];
        if (pos[0] < 0 || pos[0] >= w || pos[1] < 0 || pos[1] >= h) return false;

        dischargeTrack[iy][ix] += vol;
        mxTrack[iy][ix] += vol * vel[0];
        myTrack[iy][ix] += vol * vel[1];

        float h2;
        if (pos[0] < 0 || pos[0] >= w || pos[1] < 0 || pos[1] >= h) {
            h2 = height[iy][ix] - 0.002f;
        } else {
            int nx = (int) pos[0], ny = (int) pos[1];
            if (nx >= w - 1 || ny >= h - 1) h2 = height[iy][ix] - 0.002f;
            else {
                float dx = pos[0] - nx, dy = pos[1] - ny;
                h2 = height[ny][nx] * (1 - dx) * (1 - dy) + height[ny][nx + 1] * dx * (1 - dy)
                    + height[ny + 1][nx] * (1 - dx) * dy + height[ny + 1][nx + 1] * dx * dy;
            }
        }

        float nodeDischarge = erf(0.4f * discharge[iy][ix]);
        float c_eq = (1.0f + ENTRAINMENT * nodeDischarge) * (height[iy][ix] - h2);
        if (c_eq < 0) c_eq = 0;

        float cdiff = c_eq - sediment;
        sediment += DEPOSITION * cdiff;
        height[iy][ix] -= DEPOSITION * cdiff;
        sediment = Math.max(-10.0f, Math.min(10.0f, sediment));

        sediment /= (1.0f - EVAP);
        vol *= (1.0f - EVAP);

        if (pos[0] < 0 || pos[0] >= w || pos[1] < 0 || pos[1] >= h) { vol = 0; return false; }

        cascade(ix, iy);

        volSedAge[0] = vol;
        volSedAge[1] = age + 1;
        volSedAge[2] = sediment;
        return true;
    }

    void simulate() {
        initHeightmap();
        for (int iter = 0; iter < ITERATIONS; iter++) {
            for (int y = 0; y < h; y++) {
                Arrays.fill(dischargeTrack[y], 0);
                Arrays.fill(mxTrack[y], 0);
                Arrays.fill(myTrack[y], 0);
            }
            for (int i = 0; i < DROPS_PER_ITER; i++) {
                float px = 2 + rng.nextFloat() * (w - 4);
                float py = 2 + rng.nextFloat() * (h - 4);
                if (height[(int) py][(int) px] < SEA_LEVEL) continue;
                float[] pos = {px, py}, vel = {0, 0}, vsa = {1.0f, 0, 0.0f};
                while (descend(pos, vel, vsa)) {}
            }
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    discharge[y][x] = (1f - LRATE) * discharge[y][x] + LRATE * dischargeTrack[y][x];
                    mx[y][x] = (1f - LRATE) * mx[y][x] + LRATE * mxTrack[y][x];
                    my[y][x] = (1f - LRATE) * my[y][x] + LRATE * myTrack[y][x];
                }
            }
        }
    }

    void renderAndSave(String filePath) throws Exception {
        simulate();
        int sz = w;
        int gap = 8;
        BufferedImage img = new BufferedImage(sz * 4 + gap * 3, sz + 50, BufferedImage.TYPE_INT_RGB);
        
        List<Float> allD = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (discharge[y][x] > 0) allD.add(discharge[y][x]);
        Collections.sort(allD);
        int n = allD.size();
        float p95 = n > 0 ? allD.get((int) (n * 0.95f)) : 0;
        float p99 = n > 0 ? allD.get((int) (n * 0.99f)) : 0;
        float maxD = n > 0 ? allD.get(n - 1) : 0;

        for (int py = 0; py < sz; py++) {
            float wz = (py - h / 2f) * (VIEW_SIZE / (float) h);
            for (int px = 0; px < sz; px++) {
                float wx = (px - w / 2f) * (VIEW_SIZE / (float) w);
                float hEroded = height[py][px];
                float c = terrain.sampleContinentRaw(wx, wz);
                float d = discharge[py][px];
                float temp = terrain.sampleTemperature(wx, wz);
                float moist = terrain.sampleMoisture(wx, wz, (c + 1f) * 0.5f, temp);
                int biomeColor = terrain.minecraftBiomeColor(c, temp, moist, hEroded, 0);

                int c1 = terrainColorSmooth(hEroded);
                img.setRGB(px, py, c1);

                int r2 = (c1 >> 16) & 0xFF, g2 = (c1 >> 8) & 0xFF, b2 = c1 & 0xFF;
                if (d > p95) {
                    float t = Math.min(1, (d - p95) / (p99 - p95 + 0.001f));
                    r2 = (int) (r2 * (1 - t) + 10 * t); g2 = (int) (g2 * (1 - t) + 40 * t); b2 = (int) (b2 * (1 - t) + 180 * t);
                }
                img.setRGB(sz + gap + px, py, rgb(r2, g2, b2));

                int r3 = (biomeColor >> 16) & 0xFF, g3 = (biomeColor >> 8) & 0xFF, b3 = biomeColor & 0xFF;
                if (c >= 0 && d > p95) {
                    float t = Math.min(1, (d - p95) / (p99 - p95 + 0.001f));
                    r3 = (int) (r3 * (1 - t) + 10 * t); g3 = (int) (g3 * (1 - t) + 40 * t); b3 = (int) (b3 * (1 - t) + 180 * t);
                }
                img.setRGB((sz + gap) * 2 + px, py, rgb(r3, g3, b3));

                float v = (float) Math.sqrt(d / (maxD + 0.001f));
                img.setRGB((sz + gap) * 3 + px, py, Color.HSBtoRGB(0.6f, 1.0f - v * 0.5f, v));
            }
        }
        ImageIO.write(img, "png", new File(filePath));
    }

    int terrainColorSmooth(float h) {
        h = Math.max(0, Math.min(1, h));
        float[][] stops = {{0.00f, 5, 20, 60}, {0.35f, 180, 170, 80}, {0.45f, 100, 150, 50}, {1.00f, 230, 230, 240}};
        int idx = 0; for (int i = 0; i < stops.length - 1; i++) if (h >= stops[i][0] && h <= stops[i + 1][0]) { idx = i; break; }
        float t = (h - stops[idx][0]) / (stops[idx + 1][0] - stops[idx][0] + 0.0001f);
        return rgb((int) (stops[idx][1] + t * (stops[idx + 1][1] - stops[idx][1])), (int) (stops[idx][2] + t * (stops[idx + 1][2] - stops[idx][2])), (int) (stops[idx][3] + t * (stops[idx + 1][3] - stops[idx][3])));
    }

    static float erf(float x) {
        float a = Math.abs(x); float t = 1.0f / (1.0f + 0.47047f * a);
        float result = 1.0f - t * (0.3480242f + t * (-0.0958798f + t * 0.7478556f)) * (float) Math.exp(-a * a);
        return x >= 0 ? result : -result;
    }
    static int rgb(int r, int g, int b) { return (r << 16) | (g << 8) | b; }
    public static void main(String[] args) throws Exception {
        int seed = 12345;
        String tag = "";
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--tag")) tag = "_" + args[++i];
        }
        new SimpleHydrologyV16(seed).renderAndSave("output/v16_s" + seed + tag + ".png");
    }
}
