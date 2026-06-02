package com.erosiontest;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * SimpleHydrology V14: 渲染一致性修复版
 * 1. 修复 save() 中的坐标偏移 bug，使大陆尺度真正受 SCALE 控制
 * 2. 缩小大陆：使岛屿居中且占比减小
 * 3. 强化河流：减少粒子，极大化笔刷，使河流宽阔且主干分明
 */
public class SimpleHydrologyV8 {
    static int MAP_SIZE = 512;
    static int ITERATIONS = 150;
    static int DROPS_PER_ITER = 500;   // 减少粒子，避免毛细血管
    
    static float MOMENTUM = 0.6f;
    static float GRAVITY = 1.0f;
    static float EVAP = 0.015f;        // 提高蒸发，杀死弱小支流
    static float DEPOSITION = 0.1f;
    static float ENTRAINMENT = 20.0f;  // 极强侵蚀，刻出大峡谷
    static float SETTLING = 0.6f;
    static float SEA_LEVEL = 0.35f;
    static float SCALE = 3.0f;         // 缩放大陆，使其变为地图中央的岛屿

    float[][] height;
    float[][] discharge;
    Random rnd;
    StandalonePreview terrain;

    public SimpleHydrologyV8(long seed) {
        this.terrain = new StandalonePreview((int)seed);
        this.height = new float[MAP_SIZE][MAP_SIZE];
        this.discharge = new float[MAP_SIZE][MAP_SIZE];
        rnd = new Random(seed);
        initHeightmap();
    }

    // 统一坐标采样函数
    float getWX(int x) { return (x - MAP_SIZE / 2f) * SCALE; }
    float getWZ(int y) { return (y - MAP_SIZE / 2f) * SCALE; }

    void initHeightmap() {
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                height[y][x] = terrain.computeHeightPure(getWX(x), getWZ(y));
            }
        }
    }

    public void simulate() {
        for (int i = 0; i < ITERATIONS; i++) {
            for (int j = 0; j < DROPS_PER_ITER; j++) {
                drop();
            }
            if (i % 50 == 0) System.out.println("  Iter " + i + "/" + ITERATIONS);
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
            if (ix < 15 || ix >= MAP_SIZE - 16 || iy < 15 || iy >= MAP_SIZE - 16) break;
            
            float h00 = height[iy][ix];
            if (h00 < SEA_LEVEL) break;

            float gX = (height[iy][ix + 1] - h00) * 0.5f + (height[iy + 1][ix + 1] - height[iy + 1][ix]) * 0.5f;
            float gY = (height[iy + 1][ix] - h00) * 0.5f + (height[iy + 1][ix + 1] - height[iy][ix + 1]) * 0.5f;
            
            float glen = (float)Math.sqrt(gX*gX + gY*gY);
            if (glen > 0) {
                gX /= glen; gY /= glen;
            }

            vx = vx * MOMENTUM - gX * (1 - MOMENTUM);
            vy = vy * MOMENTUM - gY * (1 - MOMENTUM);

            float nx = x + vx;
            float ny = y + vy;
            if (nx < 10 || nx >= MAP_SIZE - 11 || ny < 10 || ny >= MAP_SIZE - 11) break;

            float hNext = height[(int)ny][(int)nx];
            float diff = h00 - hNext;

            if (volume > 0.3f) {
                // 巨型体积笔刷：确保河流在大尺度下依然醒目
                float radius = 2.0f + (volume * 15.0f); 
                erodeBrush(x, y, radius, Math.max(0, diff), volume, vx, vy, sediment);
                
                if (diff < 0) {
                    float depositAmount = Math.min(-diff, sediment);
                    height[iy][ix] += depositAmount;
                    sediment -= depositAmount;
                }
            }

            if (volume > 0.2f) {
                discharge[iy][ix] += volume;
            }

            x = nx;
            y = ny;
            volume *= (1 - EVAP);
            if (volume < 0.1f) break;
            
            if (age % 20 == 0) cascade(ix, iy);
        }
    }

    // 体积笔刷：将侵蚀力分散到周围
    void erodeBrush(float fx, float fy, float radius, float diff, float volume, float vx, float vy, float sediment) {
        int r = (int) Math.ceil(radius);
        float capacity = Math.max(0, diff) * volume * (float)Math.sqrt(vx*vx+vy*vy) * ENTRAINMENT;
        float amountToErode = (capacity - sediment) * DEPOSITION;
        if (amountToErode <= 0) return;

        float weightSum = 0;
        // 第一遍：计算权重
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                float distSq = dx * dx + dy * dy;
                if (distSq > radius * radius) continue;
                float weight = 1.0f - (float)Math.sqrt(distSq) / radius;
                weightSum += weight;
            }
        }

        // 第二遍：应用侵蚀
        float perWeightAmount = amountToErode / weightSum;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                float distSq = dx * dx + dy * dy;
                if (distSq > radius * radius) continue;
                float weight = 1.0f - (float)Math.sqrt(distSq) / radius;
                int targetX = (int)fx + dx;
                int targetY = (int)fy + dy;
                if (targetX >= 0 && targetX < MAP_SIZE && targetY >= 0 && targetY < MAP_SIZE) {
                    height[targetY][targetX] -= perWeightAmount * weight;
                }
            }
        }
    }

    float getHeight(int x, int y) {
        if (x < 0 || x >= MAP_SIZE || y < 0 || y >= MAP_SIZE) return SEA_LEVEL;
        return height[y][x];
    }

    void cascade(int ix, int iy) {
        float h0 = height[iy][ix];
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                float h1 = getHeight(ix + dx, iy + dy);
                float diff = h0 - h1;
                if (diff > 0.08f) { // 提高平滑阈值，只修剪尖锐边缘
                    float move = (diff - 0.08f) * SETTLING;
                    height[iy][ix] -= move * 0.5f;
                    height[iy + dy][ix + dx] += move * 0.5f;
                }
            }
        }
    }

    public void save(String path) throws Exception {
        int w = MAP_SIZE, h = MAP_SIZE;
        BufferedImage img = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        float maxD = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (discharge[y][x] > maxD) maxD = discharge[y][x];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // 关键修复：渲染时使用与计算完全一致的缩放坐标
                float wx = getWX(x), wz = getWZ(y);
                float hEroded = height[y][x];
                
                // 背景背景计算
                float hPure = terrain.computeHeightPure(wx, wz);
                float hOcean = terrain.computeHeightWithOcean(wx, wz, hPure);
                float c = terrain.sampleContinentRaw(wx, wz);
                float temp = terrain.sampleTemperature(wx, wz);
                float moist = terrain.sampleMoisture(wx, wz, (c + 1f) * 0.5f, temp);
                int biomeColor = terrain.minecraftBiomeColor(c, temp, moist, hEroded, 0);

                int c1 = terrainColorSmooth(hEroded);
                float d = discharge[y][x];
                float a = Math.min(1f, d / 60f); // 提高河流显示对比度

                // 面板 1: 侵蚀地形 + 显眼河流
                int col1 = lerpColorInt(c1, new Color(20, 50, 220).getRGB(), a * 0.9f);
                img.setRGB(x, y, col1);

                // 面板 2: MC 群系色 + 显眼河流
                int col2 = lerpColorInt(biomeColor, new Color(20, 40, 200).getRGB(), a * 0.95f);
                img.setRGB(x + w, y, col2);

                // 面板 3: 原始对比
                int col3;
                if (hOcean < SEA_LEVEL) {
                    col3 = new Color(30, 60, 150).getRGB();
                } else {
                    col3 = terrainColorSmooth(hOcean);
                }
                img.setRGB(x, y + h, col3);

                // 面板 4: 热力图
                float v = (float) Math.sqrt(d / (maxD + 0.001f));
                img.setRGB(x + w, y + h, Color.HSBtoRGB(0.6f, 1.0f - v * 0.4f, v));
            }
        }

        g.dispose();
        ImageIO.write(img, "png", new File(path));
        System.out.println("Saved: " + path);
    }

    int terrainColorSmooth(float h) {
        if (h < SEA_LEVEL) {
            float depth = (SEA_LEVEL - h) * 5f;
            int b = (int) (80 + 120 * Math.min(1, depth));
            return new Color(20, 40 + b / 3, b).getRGB();
        }
        float dh = h - SEA_LEVEL;
        int g = (int) (120 + 100 * Math.min(1, dh * 4f));
        int r = (int) (60 + 40 * Math.min(1, dh * 4f));
        if (dh > 0.3f) {
            float t = (dh - 0.3f) * 4f;
            r = (int) (100 + 100 * Math.min(1, t));
            g = (int) (160 - 60 * Math.min(1, t));
        }
        return new Color(Math.min(255, r), Math.min(255, g), Math.min(200, 40)).getRGB();
    }

    int lerpColorInt(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== SimpleHydrology V8 (Physics Fix) ===");
        long seed = 12345;
        SimpleHydrologyV8 sim = new SimpleHydrologyV8(seed);
        sim.simulate();
        sim.save("output/v8_s" + seed + ".png");
    }
}
