package com.erosiontest;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrology V17: 物理步长缩放版
 * 1. 步长从 sqrt(2) 提高到 3.0，跨越噪声微坑
 * 2. 坡度采样半径加大，感知宏观山谷
 * 3. 提高蒸发和侵蚀强度，强化主干，消除细碎纹路
 */
public class SimpleHydrologyV17 {

    static int MAP_SIZE = 512;
    static int ITERATIONS = 150;
    static int DROPS_PER_ITER = 600;
    static float LRATE = 0.1f;
    static float GRAVITY = 1.0f;
    static float MOMENTUM = 0.7f;
    static float EVAP = 0.015f;
    static float DEPOSITION = 0.1f;
    static float ENTRAINMENT = 8.0f;
    static float MIN_VOL = 0.05f;
    static float MAX_AGE = 400;
    static float SETTLING = 0.8f;
    static float SEA_LEVEL = 0.35f;
    static int VIEW_SIZE = 3000;

    final int w, h;
    final float[][] height;
    final float[][] heightOcean;
    final float[][] discharge;
    final float[][] dischargeTrack;
    final float[][] mx, my, mxTrack, myTrack;

    final StandalonePreview terrain;
    final Random rng;

    public SimpleHydrologyV17(int seed) {
        this.rng = new Random(seed + 9999);
        this.terrain = new StandalonePreview(seed);
        this.w = MAP_SIZE;
        this.h = MAP_SIZE;
        this.height = new float[h][w];
        this.heightOcean = new float[h][w];
        this.discharge = new float[h][w];
        this.dischargeTrack = new float[h][w];
        this.mx = new float[h][w];
        this.my = new float[h][w];
        this.mxTrack = new float[h][w];
        this.myTrack = new float[h][w];
        initHeightmap();
    }

    void initHeightmap() {
        float scale = (float) VIEW_SIZE / w;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float wx = (x - w / 2f) * scale;
                float wz = (y - h / 2f) * scale;
                float hPure = terrain.computeHeightPure(wx, wz);
                heightOcean[y][x] = terrain.computeHeightWithOcean(wx, wz, hPure);
                height[y][x] = hPure;
            }
        }
    }

    float getH(int x, int y) {
        if (x < 0) x = 0; if (x >= w) x = w - 1;
        if (y < 0) y = 0; if (y >= h) y = h - 1;
        return height[y][x];
    }

    float[] normal(float x, float y) {
        int ix = (int)x, iy = (int)y;
        // 采样半径加大到 2，获得更平滑的宏观坡度
        float gx = (getH(ix + 2, iy) - getH(ix - 2, iy)) * 0.25f;
        float gy = (getH(ix, iy + 2) - getH(ix, iy - 2)) * 0.25f;
        float len = (float) Math.sqrt(gx * gx + gy * gy + 0.0001f);
        return new float[]{-gx / len, -gy / len};
    }

    void cascade(int ix, int iy) {
        if (ix < 1 || ix >= w - 1 || iy < 1 || iy >= h - 1) return;
        float h0 = height[iy][ix];
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                float h1 = height[iy + dy][ix + dx];
                float diff = h0 - h1;
                if (diff > 0.01f) {
                    float move = diff * SETTLING * 0.5f;
                    height[iy][ix] -= move;
                    height[iy + dy][ix + dx] += move;
                }
            }
        }
    }

    boolean descend(float[] pos, float[] vel, float[] volSedAge) {
        int ix = (int) pos[0], iy = (int) pos[1];
        if (ix < 2 || ix >= w - 2 || iy < 2 || iy >= h - 2) return false;

        float vol = volSedAge[0], age = volSedAge[1], sediment = volSedAge[2];
        if (age > MAX_AGE || vol < MIN_VOL || height[iy][ix] < SEA_LEVEL) {
            height[iy][ix] += sediment; return false;
        }

        float[] n = normal(pos[0], pos[1]);
        vel[0] = vel[0] * MOMENTUM + n[0] * (1 - MOMENTUM) * GRAVITY;
        vel[1] = vel[1] * MOMENTUM + n[1] * (1 - MOMENTUM) * GRAVITY;

        float vlen = (float) Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1]);
        if (vlen > 0) {
            // 关键改动：物理步长增加到 3.0，跨越细碎地形
            vel[0] = (vel[0] / vlen) * 3.0f;
            vel[1] = (vel[1] / vlen) * 3.0f;
        }

        float nx = pos[0] + vel[0], ny = pos[1] + vel[1];
        if (nx < 2 || nx >= w - 2 || ny < 2 || ny >= h - 2) return false;

        float h1 = height[iy][ix];
        float h2 = height[(int)ny][(int)nx];
        float diff = h1 - h2;

        dischargeTrack[iy][ix] += vol;
        
        float c_eq = Math.max(0, diff) * vol * ENTRAINMENT;
        float cdiff = c_eq - sediment;
        sediment += DEPOSITION * cdiff;
        height[iy][ix] -= DEPOSITION * cdiff;

        pos[0] = nx; pos[1] = ny;
        volSedAge[0] = vol * (1 - EVAP);
        volSedAge[1] = age + 1;
        volSedAge[2] = sediment;

        if (age % 10 == 0) cascade(ix, iy);
        return true;
    }

    public void simulate() {
        for (int iter = 0; iter < ITERATIONS; iter++) {
            for (int y = 0; y < h; y++) Arrays.fill(dischargeTrack[y], 0);
            for (int i = 0; i < DROPS_PER_ITER; i++) {
                float[] pos = {2 + rng.nextFloat() * (w - 5), 2 + rng.nextFloat() * (h - 5)};
                if (height[(int)pos[1]][(int)pos[0]] < SEA_LEVEL) continue;
                float[] vel = {0, 0}, vsa = {1.0f, 0, 0.0f};
                while (descend(pos, vel, vsa)) {}
            }
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    discharge[y][x] = (1f - LRATE) * discharge[y][x] + LRATE * dischargeTrack[y][x];
                }
            }
        }
    }

    public void save(String path) throws Exception {
        simulate();
        BufferedImage img = new BufferedImage(w * 4, h, BufferedImage.TYPE_INT_RGB);
        float maxD = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) maxD = Math.max(maxD, discharge[y][x]);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float d = discharge[y][x];
                int cBase = terrainColorSmooth(height[y][x]);
                img.setRGB(x, y, cBase);

                // 面板 2：侵蚀+河流
                int r = (cBase >> 16) & 0xFF, g = (cBase >> 8) & 0xFF, b = cBase & 0xFF;
                if (d > maxD * 0.05f) {
                    float t = Math.min(1, d / (maxD * 0.5f));
                    r = (int)(r*(1-t)+20*t); g = (int)(g*(1-t)+50*t); b = (int)(b*(1-t)+200*t);
                }
                img.setRGB(x + w, y, (r<<16)|(g<<8)|b);

                // 面板 3：海洋对比
                if (heightOcean[y][x] < SEA_LEVEL) img.setRGB(x + 2*w, y, 0x1A3A7A);
                else img.setRGB(x + 2*w, y, cBase);

                // 面板 4：热力图
                float v = (float)Math.sqrt(d / (maxD + 0.001f));
                img.setRGB(x + 3*w, y, Color.HSBtoRGB(0.6f, 1.0f, v));
            }
        }
        ImageIO.write(img, "png", new File(path));
    }

    int terrainColorSmooth(float h) {
        h = Math.max(0, Math.min(1, h));
        float[][] stops = {{0.00f, 5, 20, 60}, {0.35f, 180, 170, 80}, {0.45f, 100, 150, 50}, {1.00f, 230, 230, 240}};
        int idx = 0; for (int i = 0; i < stops.length - 1; i++) if (h >= stops[i][0] && h <= stops[i + 1][0]) { idx = i; break; }
        float t = (h - stops[idx][0]) / (stops[idx + 1][0] - stops[idx][0] + 0.0001f);
        return (int)(stops[idx][1]+t*(stops[idx+1][1]-stops[idx][1]))<<16 | (int)(stops[idx][2]+t*(stops[idx+1][2]-stops[idx][2]))<<8 | (int)(stops[idx][3]+t*(stops[idx+1][3]-stops[idx][3]));
    }

    public static void main(String[] args) throws Exception {
        new SimpleHydrologyV17(12345).save("output/v17_s12345.png");
    }
}
