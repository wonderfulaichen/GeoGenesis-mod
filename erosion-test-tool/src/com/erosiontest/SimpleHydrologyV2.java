package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrology V2 - 正确移植
 * 
 * 关键改进：
 * 1. 强平滑地形（消除局部起伏）
 * 2. 固定步长粒子（sqrt(2) 细胞大小）
 * 3. 多轮迭代（discharge 累积）
 * 4. 只渲染高 discharge 路径
 */
public class SimpleHydrologyV2 {

    static int MAP_SIZE = 256;
    static int ITERATIONS = 50;
    static int DROPS_PER_ITER = 5000;
    static float LRATE = 0.1f;
    static float GRAVITY = 2.0f;
    static float MOMENTUM = 0.85f;
    static float EVAP = 0.002f;
    static float MIN_VOL = 0.02f;
    static float MAX_AGE = 800;

    static float SEA_LEVEL = 0.35f;
    static int VIEW_SIZE = 6000;
    static final int OUTPUT_SIZE = 2048;

    final int w, h;
    final float[][] height;      // 平滑后的地形
    final float[][] discharge;
    final float[][] dischargeTrack;
    final float[][] mx, my;      // 动量地图
    final float[][] mxTrack, myTrack;

    final StandalonePreview terrain;
    final long seed;
    final Random rng;

    public SimpleHydrologyV2(int seed, int mapSize) {
        this.seed = seed;
        this.terrain = new StandalonePreview(seed);
        this.rng = new Random(seed + 9999);
        this.w = mapSize;
        this.h = mapSize;
        this.height = new float[h][w];
        this.discharge = new float[h][w];
        this.dischargeTrack = new float[h][w];
        this.mx = new float[h][w];
        this.my = new float[h][w];
        this.mxTrack = new float[h][w];
        this.myTrack = new float[h][w];
    }

    void init() {
        float scale = (float)VIEW_SIZE / Math.max(w, h);
        // 1. 采样原始地形
        float[][] raw = new float[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                raw[y][x] = terrain.computeHeight((x - w/2f) * scale, (y - h/2f) * scale);
            }
        }
        // 2. 强平滑（5x5 高斯）消除局部起伏
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0, wsum = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                            float wg = (float)Math.exp(-(dx*dx + dy*dy) / 4f);
                            sum += raw[ny][nx] * wg;
                            wsum += wg;
                        }
                    }
                }
                height[y][x] = sum / wsum;
            }
        }
    }

    float getH(float fx, float fy) {
        int x = (int)fx, y = (int)fy;
        if (x < 0 || x >= w-1 || y < 0 || y >= h-1) return SEA_LEVEL;
        float dx = fx - x, dy = fy - y;
        return height[y][x]*(1-dx)*(1-dy) + height[y][x+1]*dx*(1-dy)
             + height[y+1][x]*(1-dx)*dy + height[y+1][x+1]*dx*dy;
    }

    float[] normal(float fx, float fy) {
        float gx = getH(fx + 1.5f, fy) - getH(fx - 1.5f, fy);
        float gy = getH(fx, fy + 1.5f) - getH(fx, fy - 1.5f);
        float len = (float)Math.sqrt(gx*gx + gy*gy + 0.0001f);
        return new float[]{-gx/len, -gy/len};
    }

    // ============ 核心：粒子下降 ============
    boolean descend(float[] pos, float[] vel, float[] volAge) {
        float px = pos[0], py = pos[1];
        int ix = (int)px, iy = (int)py;
        if (ix < 0 || ix >= w || iy < 0 || iy >= h) return false;

        float vol = volAge[0];
        if (volAge[1] > MAX_AGE || vol < MIN_VOL) return false;

        // 重力
        float[] n = normal(px, py);
        vel[0] += GRAVITY * n[0] / vol;
        vel[1] += GRAVITY * n[1] / vol;

        // 动量传递（来自 discharge 地图）
        if (ix >= 0 && ix < w && iy >= 0 && iy < h) {
            float mdx = mx[iy][ix], mdy = my[iy][ix];
            float mlen = (float)Math.sqrt(mdx*mdx + mdy*mdy);
            if (mlen > 0.001f) {
                float sx = vel[0], sy = vel[1];
                float slen = (float)Math.sqrt(sx*sx + sy*sy);
                if (slen > 0.001f) {
                    float dot = (sx*mdx + sy*mdy) / (slen * mlen);
                    float factor = MOMENTUM * dot / (vol + discharge[iy][ix] + 1f);
                    vel[0] += factor * mdx;
                    vel[1] += factor * mdy;
                }
            }
        }

        // 固定步长 = sqrt(2)（SimpleHydrology 核心）
        float slen = (float)Math.sqrt(vel[0]*vel[0] + vel[1]*vel[1]);
        if (slen > 0.001f) {
            float step = (float)Math.sqrt(2.0f);
            vel[0] = vel[0] / slen * step;
            vel[1] = vel[1] / slen * step;
        }

        // 移动
        px += vel[0];
        py += vel[1];
        pos[0] = px; pos[1] = py;

        if (px < 0 || px >= w || py < 0 || py >= h) return false;

        // 更新 track
        int nix = (int)px, niy = (int)py;
        if (nix >= 0 && nix < w && niy >= 0 && niy < h) {
            dischargeTrack[niy][nix] += vol;
            mxTrack[niy][nix] += vol * vel[0];
            myTrack[niy][nix] += vol * vel[1];
        }

        // 蒸发
        vol *= (1 - EVAP);
        volAge[0] = vol;
        volAge[1] += 1;

        // 进入海洋停止
        if (getH(px, py) < SEA_LEVEL - 0.02f) return false;

        return true;
    }

    // ============ 主模拟 ============
    void simulate() {
        System.out.println("=== SimpleHydrology V2 ===");
        System.out.println("Map: " + w + "x" + h + "  Iter: " + ITERATIONS);

        long t0 = System.nanoTime();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            // 重置 track
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    dischargeTrack[y][x] = 0;
                    mxTrack[y][x] = 0;
                    myTrack[y][x] = 0;
                }
            }

            // 撒粒子
            for (int i = 0; i < DROPS_PER_ITER; i++) {
                float px, py;
                int attempts = 0;
                do {
                    px = 2 + rng.nextFloat() * (w - 4);
                    py = 2 + rng.nextFloat() * (h - 4);
                    attempts++;
                } while (getH(px, py) < SEA_LEVEL + 0.05f && attempts < 30);
                if (getH(px, py) < SEA_LEVEL + 0.05f) continue;

                float[] pos = {px, py};
                float[] vel = {0, 0};
                float[] va = {1.0f, 0};

                while (descend(pos, vel, va)) {}
            }

            // 更新 discharge（指数平滑）
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    discharge[y][x] = (1f - LRATE) * discharge[y][x] + LRATE * dischargeTrack[y][x];
                    mx[y][x] = (1f - LRATE) * mx[y][x] + LRATE * mxTrack[y][x];
                    my[y][x] = (1f - LRATE) * my[y][x] + LRATE * myTrack[y][x];
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
            System.out.println("p50=" + fmt1(vals.get(n/2)) + " p90=" + fmt1(vals.get((int)(n*0.9)))
                + " p99=" + fmt1(vals.get((int)(n*0.99))));
        }
    }

    // ============ 渲染 ============
    void renderAndSave(String filePath) throws Exception {
        init();
        simulate();

        int sz = OUTPUT_SIZE;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_RGB);
        float renderScale = (float)VIEW_SIZE / sz;
        float mapScale = (float)VIEW_SIZE / Math.max(w, h);

        float maxD = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                maxD = Math.max(maxD, discharge[y][x]);

        // 找出 p95 阈值（只渲染前 5% 高 discharge）
        List<Float> allD = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (discharge[y][x] > 0) allD.add(discharge[y][x]);
        Collections.sort(allD);
        float p95 = allD.size() > 0 ? allD.get((int)(allD.size() * 0.95f)) : 0;
        float p90 = allD.size() > 0 ? allD.get((int)(allD.size() * 0.90f)) : 0;
        System.out.println("Rendering (p90=" + fmt1(p90) + " p95=" + fmt1(p95) + ")...");

        for (int py = 0; py < sz; py++) {
            float wz = (py - sz/2f) * renderScale;
            for (int px = 0; px < sz; px++) {
                float wx = (px - sz/2f) * renderScale;

                float h = terrain.computeHeight(wx, wz);
                int color = getTerrainColor(h);

                float mx = wx / mapScale + w/2f;
                float my = wz / mapScale + h/2f;
                float d = sampleDischarge(mx, my);

                // 只渲染 p90+ 的 discharge（形成清晰河道）
                if (d > p90) {
                    float t = (float)Math.log1p(d - p90) / (float)Math.log1p(maxD - p90 + 1);
                    t = Math.min(1, t);
                    // 大河 = 亮蓝，小河 = 暗蓝
                    int r = (int)(10 + t * 20);
                    int g = (int)(30 + t * 80);
                    int b = (int)(120 + t * 135);
                    color = rgb(r, g, b);
                }

                img.setRGB(px, py, color);
            }
        }

        ImageIO.write(img, "png", new File(filePath));
        System.out.println("Saved: " + filePath);

        // 热力图
        renderHeatmap("../output/simplehydro2_s" + seed + "_heatmap.png", maxD, p90);
    }

    float sampleDischarge(float fx, float fy) {
        int x = (int)fx, y = (int)fy;
        if (x < 0 || x >= w-1 || y < 0 || y >= h-1) return 0;
        float dx = fx - x, dy = fy - y;
        return discharge[y][x]*(1-dx)*(1-dy) + discharge[y][x+1]*dx*(1-dy)
             + discharge[y+1][x]*(1-dx)*dy + discharge[y+1][x+1]*dx*dy;
    }

    void renderHeatmap(String filePath, float maxD, float threshold) throws Exception {
        System.out.println("Rendering heatmap...");
        BufferedImage hm = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float d = discharge[y][x];
                if (d < threshold) {
                    hm.setRGB(x, y, rgb(20, 20, 20));
                } else {
                    float t = (float)Math.log1p(d - threshold) / (float)Math.log1p(maxD - threshold + 1);
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
        if (h < SEA_LEVEL - 0.05f) return rgb(10, 40, Math.min(255, 80 + (int)((SEA_LEVEL - h) * 240)));
        else if (h < SEA_LEVEL) return rgb(190, 180, 60);
        else { int v = (int)((h - SEA_LEVEL) / (1f - SEA_LEVEL) * 200) + 55; return rgb(v, v, v); }
    }

    static int rgb(int r, int g, int b) { return (r<<16) | (g<<8) | b; }
    static String fmt1(float v) { return String.format("%.1f", v); }
    static String fmt0(double v) { return String.format("%.0f", v); }

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        int mapSize = 256;
        int iter = 50;
        int drops = 5000;
        String tag = "v1";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed": seed = Integer.parseInt(args[++i]); break;
                case "--size": mapSize = Integer.parseInt(args[++i]); break;
                case "--iter": iter = Integer.parseInt(args[++i]); break;
                case "--drops": drops = Integer.parseInt(args[++i]); break;
                case "--tag": tag = args[++i]; break;
            }
        }
        ITERATIONS = iter;
        DROPS_PER_ITER = drops;
        MAP_SIZE = mapSize;

        SimpleHydrologyV2 sim = new SimpleHydrologyV2(seed, mapSize);
        sim.renderAndSave("../output/simplehydro2_s" + seed + "_" + tag + ".png");
    }
}
