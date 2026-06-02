package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrology V4 - 使用 StandalonePreview 地形 + 完整侵蚀算法
 */
public class SimpleHydrologyV4 {

    static int MAP_SIZE = 512;
    static int ITERATIONS = 500;
    static int DROPS_PER_ITER = 5000;
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

    static float SEA_LEVEL = 0.35f;  // StandalonePreview 的海洋线
    static int VIEW_SIZE = 6000;
    static final int OUTPUT_SIZE = 2048;

    final int w, h;
    final float[][] height;
    final float[][] discharge;
    final float[][] dischargeTrack;
    final float[][] mx, my;
    final float[][] mxTrack, myTrack;

    final StandalonePreview terrain;
    final long seed;
    final Random rng;

    public SimpleHydrologyV4(int seed, int mapSize) {
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
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                height[y][x] = terrain.computeHeight((x - w/2f) * scale, (y - h/2f) * scale);
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
            int nx = (int)pos[0], ny = (int)pos[1];
            if (nx >= w-1 || ny >= h-1) h2 = height[iy][ix] - 0.002f;
            else {
                float dx = pos[0] - nx, dy = pos[1] - ny;
                h2 = height[ny][nx]*(1-dx)*(1-dy) + height[ny][nx+1]*dx*(1-dy)
                   + height[ny+1][nx]*(1-dx)*dy + height[ny+1][nx+1]*dx*dy;
            }
        }

        float nodeDischarge = discharge[iy][ix];
        // 限制 nodeDischarge 防止数值爆炸，但保留足够的影响
        float cappedDischarge = Math.min(nodeDischarge, 500.0f);
        float c_eq = (1.0f + ENTRAINMENT * cappedDischarge) * (height[iy][ix] - h2);
        if (c_eq < 0) c_eq = 0;
        float cdiff = c_eq - sediment;

        sediment += effD * cdiff;
        height[iy][ix] -= effD * cdiff;

        // 限制 sediment 防止数值爆炸
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
        System.out.println("=== SimpleHydrology V4 (StandalonePreview 地形) ===");
        System.out.println("Map: " + w + "x" + h + "  Iter: " + ITERATIONS);

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
        
        // 调试：统计 discharge 分布
        int[] buckets = new int[10];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float d = discharge[y][x];
                if (d == 0) buckets[0]++;
                else if (d < 1) buckets[1]++;
                else if (d < 10) buckets[2]++;
                else if (d < 100) buckets[3]++;
                else if (d < 1000) buckets[4]++;
                else if (d < 10000) buckets[5]++;
                else if (d < 100000) buckets[6]++;
                else buckets[7]++;
            }
        }
        System.out.println("Discharge distribution:");
        System.out.println("  0=" + buckets[0] + " <1=" + buckets[1] + " <10=" + buckets[2] + " <100=" + buckets[3]);
        System.out.println("  <1K=" + buckets[4] + " <10K=" + buckets[5] + " <100K=" + buckets[6] + " >=100K=" + buckets[7]);
    }

    void renderAndSave(String filePath) throws Exception {
        init();
        simulate();

        // 使用原尺寸渲染，避免放大导致河流不可见
        int sz = w;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_RGB);

        float maxD = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                maxD = Math.max(maxD, discharge[y][x]);

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
                int mx = Math.min(px, w - 1);
                int my = Math.min(py, h - 1);

                float terrainH = height[my][mx];

                // 使用 StandalonePreview 的 biome 颜色
                float wx = (mx - w/2f) * ((float)VIEW_SIZE / w);
                float wz = (my - h/2f) * ((float)VIEW_SIZE / h);
                float c = terrain.sampleContinentRaw(wx, wz);
                float temp = terrain.sampleTemperature(wx, wz);
                float moist = terrain.sampleMoisture(wx, wz, (c+1f)*0.5f, temp);
                int color = terrain.minecraftBiomeColor(c, temp, moist, terrainH, 0);

                // 采样周围最大 discharge，加粗河流显示
                float d = 0;
                int radius = 2;
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx = mx + dx, ny = my + dy;
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        d = Math.max(d, discharge[ny][nx]);
                    }
                }
                // 限制 d 的范围防止溢出
                if (d > maxD) d = maxD;

                // 分层渲染：p95+ 显示为河流，p98+ 加粗为主干
                if (d > p95) {
                    float t = (float)Math.log1p(d - p95) / (float)Math.log1p(maxD - p95 + 1);
                    t = Math.min(1, t);
                    if (d > p98) {
                        // 主干 = 亮蓝
                        int r = (int)(10 + t * 20);
                        int g = (int)(30 + t * 80);
                        int b = (int)(120 + t * 135);
                        color = rgb(r, g, b);
                    } else {
                        // 支流 = 更暗的蓝
                        int r = (int)(5 + t * 10);
                        int g = (int)(15 + t * 40);
                        int b = (int)(60 + t * 60);
                        color = rgb(r, g, b);
                    }
                }

                img.setRGB(px, py, color);
            }
        }

        ImageIO.write(img, "png", new File(filePath));
        System.out.println("Saved: " + filePath);

        renderHeatmap("../output/simplehydro4_s" + seed + "_heatmap.png", maxD, p95);
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

        SimpleHydrologyV4 sim = new SimpleHydrologyV4(seed, mapSize);
        sim.renderAndSave("../output/simplehydro4_s" + seed + "_" + tag + ".png");
    }
}
