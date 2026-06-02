package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrology V3 - 完整移植原版算法
 *
 * 关键修复（对比 V2）：
 * 1. 实时地形侵蚀（粒子下降时修改 height，形成河道凹陷）
 * 2. Cascade 崩塌稳定（防止地形过陡）
 * 3. 使用纯 FBM 地形（匹配原版 OpenSimplex2 风格）
 * 4. 原版 discharge 渲染方式（无阈值过滤）
 * 5. 原版参数值
 */
public class SimpleHydrologyV3 {

    // ============ 原版参数 ============
    static int MAP_SIZE = 512;
    static int ITERATIONS = 200;
    static int DROPS_PER_ITER = 2000;
    static float LRATE = 0.1f;
    static float GRAVITY = 1.0f;
    static float MOMENTUM = 1.0f;
    static float EVAP = 0.001f;
    static float DEPOSITION = 0.1f;
    static float ENTRAINMENT = 10.0f;
    static float MIN_VOL = 0.01f;
    static float MAX_AGE = 500;
    static float MAXDIFF = 0.01f;
    static float SETTLING = 0.8f;

    static float SEA_LEVEL = 0.1f;  // 降低海洋阈值，让更多区域可生成粒子
    static int VIEW_SIZE = 6000;
    static final int OUTPUT_SIZE = 2048;

    final int w, h;
    final float[][] height;
    final float[][] discharge;
    final float[][] dischargeTrack;
    final float[][] mx, my;
    final float[][] mxTrack, myTrack;

    final long seed;
    final Random rng;
    final ImprovedNoise[] noises;

    public SimpleHydrologyV3(int seed, int mapSize) {
        this.seed = seed;
        this.rng = new Random(seed);
        this.w = mapSize;
        this.h = mapSize;
        this.height = new float[h][w];
        this.discharge = new float[h][w];
        this.dischargeTrack = new float[h][w];
        this.mx = new float[h][w];
        this.my = new float[h][w];
        this.mxTrack = new float[h][w];
        this.myTrack = new float[h][w];

        this.noises = new ImprovedNoise[8];
        for (int i = 0; i < 8; i++) {
            noises[i] = new ImprovedNoise(rng.nextLong());
        }
    }

    // ============ 初始化：原版 8 层 FBM 噪声 ============
    void init() {
        // 匹配原版：坐标归一化到 [0,1] 再乘频率
        float frequency = 1.0f;
        float amp = 0.6f;
        float seedZ = (seed % 10000);
        for (int o = 0; o < 8; o++) {
            ImprovedNoise noise = noises[o];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float px = (float)x / w * frequency;
                    float py = (float)y / h * frequency;
                    height[y][x] += amp * (float)noise.noise(px, py, seedZ);
                }
            }
            frequency *= 2;
            amp *= 0.6f;
        }

        // 归一化到 [0,1]
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                min = Math.min(min, height[y][x]);
                max = Math.max(max, height[y][x]);
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                height[y][x] = (height[y][x] - min) / (max - min + 0.0001f);
            }
        }
    }

    float getH(int x, int y) {
        if (x < 0 || x >= w || y < 0 || y >= h) return 0;
        return height[y][x];
    }

    float getH(float fx, float fy) {
        int x = (int)fx, y = (int)fy;
        if (x < 0 || x >= w-1 || y < 0 || y >= h-1) return 0;
        float dx = fx - x, dy = fy - y;
        return height[y][x]*(1-dx)*(1-dy) + height[y][x+1]*dx*(1-dy)
             + height[y+1][x]*(1-dx)*dy + height[y+1][x+1]*dx*dy;
    }

    float[] normal(int ix, int iy) {
        float gx = getH(ix + 1, iy) - getH(ix - 1, iy);
        float gy = getH(ix, iy + 1) - getH(ix, iy - 1);
        float len = (float)Math.sqrt(gx*gx + gy*gy + 0.0001f);
        return new float[]{-gx/len, -gy/len};
    }

    void cascade(int ix, int iy) {
        int[][] n = {
            {-1,-1}, {-1,0}, {-1,1},
            {0,-1},          {0,1},
            {1,-1},  {1,0},  {1,1}
        };

        float[] nh = new float[8];
        float[] nd = new float[8];
        int[] nxi = new int[8];
        int[] nyi = new int[8];
        int num = 0;

        for (int i = 0; i < 8; i++) {
            int nx = ix + n[i][0];
            int ny = iy + n[i][1];
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
            nxi[num] = nx;
            nyi[num] = ny;
            nh[num] = height[ny][nx];
            nd[num] = (float)Math.sqrt(n[i][0]*n[i][0] + n[i][1]*n[i][1]);
            num++;
        }

        Integer[] idx = new Integer[num];
        for (int i = 0; i < num; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Float.compare(nh[a], nh[b]));

        for (int i = 0; i < num; i++) {
            int ni = idx[i];
            float diff = height[iy][ix] - nh[ni];
            if (diff == 0) continue;

            float excess;
            if (nh[ni] > 0.1f) {
                excess = Math.abs(diff) - nd[ni] * MAXDIFF;
            } else {
                excess = Math.abs(diff);
            }
            if (excess <= 0) continue;

            float transfer = SETTLING * excess / 2.0f;
            if (diff > 0) {
                height[iy][ix] -= transfer;
                height[nyi[ni]][nxi[ni]] += transfer;
            } else {
                height[iy][ix] += transfer;
                height[nyi[ni]][nxi[ni]] -= transfer;
            }
        }
    }

    boolean descend(float[] pos, float[] vel, float[] volSedAge) {
        int ix = (int)pos[0];
        int iy = (int)pos[1];

        if (ix < 0 || ix >= w || iy < 0 || iy >= h) return false;

        float vol = volSedAge[0];
        float sediment = volSedAge[2];
        float age = volSedAge[1];

        if (age > MAX_AGE) {
            height[iy][ix] += sediment;
            return false;
        }
        if (vol < MIN_VOL) {
            height[iy][ix] += sediment;
            return false;
        }

        float effD = DEPOSITION;

        float[] n = normal(ix, iy);
        vel[0] += GRAVITY * n[0] / vol;
        vel[1] += GRAVITY * n[1] / vol;

        float mdx = mx[iy][ix], mdy = my[iy][ix];
        float mlen = (float)Math.sqrt(mdx*mdx + mdy*mdy);
        float vlen = (float)Math.sqrt(vel[0]*vel[0] + vel[1]*vel[1]);
        if (mlen > 0 && vlen > 0) {
            float dot = (vel[0]*mdx + vel[1]*mdy) / (vlen * mlen);
            float factor = MOMENTUM * dot / (vol + discharge[iy][ix] + 1f);
            vel[0] += factor * mdx;
            vel[1] += factor * mdy;
        }

        vlen = (float)Math.sqrt(vel[0]*vel[0] + vel[1]*vel[1]);
        if (vlen > 0) {
            vel[0] = (vel[0] / vlen) * (float)Math.sqrt(2.0f);
            vel[1] = (vel[1] / vlen) * (float)Math.sqrt(2.0f);
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
            h2 = getH(pos[0], pos[1]);
        }

        float nodeDischarge = discharge[iy][ix];
        float c_eq = (1.0f + ENTRAINMENT * nodeDischarge) * (height[iy][ix] - h2);
        if (c_eq < 0) c_eq = 0;
        float cdiff = c_eq - sediment;

        sediment += effD * cdiff;
        height[iy][ix] -= effD * cdiff;

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
        System.out.println("=== SimpleHydrology V3 (完整移植) ===");
        System.out.println("Map: " + w + "x" + h + "  Iter: " + ITERATIONS);
        System.out.println("Drops/Iter: " + DROPS_PER_ITER);
        System.out.println("Total drops: " + (ITERATIONS * DROPS_PER_ITER));

        long t0 = System.nanoTime();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    dischargeTrack[y][x] = 0;
                    mxTrack[y][x] = 0;
                    myTrack[y][x] = 0;
                }
            }

            for (int i = 0; i < DROPS_PER_ITER; i++) {
                float px = rng.nextFloat() * w;
                float py = rng.nextFloat() * h;

                if (getH((int)px, (int)py) < SEA_LEVEL) continue;

                float[] pos = {px, py};
                float[] vel = {0, 0};
                float[] vsa = {1.0f, 0, 0.0f};

                while (descend(pos, vel, vsa)) {}
            }

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    discharge[y][x] = (1f - LRATE) * discharge[y][x] + LRATE * dischargeTrack[y][x];
                    mx[y][x] = (1f - LRATE) * mx[y][x] + LRATE * mxTrack[y][x];
                    my[y][x] = (1f - LRATE) * my[y][x] + LRATE * myTrack[y][x];
                }
            }

            if ((iter + 1) % 20 == 0) {
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

    void renderAndSave(String filePath) throws Exception {
        init();
        simulate();

        int sz = OUTPUT_SIZE;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_RGB);
        float mapScale = (float)w / sz;

        float maxD = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                maxD = Math.max(maxD, discharge[y][x]);

        // 计算 p90/p95 阈值
        List<Float> allD = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (discharge[y][x] > 0) allD.add(discharge[y][x]);
        Collections.sort(allD);
        float p90 = allD.size() > 0 ? allD.get((int)(allD.size() * 0.90f)) : 0;
        float p95 = allD.size() > 0 ? allD.get((int)(allD.size() * 0.95f)) : 0;
        float p98 = allD.size() > 0 ? allD.get((int)(allD.size() * 0.98f)) : 0;
        System.out.println("Rendering (p90=" + fmt1(p90) + " p95=" + fmt1(p95) + " p98=" + fmt1(p98) + ")...");

        for (int py = 0; py < sz; py++) {
            for (int px = 0; px < sz; px++) {
                int mx = (int)(px * mapScale);
                int my = (int)(py * mapScale);
                if (mx >= w) mx = w-1;
                if (my >= h) my = h-1;

                float h = height[my][mx];
                int color = getTerrainColor(h);

                float d = discharge[my][mx];

                // 只渲染 p95+ 的 discharge（形成清晰河道，过滤噪声）
                if (d > p95) {
                    float t = (float)Math.log1p(d - p95) / (float)Math.log1p(maxD - p95 + 1);
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

        renderHeatmap("../output/simplehydro3_s" + seed + "_heatmap.png", maxD, p95);
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
        // 海洋：浅蓝色
        if (h < SEA_LEVEL - 0.05f) {
            int depth = (int)((SEA_LEVEL - h) / SEA_LEVEL * 100);
            return rgb(20, 60 + depth, 120 + depth);
        }
        // 海滩
        else if (h < SEA_LEVEL) return rgb(194, 178, 128);
        // 陆地：绿色到棕色到白色（根据高度）
        else {
            float t = (h - SEA_LEVEL) / (1f - SEA_LEVEL);
            int r, g, b;
            if (t < 0.3f) { // 低地绿色
                r = (int)(34 + t/0.3f * 50);
                g = (int)(139 + t/0.3f * 30);
                b = (int)(34 + t/0.3f * 20);
            } else if (t < 0.7f) { // 中地棕色
                float tt = (t - 0.3f) / 0.4f;
                r = (int)(84 + tt * 80);
                g = (int)(169 - tt * 60);
                b = (int)(54 + tt * 20);
            } else { // 高地白色/灰色
                float tt = (t - 0.7f) / 0.3f;
                int v = (int)(164 + tt * 91);
                r = g = b = v;
            }
            return rgb(r, g, b);
        }
    }

    static int rgb(int r, int g, int b) { return (r<<16) | (g<<8) | b; }
    static String fmt1(float v) { return String.format("%.1f", v); }
    static String fmt0(double v) { return String.format("%.0f", v); }

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        int mapSize = 512;
        int iter = 200;
        int drops = 2000;
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

        SimpleHydrologyV3 sim = new SimpleHydrologyV3(seed, mapSize);
        sim.renderAndSave("../output/simplehydro3_s" + seed + "_" + tag + ".png");
    }
}
