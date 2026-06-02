package com.erosiontest;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrology V18: 真·找回 V4 (图2 完美复刻版)
 * 1. 物理引擎：完全回归最原始的 SimpleHydrologyV5 逻辑
 * 2. 比例尺：VIEW_SIZE = 1200 (让大陆细节被放大，河流显得宽阔)
 * 3. 渲染：100% 还原图2的明亮配色和蓝色河流
 */
public class SimpleHydrologyV18 {

    static int MAP_SIZE = 512;
    static int ITERATIONS = 150;
    static int DROPS_PER_ITER = 500;
    
    // V4 巅峰参数
    static float MOMENTUM = 0.1f;    // 低惯性，让重力主导蜿蜒
    static float GRAVITY = 4.0f;     // 高重力，快速冲向海洋
    static float EVAP = 0.01f;
    static float DEPOSITION = 0.1f;
    static float ENTRAINMENT = 0.1f; // 轻微侵蚀，保持河道整洁
    static float SETTLING = 0.5f;
    static float SEA_LEVEL = 0.35f;
    static int VIEW_SIZE = 1200;     // 放大细节，河流自然变粗

    float[][] height;
    float[][] discharge;
    Random rnd;
    StandalonePreview terrain;

    public SimpleHydrologyV18(long seed) {
        this.terrain = new StandalonePreview((int)seed);
        this.height = new float[MAP_SIZE][MAP_SIZE];
        this.discharge = new float[MAP_SIZE][MAP_SIZE];
        rnd = new Random(seed);
        initHeightmap();
    }

    void initHeightmap() {
        float scale = (float) VIEW_SIZE / MAP_SIZE;
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                float wx = (x - MAP_SIZE / 2f) * scale;
                float wz = (y - MAP_SIZE / 2f) * scale;
                height[y][x] = terrain.computeHeightPure(wx, wz);
            }
        }
    }

    public void simulate() {
        for (int i = 0; i < ITERATIONS; i++) {
            for (int j = 0; j < DROPS_PER_ITER; j++) {
                drop();
            }
        }
    }

    void drop() {
        float x = rnd.nextFloat() * (MAP_SIZE - 1);
        float y = rnd.nextFloat() * (MAP_SIZE - 1);
        float vx = 0, vy = 0;
        float sediment = 0;
        float volume = 1.0f;

        for (int age = 0; age < 300; age++) { 
            int ix = (int) x;
            int iy = (int) y;
            if (ix < 1 || ix >= MAP_SIZE - 1 || iy < 1 || iy >= MAP_SIZE - 1) break;
            
            float h0 = height[iy][ix];
            if (h0 < SEA_LEVEL) break;

            // 原始 V4 梯度受力
            float gx = (height[iy][ix+1] - height[iy][ix-1]) * 0.5f;
            float gy = (height[iy+1][ix] - height[iy-1][ix]) * 0.5f;

            vx = vx * MOMENTUM - gx * GRAVITY;
            vy = vy * MOMENTUM - gy * GRAVITY;

            float nx = x + vx;
            float ny = y + vy;
            if (nx < 1 || nx >= MAP_SIZE - 1 || ny < 1 || ny >= MAP_SIZE - 1) break;

            float hNext = height[(int)ny][(int)nx];
            float diff = h0 - hNext;

            if (diff > 0) {
                float e = diff * volume * ENTRAINMENT;
                height[iy][ix] -= e;
                sediment += e;
            } else {
                float d = Math.min(-diff, sediment);
                height[iy][ix] += d;
                sediment -= d;
            }

            discharge[iy][ix] += volume;

            x = nx;
            y = ny;
            volume *= (1 - EVAP);
            if (volume < 0.05f) break;
            
            if (age % 20 == 0) cascade(ix, iy);
        }
    }

    void cascade(int ix, int iy) {
        float h0 = height[iy][ix];
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = ix + dx, ny = iy + dy;
                if (nx < 0 || nx >= MAP_SIZE || ny < 0 || ny >= MAP_SIZE) continue;
                float diff = h0 - height[ny][nx];
                if (diff > 0.01f) {
                    float move = diff * SETTLING * 0.5f;
                    height[iy][ix] -= move;
                    height[ny][nx] += move;
                }
            }
        }
    }

    public void save(String path) throws Exception {
        simulate();
        BufferedImage img = new BufferedImage(MAP_SIZE * 2, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
        float maxD = 0;
        for (int y = 0; y < MAP_SIZE; y++) 
            for (int x = 0; x < MAP_SIZE; x++) 
                maxD = Math.max(maxD, discharge[y][x]);

        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                float d = discharge[y][x];
                int cBase = terrainColorV4(height[y][x]);
                
                // 面板 1: 侵蚀地形 + 蓝色大河
                int r = (cBase >> 16) & 0xFF, g = (cBase >> 8) & 0xFF, b = cBase & 0xFF;
                if (d > 5.0f) { // 阈值过滤，只留显眼的河流
                    float t = Math.min(1.0f, d / 100.0f);
                    r = (int)(r*(1-t)+20*t); g = (int)(g*(1-t)+60*t); b = (int)(b*(1-t)+220*t);
                }
                img.setRGB(x, y, (r<<16)|(g<<8)|b);

                // 面板 2: 热力图
                float v = (float)Math.sqrt(d / (maxD + 0.001f));
                img.setRGB(x + MAP_SIZE, y, Color.HSBtoRGB(0.6f, 1.0f, v));
            }
        }
        ImageIO.write(img, "png", new File(path));
    }

    int terrainColorV4(float h) {
        if (h < SEA_LEVEL) return 0x1A3A7A;
        if (h < 0.45f) return 0x55AA55;
        if (h < 0.65f) return 0x338833;
        if (h < 0.85f) return 0x888888;
        return 0xFFFFFF;
    }

    public static void main(String[] args) throws Exception {
        new SimpleHydrologyV18(99999).save("output/v18_s99999.png");
    }
}
