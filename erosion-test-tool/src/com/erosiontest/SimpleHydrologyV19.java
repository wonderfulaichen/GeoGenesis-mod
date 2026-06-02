package com.erosiontest;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrology V19: 彻底回归 V4 逻辑
 * 1. 尺度回归：VIEW_SIZE = 1000 (大陆放大，河流显眼)
 * 2. 物理回归：放弃动量地图，回归最简单的坡度下降
 * 3. 渲染回归：使用高阈值过滤掉细小纹路，只留主干
 */
public class SimpleHydrologyV19 {

    static int MAP_SIZE = 512;
    static int ITERATIONS = 200;
    static int DROPS_PER_ITER = 400;
    
    static float MOMENTUM = 0.15f; 
    static float GRAVITY = 1.0f;
    static float EVAP = 0.01f;
    static float ENTRAINMENT = 0.15f;
    static float SETTLING = 0.5f;
    static float SEA_LEVEL = 0.35f;
    static int VIEW_SIZE = 1000; 

    float[][] height;
    float[][] discharge;
    Random rnd;
    StandalonePreview terrain;

    public SimpleHydrologyV19(long seed) {
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

        for (int age = 0; age < 256; age++) { 
            int ix = (int) x;
            int iy = (int) y;
            if (ix < 1 || ix >= MAP_SIZE - 1 || iy < 1 || iy >= MAP_SIZE - 1) break;
            
            float h0 = height[iy][ix];
            if (h0 < SEA_LEVEL) break;

            float gx = (height[iy][ix+1] - height[iy][ix-1]) * 0.5f;
            float gy = (height[iy+1][ix] - height[iy-1][ix]) * 0.5f;

            vx = vx * MOMENTUM - gx * (1-MOMENTUM);
            vy = vy * MOMENTUM - gy * (1-MOMENTUM);
            
            float vlen = (float)Math.sqrt(vx*vx + vy*vy);
            if (vlen > 0) { vx /= vlen; vy /= vlen; } 

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

            x = nx; y = ny;
            volume *= (1 - EVAP);
            if (volume < 0.1f) break;
        }
    }

    public void save(String path) throws Exception {
        simulate();
        BufferedImage img = new BufferedImage(MAP_SIZE * 2, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
        
        float[][] blurredD = new float[MAP_SIZE][MAP_SIZE];
        for(int y=1; y<MAP_SIZE-1; y++) {
            for(int x=1; x<MAP_SIZE-1; x++) {
                blurredD[y][x] = (discharge[y][x]*4 + discharge[y-1][x] + discharge[y+1][x] + discharge[y][x-1] + discharge[y][x+1])/8f;
            }
        }

        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                float d = blurredD[y][x];
                int cBase = terrainColorV4(height[y][x]);
                
                if (d > 12.0f) { 
                    float t = Math.min(1.0f, d / 120.0f);
                    int r = (cBase >> 16) & 0xFF, g = (cBase >> 8) & 0xFF, b = cBase & 0xFF;
                    r = (int)(r*(1-t)+10*t); g = (int)(g*(1-t)+40*t); b = (int)(b*(1-t)+220*t);
                    img.setRGB(x, y, (r<<16)|(g<<8)|b);
                } else {
                    img.setRGB(x, y, cBase);
                }

                float v = (float)Math.sqrt(discharge[y][x] / 200.0f);
                img.setRGB(x + MAP_SIZE, y, Color.HSBtoRGB(0.6f, 1.0f, Math.min(1, v)));
            }
        }
        ImageIO.write(img, "png", new File(path));
    }

    int terrainColorV4(float h) {
        if (h < SEA_LEVEL) return 0x1A3A7A;
        if (h < 0.45f) return 0x66CC66;
        if (h < 0.65f) return 0x44AA44;
        if (h < 0.85f) return 0x999999;
        return 0xFFFFFF;
    }

    public static void main(String[] args) throws Exception {
        new SimpleHydrologyV19(12345).save("output/v19_s12345.png");
    }
}
