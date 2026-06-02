package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrologyV7 - 基于 V5 已验证框架 + 粒子自适应笔刷
 * 
 * 不再做 C++ 移植，保留 V5 的核心逻辑：
 * - 简单梯度法线（非叉积）
 * - 粒子步长 sqrt(2)
 * - cascade 在旧位置调用
 * - 稳定双阶段：纯地形 + 海岸补充
 * 
 * 新增：笔刷半径随 discharge 自适应变化
 */
public class SimpleHydrologyV7 {

    static int MAP_SIZE = 512;
    static int ITERATIONS = 150;
    static int DROPS_PER_ITER = 500;
    static float LRATE = 0.1f;
    static float GRAVITY = 1.0f;
    static float MOMENTUM = 0.8f;
    static float EVAP = 0.001f;
    static float DEPOSITION = 0.1f;
    static float ENTRAINMENT = 4.0f;
    static float MIN_VOL = 0.01f;
    static float MAX_AGE = 500;
    static float MAXDIFF = 0.01f;
    static float SETTLING = 0.8f;

    static float SEA_LEVEL = 0.35f;
    static int VIEW_SIZE = 2500;

    // 笔刷参数
    static float BASE_RADIUS = 0.3f;
    static float VOL_TO_RADIUS = 0.3f;
    static float MAX_RADIUS = 3.0f;

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

    public SimpleHydrologyV7(int seed) {
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

    // V5 风格：简单梯度法线，直接指向下坡方向
    float[] normal(int ix, int iy) {
        float gx = getH(ix + 1, iy) - getH(ix - 1, iy);
        float gy = getH(ix, iy + 1) - getH(ix, iy - 1);
        float len = (float) Math.sqrt(gx * gx + gy * gy + 0.0001f);
        return new float[]{-gx / len, -gy / len};
    }

    // V5 风格：在侵蚀位置做温和的热扩散平滑
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

        if (age > MAX_AGE) {
            height[iy][ix] += sediment;
            return false;
        }
        if (vol < MIN_VOL) {
            height[iy][ix] += sediment;
            return false;
        }
        if (height[iy][ix] < SEA_LEVEL) {
            height[iy][ix] += sediment;
            return false;
        }

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

        if (pos[0] < 0 || pos[0] >= w || pos[1] < 0 || pos[1] >= h) {
            return false;
        }

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

        if (pos[0] < 0 || pos[0] >= w || pos[1] < 0 || pos[1] >= h) {
            vol = 0;
            return false;
        }

        cascade(ix, iy);

        volSedAge[0] = vol;
        volSedAge[1] = age + 1;
        volSedAge[2] = sediment;
        return true;
    }

    void simulate() {
        System.out.println("=== SimpleHydrology V7 (V5框架 + 笔刷侵蚀) ===");
        long t0 = System.nanoTime();
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

                float[] pos = {px, py};
                float[] vel = {0, 0};
                float[] vsa = {1.0f, 0, 0.0f};

                while (descend(pos, vel, vsa)) {
                }
            }

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    discharge[y][x] = (1f - LRATE) * discharge[y][x] + LRATE * dischargeTrack[y][x];
                    mx[y][x] = (1f - LRATE) * mx[y][x] + LRATE * mxTrack[y][x];
                    my[y][x] = (1f - LRATE) * my[y][x] + LRATE * myTrack[y][x];
                }
            }

            if ((iter + 1) % 30 == 0) {
                float maxD = 0;
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++)
                        maxD = Math.max(maxD, discharge[y][x]);
                System.out.println("  Iter " + (iter + 1) + "/" + ITERATIONS + "  maxD=" + fmt1(maxD));
            }
        }

        double totalMs = (System.nanoTime() - t0) / 1e6;
        System.out.println("Done: " + fmt0(totalMs) + "ms");

        float maxD = 0;
        List<Float> vals = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                maxD = Math.max(maxD, discharge[y][x]);
                if (discharge[y][x] > 0) vals.add(discharge[y][x]);
            }
        }
        Collections.sort(vals);
        int n = vals.size();
        System.out.println("Max discharge: " + fmt1(maxD) + "  Non-zero: " + n);
        if (n > 0) {
            System.out.println("  p90=" + fmt1(vals.get((int) (n * 0.9))) + " p95=" + fmt1(vals.get((int) (n * 0.95)))
                + " p98=" + fmt1(vals.get((int) (n * 0.98))) + " p99=" + fmt1(vals.get((int) (n * 0.99))));
        }
    }

    void renderAndSave(String filePath) throws Exception {
        simulate();

        int sz = w;
        int gap = 8;
        int panelW = sz;
        int imgW = panelW * 4 + gap * 3;
        int imgH = sz + 50;
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2 = (java.awt.Graphics2D) img.getGraphics();
        g2.fillRect(0, 0, imgW, imgH);
        g2.dispose();

        List<Float> allD = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (discharge[y][x] > 0) allD.add(discharge[y][x]);
        Collections.sort(allD);
        int n = allD.size();
        float p90 = n > 0 ? allD.get((int) (n * 0.90f)) : 0;
        float p95 = n > 0 ? allD.get((int) (n * 0.95f)) : 0;
        float p98 = n > 0 ? allD.get((int) (n * 0.98f)) : 0;
        float p99 = n > 0 ? allD.get((int) (n * 0.99f)) : 0;
        float p995 = n > 0 ? allD.get((int) (n * 0.995f)) : 0;
        float maxD = n > 0 ? allD.get(n - 1) : 0;

        float[][] blurD = new float[h][w];
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                float sum = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++)
                        sum += discharge[y + dy][x + dx];
                blurD[y][x] = sum / 9f;
            }

        for (int py = 0; py < sz; py++) {
            float wz = (py - h / 2f) * (VIEW_SIZE / (float) h);
            for (int px = 0; px < sz; px++) {
                float wx = (px - w / 2f) * (VIEW_SIZE / (float) w);

                float hEroded = height[py][px];
                float hOcean = heightOcean[py][px];
                float c = terrain.sampleContinentRaw(wx, wz);
                float d = blurD[py][px];
                float temp = terrain.sampleTemperature(wx, wz);
                float moist = terrain.sampleMoisture(wx, wz, (c + 1f) * 0.5f, temp);
                int biomeColor = terrain.minecraftBiomeColor(c, temp, moist, hEroded, 0);

                int c1 = terrainColorSmooth(hEroded);
                img.setRGB(px, py, c1);

                int c2 = terrainColorSmooth(hEroded);
                int r2 = (c2 >> 16) & 0xFF, g2r = (c2 >> 8) & 0xFF, b2r = c2 & 0xFF;
                if (d > p95) {
                    float t = (float) Math.log1p(d - p95) / (float) Math.log1p(maxD - p95 + 1);
                    t = Math.max(0, Math.min(1, t));
                    if (d > p995) {
                        r2 = (int) (10 + t * 20);
                        g2r = (int) (30 + t * 80);
                        b2r = (int) (120 + t * 135);
                    } else if (d > p99) {
                        r2 = (int) (5 + t * 15);
                        g2r = (int) (20 + t * 60);
                        b2r = (int) (80 + t * 100);
                    } else if (d > p98) {
                        r2 = (int) (5 + t * 10);
                        g2r = (int) (15 + t * 40);
                        b2r = (int) (60 + t * 60);
                    } else {
                        r2 = (int) (5 + t * 8);
                        g2r = (int) (15 + t * 30);
                        b2r = (int) (50 + t * 50);
                    }
                }
                img.setRGB(panelW + gap + px, py, rgb(Math.min(255, r2), Math.min(255, g2r), Math.min(255, b2r)));

                int r3 = (biomeColor >> 16) & 0xFF, g3 = (biomeColor >> 8) & 0xFF, b3 = biomeColor & 0xFF;
                if (c >= 0 && d > p95) {
                    float t = (float) Math.log1p(d - p95) / (float) Math.log1p(maxD - p95 + 1);
                    t = Math.max(0, Math.min(1, t));
                    if (d > p995) {
                        r3 = (int) (10 + t * 20);
                        g3 = (int) (30 + t * 80);
                        b3 = (int) (120 + t * 135);
                    } else if (d > p99) {
                        r3 = (int) (5 + t * 15);
                        g3 = (int) (20 + t * 60);
                        b3 = (int) (80 + t * 100);
                    } else {
                        r3 = (int) (5 + t * 10);
                        g3 = (int) (15 + t * 40);
                        b3 = (int) (60 + t * 60);
                    }
                }
                img.setRGB((panelW + gap) * 2 + px, py, rgb(Math.min(255, r3), Math.min(255, g3), Math.min(255, b3)));

                if (d < p90) {
                    img.setRGB((panelW + gap) * 3 + px, py, rgb(20, 20, 20));
                } else {
                    float t = (float) Math.log1p(d - p90) / (float) Math.log1p(maxD - p90 + 1);
                    t = Math.max(0, Math.min(1, t));
                    img.setRGB((panelW + gap) * 3 + px, py, heatColor(t));
                }
            }
        }

        drawLabel(img, "侵蚀后纯地形", 10, 2, 0xFFFFFF);
        drawLabel(img, "侵蚀后+河流", panelW + gap + 10, 2, 0xFFFFFF);
        drawLabel(img, "Minecraft地形+河流", (panelW + gap) * 2 + 10, 2, 0xFFFFFF);
        drawLabel(img, "Discharge热力图", (panelW + gap) * 3 + 10, 2, 0xFFFFFF);
        String info = "Seed=" + seed + " V7 Iter=" + ITERATIONS + " Drops=" + (ITERATIONS * DROPS_PER_ITER);
        drawLabel(img, info, 10, sz + 5, 0xAAAAAA);

        ImageIO.write(img, "png", new File(filePath));
        System.out.println("Saved: " + filePath);

        System.out.println("Thresholds: p90=" + fmt1(p90) + " p95=" + fmt1(p95) + " p98=" + fmt1(p98) + " p99=" + fmt1(p99) + " p995=" + fmt1(p995) + " max=" + fmt1(maxD));
    }

    int terrainColorSmooth(float h) {
        h = Math.max(0, Math.min(1, h));
        float[][] stops = {
            {0.00f, 5, 20, 60},
            {0.25f, 20, 60, 120},
            {0.35f, 180, 170, 80},
            {0.45f, 100, 150, 50},
            {0.55f, 60, 130, 50},
            {0.65f, 140, 150, 70},
            {0.75f, 170, 160, 120},
            {0.85f, 190, 185, 170},
            {1.00f, 230, 230, 240}
        };
        int idx = 0;
        for (int i = 0; i < stops.length - 1; i++) {
            if (h >= stops[i][0] && h <= stops[i + 1][0]) {
                idx = i;
                break;
            }
            if (i == stops.length - 2) idx = stops.length - 2;
        }
        float t = (h - stops[idx][0]) / (stops[idx + 1][0] - stops[idx][0] + 0.0001f);
        t = t * t * (3f - 2f * t);
        int r = (int) (stops[idx][1] + t * (stops[idx + 1][1] - stops[idx][1]));
        int g = (int) (stops[idx][2] + t * (stops[idx + 1][2] - stops[idx][2]));
        int b = (int) (stops[idx][3] + t * (stops[idx + 1][3] - stops[idx][3]));
        return rgb(r, g, b);
    }

    int heatColor(float t) {
        t = Math.max(0, Math.min(1, t));
        if (t < 0.2f) return rgb(0, 0, (int) (t * 5 * 255));
        else if (t < 0.4f) return rgb(0, (int) ((t - 0.2f) * 5 * 255), 255);
        else if (t < 0.6f) return rgb(0, 255, (int) ((0.6f - t) * 5 * 255));
        else if (t < 0.8f) return rgb((int) ((t - 0.6f) * 5 * 255), 255, 0);
        else return rgb(255, (int) ((1 - t) * 5 * 255), 0);
    }

    void drawLabel(BufferedImage img, String text, int x, int y, int color) {
        java.awt.Graphics g = img.getGraphics();
        g.setColor(new java.awt.Color(color));
        g.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
        g.drawString(text, x, y + 12);
        g.dispose();
    }

    static float erf(float x) {
        float a = Math.abs(x);
        float t = 1.0f / (1.0f + 0.47047f * a);
        float result = 1.0f - t * (0.3480242f + t * (-0.0958798f + t * 0.7478556f)) * (float) Math.exp(-a * a);
        return x >= 0 ? result : -result;
    }

    static int rgb(int r, int g, int b) { return (r << 16) | (g << 8) | b; }
    static String fmt1(float v) { return String.format("%.1f", v); }
    static String fmt0(double v) { return String.format("%.0f", v); }

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        String tag = "v1";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed": seed = Integer.parseInt(args[++i]); break;
                case "--tag": tag = args[++i]; break;
            }
        }
        SimpleHydrologyV7 sim = new SimpleHydrologyV7(seed);
        sim.renderAndSave("output/v7_s" + seed + "_" + tag + ".png");
    }
}
