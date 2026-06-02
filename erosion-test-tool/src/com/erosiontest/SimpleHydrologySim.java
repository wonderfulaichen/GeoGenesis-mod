package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * 完整移植 SimpleHydrology 侵蚀模拟器
 * 
 * 核心流程（跟原版一致）：
 * 1. 多轮迭代（默认 50 轮）
 * 2. 每轮：
 *    a. 重置 discharge_track / momentum_track
 *    b. 撒大量粒子，每个粒子 descend()：
 *       - 沿地形法线+动量移动
 *       - 固定步长 sqrt(2)*cellSize
 *       - 刻蚀地形（cell.height -= sediment）
 *       - 更新 discharge/momentum track
 *       - 调用级联（cascade）让陡坡塌落
 *    c. 更新 discharge = lerp(discharge, discharge_track, lrate)
 * 3. 渲染侵蚀后的地形 + discharge 河流
 */
public class SimpleHydrologySim {

    // ============ 模拟参数（跟 SimpleHydrology 默认值对齐）============
    static int MAP_SIZE = 256;           // 地图大小（256x256）
    static int ITERATIONS = 100;         // 迭代轮数
    static int DROPS_PER_ITER = 4000;    // 每轮粒子数
    static float LRATE = 0.05f;          // discharge 学习率（更平滑）
    static float GRAVITY = 1.0f;         // 重力
    static float MOMENTUM_TRANSFER = 1.0f; // 动量传递
    static float EVAP_RATE = 0.001f;     // 蒸发率
    static float DEPOSITION_RATE = 0.05f; // 沉积率（降低侵蚀）
    static float ENTRAINMENT = 5.0f;     // 侵蚀增强（降低）
    static float MIN_VOL = 0.01f;        // 最小水量
    static float MAX_AGE = 500;          // 最大年龄
    static float MAX_DIFF = 0.01f;       // 级联最大高度差
    static float SETTLING = 0.8f;        // 级联沉降率

    // ============ 渲染参数 ============
    static float SEA_LEVEL = 0.35f;
    static int VIEW_SIZE = 6000;
    static final int OUTPUT_SIZE = 2048;

    final int w, h;
    final float[][] height;      // 地形高度（会被侵蚀修改）
    final float[][] discharge;   // discharge 地图
    final float[][] dischargeTrack;
    final float[][] momentumX;   // X 方向动量
    final float[][] momentumY;   // Y 方向动量
    final float[][] momentumXTrack;
    final float[][] momentumYTrack;

    final StandalonePreview terrain;
    final long seed;
    final Random rng;

    public SimpleHydrologySim(int seed, int mapSize) {
        this.seed = seed;
        this.terrain = new StandalonePreview(seed);
        this.rng = new Random(seed + 7777);
        this.w = mapSize;
        this.h = mapSize;
        this.height = new float[h][w];
        this.discharge = new float[h][w];
        this.dischargeTrack = new float[h][w];
        this.momentumX = new float[h][w];
        this.momentumY = new float[h][w];
        this.momentumXTrack = new float[h][w];
        this.momentumYTrack = new float[h][w];
    }

    void initHeightmap() {
        float scale = (float)VIEW_SIZE / Math.max(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float wx = (x - w/2f) * scale;
                float wz = (y - h/2f) * scale;
                height[y][x] = terrain.computeHeight(wx, wz);
            }
        }
    }

    float sampleHeight(float fx, float fy) {
        int x = (int)fx, y = (int)fy;
        if (x < 0 || x >= w-1 || y < 0 || y >= h-1) return SEA_LEVEL;
        float dx = fx - x, dy = fy - y;
        float h00 = height[y][x], h10 = height[y][x+1];
        float h01 = height[y+1][x], h11 = height[y+1][x+1];
        return h00*(1-dx)*(1-dy) + h10*dx*(1-dy) + h01*(1-dx)*dy + h11*dx*dy;
    }

    // 计算地形法线（用于重力方向）
    float[] normal(float fx, float fy) {
        float gx = sampleHeight(fx + 1.5f, fy) - sampleHeight(fx - 1.5f, fy);
        float gy = sampleHeight(fx, fy + 1.5f) - sampleHeight(fx, fy - 1.5f);
        float len = (float)Math.sqrt(gx*gx + gy*gy + 0.0001f);
        return new float[]{-gx/len, -gy/len, 1.0f/len};
    }

    // ============ 核心：粒子下降（完整移植）============
    boolean descend(float[] pos, float[] speed, float[] volumeSediment) {
        float px = pos[0], py = pos[1];
        int ix = (int)px, iy = (int)py;

        if (ix < 0 || ix >= w || iy < 0 || iy >= h) return false;

        float vol = volumeSediment[0];
        float sed = volumeSediment[1];

        // 终止检查
        if (volumeSediment[2] > MAX_AGE) {
            height[iy][ix] += sed;
            return false;
        }
        if (vol < MIN_VOL) {
            height[iy][ix] += sed;
            return false;
        }

        // 地形法线
        float[] n = normal(px, py);

        // 重力（沿法线水平分量）
        speed[0] += GRAVITY * n[0] / vol;
        speed[1] += GRAVITY * n[1] / vol;

        // 动量传递（来自 discharge 地图的动量）
        if (ix >= 0 && ix < w && iy >= 0 && iy < h) {
            float mx = momentumX[iy][ix];
            float my = momentumY[iy][ix];
            float mlen = (float)Math.sqrt(mx*mx + my*my);
            if (mlen > 0.001f) {
                float sx = speed[0], sy = speed[1];
                float slen = (float)Math.sqrt(sx*sx + sy*sy);
                if (slen > 0.001f) {
                    float dot = (sx*mx + sy*my) / (slen * mlen);
                    float factor = MOMENTUM_TRANSFER * dot / (vol + discharge[iy][ix]);
                    speed[0] += factor * mx;
                    speed[1] += factor * my;
                }
            }
        }

        // 固定步长 = sqrt(2) * cellSize（SimpleHydrology 核心）
        float slen = (float)Math.sqrt(speed[0]*speed[0] + speed[1]*speed[1]);
        if (slen > 0.001f) {
            float step = (float)Math.sqrt(2.0f);
            speed[0] = speed[0] / slen * step;
            speed[1] = speed[1] / slen * step;
        }

        // 移动
        px += speed[0];
        py += speed[1];
        pos[0] = px; pos[1] = py;

        // 越界检查
        if (px < 0 || px >= w || py < 0 || py >= h) {
            volumeSediment[0] = 0;
            return false;
        }

        // 更新 track 地图
        int nix = (int)px, niy = (int)py;
        if (nix >= 0 && nix < w && niy >= 0 && niy < h) {
            dischargeTrack[niy][nix] += vol;
            momentumXTrack[niy][nix] += vol * speed[0];
            momentumYTrack[niy][nix] += vol * speed[1];
        }

        // 侵蚀/沉积（Mass Transfer）
        float h2 = sampleHeight(px, py);
        float h1 = height[iy][ix];
        float c_eq = (1.0f + ENTRAINMENT * discharge[iy][ix]) * (h1 - h2);
        if (c_eq < 0) c_eq = 0;
        float cdiff = c_eq - sed;

        sed += DEPOSITION_RATE * cdiff;
        height[iy][ix] -= DEPOSITION_RATE * cdiff;

        // 蒸发（质量守恒）
        sed /= (1.0f - EVAP_RATE);
        vol *= (1.0f - EVAP_RATE);

        volumeSediment[0] = vol;
        volumeSediment[1] = sed;
        volumeSediment[2] += 1; // age

        // 级联（让陡坡塌落）
        cascade(ix, iy);

        return true;
    }

    // ============ 级联（Cascade）============
    void cascade(int cx, int cy) {
        int[][] dirs = {{-1,-1},{-1,0},{-1,1},{0,-1},{0,1},{1,-1},{1,0},{1,1}};

        for (int[] d : dirs) {
            int nx = cx + d[0], ny = cy + d[1];
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;

            float diff = height[cy][cx] - height[ny][nx];
            if (diff == 0) continue;

            float dist = (float)Math.sqrt(d[0]*d[0] + d[1]*d[1]);
            float excess;
            if (height[ny][nx] > 0.1f) {
                excess = Math.abs(diff) - dist * MAX_DIFF;
            } else {
                excess = Math.abs(diff);
            }
            if (excess <= 0) continue;

            float transfer = SETTLING * excess / 2.0f;
            if (diff > 0) {
                height[cy][cx] -= transfer;
                height[ny][nx] += transfer;
            } else {
                height[cy][cx] += transfer;
                height[ny][nx] -= transfer;
            }
        }
    }

    // ============ 主模拟循环 ============
    void simulate() {
        System.out.println("=== SimpleHydrology Full Sim ===");
        System.out.println("Map: " + w + "x" + h);
        System.out.println("Iterations: " + ITERATIONS + " x " + DROPS_PER_ITER + " drops");

        long t0 = System.nanoTime();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            // 1. 重置 track
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    dischargeTrack[y][x] = 0;
                    momentumXTrack[y][x] = 0;
                    momentumYTrack[y][x] = 0;
                }
            }

            // 2. 撒粒子
            for (int i = 0; i < DROPS_PER_ITER; i++) {
                float px, py;
                int attempts = 0;
                do {
                    px = 2 + rng.nextFloat() * (w - 4);
                    py = 2 + rng.nextFloat() * (h - 4);
                    attempts++;
                } while (sampleHeight(px, py) < SEA_LEVEL + 0.05f && attempts < 30);
                if (sampleHeight(px, py) < SEA_LEVEL + 0.05f) continue;

                float[] pos = {px, py};
                float[] speed = {0, 0};
                float[] vs = {1.0f, 0.0f, 0}; // volume, sediment, age

                while (descend(pos, speed, vs)) {
                    // 粒子继续下降
                }
            }

            // 3. 更新 discharge/momentum（指数平滑）
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    discharge[y][x] = (1.0f - LRATE) * discharge[y][x] + LRATE * dischargeTrack[y][x];
                    momentumX[y][x] = (1.0f - LRATE) * momentumX[y][x] + LRATE * momentumXTrack[y][x];
                    momentumY[y][x] = (1.0f - LRATE) * momentumY[y][x] + LRATE * momentumYTrack[y][x];
                }
            }

            if ((iter + 1) % 10 == 0) {
                float maxD = 0;
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++)
                        maxD = Math.max(maxD, discharge[y][x]);
                System.out.println("  Iter " + (iter+1) + "/" + ITERATIONS + "  maxD=" + fmt1(maxD));
            }
        }

        double totalMs = (System.nanoTime() - t0) / 1e6;
        System.out.println("Simulation done: " + fmt0(totalMs) + "ms");

        // 统计
        float maxD = 0; int nonZero = 0;
        List<Float> vals = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                maxD = Math.max(maxD, discharge[y][x]);
                if (discharge[y][x] > 0) {
                    nonZero++;
                    vals.add(discharge[y][x]);
                }
            }
        }
        Collections.sort(vals);
        int n = vals.size();
        System.out.println("Max discharge: " + fmt1(maxD) + "  Non-zero: " + nonZero);
        if (n > 0) {
            System.out.println("p50=" + fmt1(vals.get(n/2)) +
                " p90=" + fmt1(vals.get((int)(n*0.9))) +
                " p99=" + fmt1(vals.get((int)(n*0.99))));
        }
    }

    // ============ 渲染 ============
    void renderAndSave(String filePath) throws Exception {
        initHeightmap();
        simulate();

        int sz = OUTPUT_SIZE;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_RGB);
        float renderScale = (float)VIEW_SIZE / sz;
        float mapScale = (float)VIEW_SIZE / Math.max(w, h);

        // 找出最大 discharge
        float maxD = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                maxD = Math.max(maxD, discharge[y][x]);

        System.out.println("Rendering...");

        for (int py = 0; py < sz; py++) {
            float wz = (py - sz/2f) * renderScale;
            for (int px = 0; px < sz; px++) {
                float wx = (px - sz/2f) * renderScale;

                // 采样原始地形（不显示侵蚀后的）
                float h = terrain.computeHeight(wx, wz);
                float mx = wx / mapScale + w/2f;
                float my = wz / mapScale + h/2f;
                float d = sampleDischarge(mx, my);

                int color = getTerrainColor(h);

                // 河流覆盖（基于百分位显示）
                if (d > 0) {
                    float t = (float)Math.log1p(d) / (float)Math.log1p(maxD);
                    // 只显示前 10% 的 discharge
                    if (t > 0.5f) {
                        t = (t - 0.5f) * 2; // 0.5~1.0 映射到 0~1
                        int r = (int)(10 + t * 20);
                        int g = (int)(30 + t * 80);
                        int b = (int)(120 + t * 135);
                        color = rgb(r, g, b);
                    }
                }

                img.setRGB(px, py, color);
            }
        }

        File f = new File(filePath);
        ImageIO.write(img, "png", f);
        System.out.println("Saved: " + f.getAbsolutePath());

        // 额外输出热力图
        renderHeatmap("../output/simplehydro_s" + seed + "_heatmap.png", maxD);
    }

    float sampleDischarge(float fx, float fy) {
        int x = (int)fx, y = (int)fy;
        if (x < 0 || x >= w-1 || y < 0 || y >= h-1) return 0;
        float dx = fx - x, dy = fy - y;
        return discharge[y][x]*(1-dx)*(1-dy) + discharge[y][x+1]*dx*(1-dy)
             + discharge[y+1][x]*(1-dx)*dy + discharge[y+1][x+1]*dx*dy;
    }

    void renderHeatmap(String filePath, float maxD) throws Exception {
        System.out.println("Rendering heatmap...");
        BufferedImage hm = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float d = discharge[y][x];
                if (d < 0.1f) {
                    hm.setRGB(x, y, rgb(20, 20, 20));
                } else {
                    float t = (float)Math.log1p(d) / (float)Math.log1p(maxD);
                    hm.setRGB(x, y, heatmapColor(t));
                }
            }
        }
        ImageIO.write(hm, "png", new File(filePath));
        System.out.println("Heatmap: " + filePath);
    }

    int heatmapColor(float t) {
        t = Math.max(0, Math.min(1, t));
        if (t < 0.2f) return rgb(0, 0, (int)(t*5*255));
        else if (t < 0.4f) return rgb(0, (int)((t-0.2f)*5*255), 255);
        else if (t < 0.6f) return rgb(0, 255, (int)((0.6f-t)*5*255));
        else if (t < 0.8f) return rgb((int)((t-0.6f)*5*255), 255, 0);
        else return rgb(255, (int)((1-t)*5*255), 0);
    }

    int getTerrainColor(float h) {
        if (h < SEA_LEVEL - 0.05f) {
            return rgb(10, 40, Math.min(255, 80 + (int)((SEA_LEVEL - h) * 240)));
        } else if (h < SEA_LEVEL) {
            return rgb(190, 180, 60);
        } else {
            int v = (int)((h - SEA_LEVEL) / (1f - SEA_LEVEL) * 200) + 55;
            return rgb(v, v, v);
        }
    }

    static int rgb(int r, int g, int b) { return (r<<16) | (g<<8) | b; }
    static String fmt1(float v) { return String.format("%.1f", v); }
    static String fmt0(double v) { return String.format("%.0f", v); }

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        int mapSize = 256;
        int iterations = 50;
        int dropsPerIter = 4000;
        String tag = "v1";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed": seed = Integer.parseInt(args[++i]); break;
                case "--size": mapSize = Integer.parseInt(args[++i]); break;
                case "--iter": iterations = Integer.parseInt(args[++i]); break;
                case "--drops": dropsPerIter = Integer.parseInt(args[++i]); break;
                case "--tag": tag = args[++i]; break;
            }
        }
        ITERATIONS = iterations;
        DROPS_PER_ITER = dropsPerIter;
        MAP_SIZE = mapSize;

        SimpleHydrologySim sim = new SimpleHydrologySim(seed, mapSize);
        sim.renderAndSave("../output/simplehydro_s" + seed + "_" + tag + ".png");
    }
}
