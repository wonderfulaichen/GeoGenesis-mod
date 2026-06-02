package com.erosiontest;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrology V23: 双尺度四面板对比拼图
 * 
 * 物理引擎与 V20 完全一致。
 * 左半：VIEW_SIZE=1500（大比例尺，细节丰富）
 * 右半：VIEW_SIZE=4000（小比例尺，视野广阔）
 * 各半都是 V21 的四面板布局（地形+河流 / 河流骨架 / 小比例概览 / 热力图）
 */
public class SimpleHydrologyV23 {

    static int MAP_SIZE = 512;  // 每组 512×512，2组拼成 1024×512 的 4 面板 = 2048×2048
    static int ITERATIONS = 150;
    static int DROPS_PER_ITER = 1000;
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

    // 两种尺度
    static int VIEW_SIZE_A = 1500;  // 大比例尺（特写）
    static int VIEW_SIZE_B = 4000;  // 小比例尺（远景）

    final int w, h;
    final float[][] height;
    final float[][] discharge;
    final float[][] dischargeTrack;
    final float[][] mx, my, mxTrack, myTrack;
    final StandalonePreview terrain;
    final long seed;
    final Random rng;
    final int viewSize;

    public SimpleHydrologyV23(int seed, int viewSize) {
        this.seed = seed;
        this.viewSize = viewSize;
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
        float scale = (float)viewSize / Math.max(w, h);
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
            vel[0] += factor * mdx; vel[1] += factor * mdy;
        }

        vlen = (float)Math.sqrt(vel[0]*vel[0] + vel[1]*vel[1]);
        if (vlen > 0) {
            vel[0] = (vel[0] / vlen) * (float)Math.sqrt(2.0f);
            vel[1] = (vel[1] / vlen) * (float)Math.sqrt(2.0f);
        }

        pos[0] += vel[0]; pos[1] += vel[1];
        if (pos[0] < 0 || pos[0] >= w || pos[1] < 0 || pos[1] >= h) return false;

        dischargeTrack[iy][ix] += vol;
        mxTrack[iy][ix] += vol * vel[0]; myTrack[iy][ix] += vol * vel[1];

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
        sediment /= (1.0f - EVAP); vol *= (1.0f - EVAP);
        if (pos[0] < 0 || pos[0] >= w || pos[1] < 0 || pos[1] >= h) { vol = 0; return false; }
        cascade(ix, iy);
        volSedAge[0] = vol; volSedAge[1] = age + 1; volSedAge[2] = sediment;
        return true;
    }

    public void simulate() {
        for (int iter = 0; iter < ITERATIONS; iter++) {
            for (int y = 0; y < h; y++) {
                Arrays.fill(dischargeTrack[y], 0);
                Arrays.fill(mxTrack[y], 0); Arrays.fill(myTrack[y], 0);
            }
            for (int i = 0; i < DROPS_PER_ITER; i++) {
                float px = rng.nextFloat() * w, py = rng.nextFloat() * h;
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
    }

    float maxDischarge() {
        float maxD = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                maxD = Math.max(maxD, discharge[y][x]);
        return maxD;
    }

    // 渲染此实例到目标图片的 (offsetX, offsetY) 位置
    void renderOnto(BufferedImage img, int offsetX, int offsetY, boolean showLabel) {
        // 收集统计数据
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

        System.out.println("  VIEW_SIZE="+viewSize+" maxD="+fmt1(maxD)+" p95="+fmt1(p95)+" p98="+fmt1(p98)+" p99="+fmt1(p99)+" p995="+fmt1(p995));

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

        int sz = w; // 512
        int sz2 = sz * 2; // 1024
        int gap = 4;

        float overviewScale = 0.35f;
        int ovSize = (int)(sz * overviewScale);
        // ovOffset 相对于面板右上区域 (offsetX+sz, offsetY)
        int ovOffsetX = (sz - ovSize) / 2;
        int ovOffsetY = (sz - ovSize) / 2;

        // 先填充整个方块为黑色
        java.awt.Graphics gfx = img.getGraphics();
        gfx.setColor(new Color(0x000000));
        gfx.fillRect(offsetX, offsetY, sz2, sz2);
        gfx.dispose();

        for (int py = 0; py < sz; py++) {
            for (int px = 0; px < sz; px++) {
                float hVal = height[py][px];
                float d = blurD[py][px];
                float rawD = discharge[py][px];

                // 面板1: 地形+河流
                int col1;
                if (hVal < SEA_LEVEL) col1 = 0x1a3a7a;
                else col1 = terrainColor(hVal);
                if (d > p98) {
                    float t = (float)Math.log1p(d - p98) / (float)Math.log1p(maxD - p98 + 1);
                    t = Math.max(0, Math.min(1, t));
                    if (d > p995) col1 = rgb((int)(10+t*20), (int)(30+t*60), (int)(150+t*105));
                    else if (d > p99) col1 = rgb((int)(5+t*15), (int)(20+t*50), (int)(100+t*80));
                    else col1 = rgb((int)(5+t*10), (int)(15+t*30), (int)(70+t*60));
                }
                img.setRGB(offsetX + px, offsetY + py, col1);

                // 面板2: 河流骨架
                int col2 = 0x0a0a12;
                if (rawD > p98) {
                    float t = (float)Math.log1p(rawD - p98) / (float)Math.log1p(maxD - p98 + 1);
                    t = Math.max(0, Math.min(1, t));
                    if (rawD > p995) col2 = rgb((int)(30+t*40), (int)(80+t*100), (int)(200+t*55));
                    else if (rawD > p99) col2 = rgb(20, (int)(60+t*40), (int)(160+t*60));
                    else col2 = rgb(10, (int)(40+t*30), (int)(100+t*50));
                } else if (hVal < SEA_LEVEL) col2 = 0x0d1b2a;
                img.setRGB(offsetX + px, offsetY + py + sz, col2);

                // 面板4: 热力图
                float v = (float)Math.sqrt(rawD / (maxD + 0.001f));
                img.setRGB(offsetX + px + sz, offsetY + py + sz, Color.HSBtoRGB(0.6f, 1.0f - v * 0.4f, v));
            }
        }

        // 面板3: 小比例概览
        gfx = img.getGraphics();
        gfx.setColor(new Color(0x050510));
        gfx.fillRect(offsetX + sz, offsetY, sz, sz);
        gfx.dispose();

        for (int py = 0; py < ovSize; py++) {
            for (int px = 0; px < ovSize; px++) {
                int sx = (int)(px / overviewScale);
                int sy = (int)(py / overviewScale);
                if (sx >= w || sy >= h) continue;
                float hVal = height[sy][sx], rawD = discharge[sy][sx];
                if (rawD > p95) {
                    float t = (float)Math.log1p(rawD - p95) / (float)Math.log1p(maxD - p95 + 1);
                    t = Math.max(0, Math.min(1, t));
                    int r, g, b;
                    if (rawD > p995) { r=(int)(30+t*40); g=(int)(80+t*100); b=(int)(200+t*55); }
                    else if (rawD > p99) { r=20; g=(int)(60+t*40); b=(int)(160+t*60); }
                    else { r=10; g=(int)(40+t*30); b=(int)(100+t*50); }
                    img.setRGB(offsetX + sz + ovOffsetX + px, offsetY + ovOffsetY + py, rgb(r,g,b));
                } else if (hVal < SEA_LEVEL) {
                    img.setRGB(offsetX + sz + ovOffsetX + px, offsetY + ovOffsetY + py, 0x0d1b2a);
                }
            }
        }

        // 边框 + 文字
        java.awt.Graphics g = img.getGraphics();
        g.setColor(new Color(100, 120, 180));
        g.drawRect(offsetX + sz + ovOffsetX, offsetY + ovOffsetY, ovSize, ovSize);
        if (showLabel) {
            g.setColor(java.awt.Color.WHITE);
            g.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 13));
            g.drawString("VIEW_SIZE="+viewSize, offsetX + 10, offsetY + 16);
            g.drawString("地形+河流", offsetX + 10, offsetY + sz - 8);
            g.drawString("河流骨架", offsetX + 10, offsetY + sz*2 - 8);
            g.drawString("小比例概览", offsetX + sz + 10, offsetY + sz - 8);
            g.drawString("热力图", offsetX + sz + 10, offsetY + sz*2 - 8);
        }
        g.dispose();
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
        float result = 1.0f - t*(0.3480242f + t*(-0.0958798f + t*0.7478556f))*(float)Math.exp(-a*a);
        return x >= 0 ? result : -result;
    }

    static int rgb(int r, int g, int b) { return (r<<16)|(g<<8)|b; }
    static String fmt1(float v) { return String.format("%.1f", v); }

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        System.out.println("=== V23 双尺度四面板对比拼图 ===");

        // 生成尺度A（大比例尺，细节丰富）
        System.out.println("渲染 VIEW_SIZE=" + VIEW_SIZE_A);
        SimpleHydrologyV23 simA = new SimpleHydrologyV23(seed, VIEW_SIZE_A);
        simA.simulate();
        BufferedImage img = new BufferedImage(2048, 1024, BufferedImage.TYPE_INT_RGB);

        // 尺度A渲染到左半（0, 0），带标签
        simA.renderOnto(img, 0, 0, true);

        // 生成尺度B（小比例尺，视野广阔）
        System.out.println("渲染 VIEW_SIZE=" + VIEW_SIZE_B);
        SimpleHydrologyV23 simB = new SimpleHydrologyV23(seed, VIEW_SIZE_B);
        simB.simulate();

        // 尺度B渲染到右半（1024, 0），不带标签
        simB.renderOnto(img, 1024, 0, false);

        // 分隔线
        for (int py = 0; py < 1024; py++)
            img.setRGB(1023, py, 0xFFFFFF);

        ImageIO.write(img, "png", new File("output/v23_s" + seed + ".png"));
        System.out.println("Saved: output/v23_s" + seed + ".png");
    }
}
