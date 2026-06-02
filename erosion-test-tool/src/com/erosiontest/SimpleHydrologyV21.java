package com.erosiontest;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrology V21: V20 引擎 + 多面板分支可视化
 * 
 * 物理引擎与 V20 完全一致（动量地图+erf+双线性插值）
 * 渲染新增：
 * - 面板3：河流骨架图（暗背景+亮蓝线，分支结构一目了然）
 * - 面板4：小比例尺概览图（缩放到 0.25x，一次看到更多河流）
 */
public class SimpleHydrologyV21 {

    static int MAP_SIZE = 1024;
    static int ITERATIONS = 200;
    static int DROPS_PER_ITER = 2000;
    static float LRATE = 0.1f;
    static float GRAVITY = 1.0f;
    static float MOMENTUM = 0.3f;
    static float EVAP = 0.001f;
    static float DEPOSITION = 0.1f;
    static float ENTRAINMENT = 8.0f;
    static float MIN_VOL = 0.01f;
    static float MAX_AGE = 500;
    static float MAXDIFF = 0.01f;
    static float SETTLING = 0.4f;
    static float SEA_LEVEL = 0.35f;
    static int VIEW_SIZE = 2500;

    final int w, h;
    final float[][] height;
    final float[][] discharge;
    final float[][] dischargeTrack;
    final float[][] mx, my, mxTrack, myTrack;
    final StandalonePreview terrain;
    final long seed;
    final Random rng;

    public SimpleHydrologyV21(int seed) {
        this.seed = seed;
        this.terrain = new StandalonePreview(seed);
        this.rng = new Random(seed + 9999);
        this.w = MAP_SIZE;
        this.h = MAP_SIZE;
        this.height = new float[h][w];
        this.discharge = new float[h][w];
        this.dischargeTrack = new float[h][w];
        this.mx = new float[h][w];
        this.my = new float[h][w];
        this.mxTrack = new float[h][w];
        this.myTrack = new float[h][w];
        initHeightmap();
    }

    void initHeightmap() {
        float scale = (float)VIEW_SIZE / Math.max(w, h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float wx = (x - w/2f) * scale;
                float wz = (y - h/2f) * scale;
                height[y][x] = terrain.computeHeightWithOcean(wx, wz, terrain.computeHeightPure(wx, wz));
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
        if (ix < 1 || ix >= w - 1 || iy < 1 || iy >= h - 1) return;
        float h0 = height[iy][ix];
        float totalOut = 0;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                float h1 = height[iy + dy][ix + dx];
                float diff = h0 - h1;
                if (Math.abs(diff) <= MAXDIFF) continue;
                float maxdiff = SETTLING * diff;
                height[iy + dy][ix + dx] += maxdiff;
                totalOut += maxdiff;
            }
        height[iy][ix] -= totalOut;
    }

    boolean descend(float[] pos, float[] vel, float[] volSedAge) {
        int ix = (int)pos[0], iy = (int)pos[1];
        if (ix < 0 || ix >= w || iy < 0 || iy >= h) return false;

        float vol = volSedAge[0], age = volSedAge[1], sediment = volSedAge[2];
        if (age > MAX_AGE || vol < MIN_VOL || height[iy][ix] < SEA_LEVEL) {
            height[iy][ix] += sediment;
            return false;
        }

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

        pos[0] += vel[0]; pos[1] += vel[1];
        if (pos[0] < 0 || pos[0] >= w || pos[1] < 0 || pos[1] >= h) return false;

        dischargeTrack[iy][ix] += vol;
        mxTrack[iy][ix] += vol * vel[0];
        myTrack[iy][ix] += vol * vel[1];

        float h2;
        int nx = (int)pos[0], ny = (int)pos[1];
        if (nx >= w-1 || ny >= h-1) h2 = height[iy][ix] - 0.002f;
        else {
            float dx = pos[0] - nx, dy = pos[1] - ny;
            h2 = height[ny][nx]*(1-dx)*(1-dy) + height[ny][nx+1]*dx*(1-dy)
               + height[ny+1][nx]*(1-dx)*dy + height[ny+1][nx+1]*dx*dy;
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
        volSedAge[0] = vol; volSedAge[1] = age + 1; volSedAge[2] = sediment;
        return true;
    }

    public void simulate() {
        System.out.println("=== SimpleHydrology V21 (四面板: 地形+河流+骨架+概览) ===");
        long t0 = System.nanoTime();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            for (int y = 0; y < h; y++) {
                Arrays.fill(dischargeTrack[y], 0);
                Arrays.fill(mxTrack[y], 0);
                Arrays.fill(myTrack[y], 0);
            }

            for (int i = 0; i < DROPS_PER_ITER; i++) {
                float px = rng.nextFloat() * w;
                float py = rng.nextFloat() * h;
                if (getH((int)px, (int)py) < SEA_LEVEL) continue;
                float[] pos = {px, py}, vel = {0, 0}, vsa = {1.0f, 0, 0};
                while (descend(pos, vel, vsa)) {}
            }

            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    discharge[y][x] = (1f - LRATE) * discharge[y][x] + LRATE * dischargeTrack[y][x];
                    mx[y][x] = (1f - LRATE) * mx[y][x] + LRATE * mxTrack[y][x];
                    my[y][x] = (1f - LRATE) * my[y][x] + LRATE * myTrack[y][x];
                }
        }

        double totalMs = (System.nanoTime() - t0) / 1e6;
        float maxD = 0;
        List<Float> vals = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                maxD = Math.max(maxD, discharge[y][x]);
                if (discharge[y][x] > 0) vals.add(discharge[y][x]);
            }
        Collections.sort(vals);
        System.out.println("Done: " + String.format("%.0f", totalMs) + "ms");
        System.out.println("Max discharge: " + String.format("%.1f", maxD));
        System.out.println("Non-zero: " + vals.size());
    }

    public void save(String path) throws Exception {
        simulate();

        List<Float> allD = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (discharge[y][x] > 0) allD.add(discharge[y][x]);
        Collections.sort(allD);
        int n = allD.size();
        float p95 = n > 0 ? allD.get((int)(n * 0.95f)) : 0;
        float p98 = n > 0 ? allD.get((int)(n * 0.98f)) : 0;
        float p99 = n > 0 ? allD.get((int)(n * 0.99f)) : 0;
        float p995 = n > 0 ? allD.get((int)(n * 0.995f)) : 0;
        float maxD = n > 0 ? allD.get(n - 1) : 0;
        System.out.println("p95="+fmt1(p95)+" p98="+fmt1(p98)+" p99="+fmt1(p99)+" p995="+fmt1(p995));

        // 3x3 模糊
        float[][] blurD = new float[h][w];
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                float sum = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++)
                        sum += discharge[y+dy][x+dx];
                blurD[y][x] = sum / 9f;
            }

        // 四面板：2048x2048
        // 面板1: 地形+河流 (0-1023, 0-1023)
        // 面板2: 河流骨架 (0-1023, 1024-2047)
        // 面板3: 小比例概览 (1024-2047, 0-1023)
        // 面板4: 热力图 (1024-2047, 1024-2047)
        BufferedImage img = new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_RGB);

        int sz = 1024; // 每个面板 1024x1024
        float overviewScale = 0.35f; // 概览缩小到 35%

        for (int py = 0; py < sz; py++) {
            for (int px = 0; px < sz; px++) {
                int sy = py, sx = px;
                if (sy >= h || sx >= w) continue;

                float hVal = height[sy][sx];
                float d = blurD[sy][sx];
                float rawD = discharge[sy][sx];

                // === 面板1: 地形+河流 ===
                int color1;
                if (hVal < SEA_LEVEL) {
                    color1 = 0x1a3a7a;
                } else {
                    color1 = terrainColor(hVal);
                }
                if (d > p98) {
                    float t = (float)Math.log1p(d - p98) / (float)Math.log1p(maxD - p98 + 1);
                    t = Math.max(0, Math.min(1, t));
                    if (d > p995)
                        color1 = rgb((int)(10+t*20), (int)(30+t*60), (int)(150+t*105));
                    else if (d > p99)
                        color1 = rgb((int)(5+t*15), (int)(20+t*50), (int)(100+t*80));
                    else
                        color1 = rgb((int)(5+t*10), (int)(15+t*30), (int)(70+t*60));
                }
                img.setRGB(px, py, color1);

                // === 面板2: 河流骨架图（暗背景+亮蓝线）===
                int color2 = 0x0a0a12; // 深色背景
                if (rawD > p98) {
                    float t = (float)Math.log1p(rawD - p98) / (float)Math.log1p(maxD - p98 + 1);
                    t = Math.max(0, Math.min(1, t));
                    // 支流淡蓝 → 主干亮白蓝
                    if (rawD > p995)
                        color2 = rgb((int)(30+t*40), (int)(80+t*100), (int)(200+t*55));
                    else if (rawD > p99)
                        color2 = rgb(20, (int)(60+t*40), (int)(160+t*60));
                    else
                        color2 = rgb(10, (int)(40+t*30), (int)(100+t*50));
                } else if (hVal < SEA_LEVEL) {
                    color2 = 0x0d1b2a; // 海洋略亮
                }
                img.setRGB(px, py + sz, color2);

                // === 面板4: 热力图 ===
                float v = (float)Math.sqrt(rawD / (maxD + 0.001f));
                img.setRGB(px + sz, py + sz, Color.HSBtoRGB(0.6f, 1.0f - v * 0.4f, v));
            }
        }

        // === 面板3: 小比例概览（缩放到 overviewScale）===
        int ovSize = (int)(sz * overviewScale);
        int ovOffsetX = sz + (sz - ovSize) / 2;
        int ovOffsetY = (sz - ovSize) / 2;

        // 先填充深空背景
        for (int py = 0; py < sz; py++)
            for (int px = 0; px < sz; px++)
                img.setRGB(px + sz, py, 0x050510);

        // 缩放渲染
        for (int py = 0; py < ovSize; py++) {
            for (int px = 0; px < ovSize; px++) {
                int sx = (int)(px / overviewScale);
                int sy = (int)(py / overviewScale);
                if (sx >= w || sy >= h) continue;

                float hVal = height[sy][sx];
                float rawD = discharge[sy][sx];

                if (rawD > p95) {
                    float t = (float)Math.log1p(rawD - p95) / (float)Math.log1p(maxD - p95 + 1);
                    t = Math.max(0, Math.min(1, t));
                    int r, g, b;
                    if (rawD > p995) {
                        r = (int)(30+t*40); g = (int)(80+t*100); b = (int)(200+t*55);
                    } else if (rawD > p99) {
                        r = 20; g = (int)(60+t*40); b = (int)(160+t*60);
                    } else {
                        r = 10; g = (int)(40+t*30); b = (int)(100+t*50);
                    }
                    img.setRGB(ovOffsetX + px, ovOffsetY + py, rgb(r, g, b));
                } else if (hVal < SEA_LEVEL) {
                    img.setRGB(ovOffsetX + px, ovOffsetY + py, 0x0d1b2a);
                } else {
                    img.setRGB(ovOffsetX + px, ovOffsetY + py, 0x0a0e0a);
                }
            }
        }

        // 概览边框
        java.awt.Graphics g = img.getGraphics();
        g.setColor(new Color(100, 120, 180));
        g.drawRect(ovOffsetX, ovOffsetY, ovSize, ovSize);

        // 标题
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 14));
        g.drawString("V21 四面板", 10, 20);
        g.drawString("Seed="+seed+" VIEW_SIZE="+VIEW_SIZE+" maxD="+fmt1(maxD), 10, 38);
        g.drawString("p95="+fmt1(p95)+" p98="+fmt1(p98)+" p99="+fmt1(p99)+" p995="+fmt1(p995), 10, 56);
        g.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
        g.drawString("⛰ 地形+河流", 10, 1010);
        g.drawString("〰 河流骨架", 10, sz+1010);
        g.drawString("◉ 小比例概览 ("+(int)(overviewScale*100)+"%)", sz+10, 1010);
        g.drawString("⬛ 热力图", sz+10, sz+1010);
        g.dispose();

        ImageIO.write(img, "png", new File(path));
        System.out.println("Saved: " + path);
    }

    int terrainColor(float h) {
        if (h < 0.35f) return 0x55AA55;
        if (h < 0.45f) return 0x66BB66;
        if (h < 0.55f) return 0x449944;
        if (h < 0.65f) return 0x88AA66;
        if (h < 0.75f) return 0xAA9966;
        if (h < 0.85f) return 0xBBAA88;
        return 0xDDDDCC;
    }

    static float erf(float x) {
        float a = Math.abs(x);
        float t = 1.0f / (1.0f + 0.47047f * a);
        float result = 1.0f - t * (0.3480242f + t * (-0.0958798f + t * 0.7478556f)) * (float)Math.exp(-a * a);
        return x >= 0 ? result : -result;
    }

    static int rgb(int r, int g, int b) { return (r<<16) | (g<<8) | b; }
    static String fmt1(float v) { return String.format("%.1f", v); }

    public static void main(String[] args) throws Exception {
        new SimpleHydrologyV21(12345).save("output/v21_s12345.png");
    }
}
