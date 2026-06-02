package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * BrushHydroSim V6 - 保留 SimpleHydrology 粒子侵蚀 + 体积笔刷雕刻 + 多面板显示
 * 
 * 不再使用 D8 骨架或走廊约束。
 * 纯粒子侵蚀 + 体积笔刷 -> 自然雕刻出宽阔河道。
 */
public class BrushHydroSimV6 {

    static int MAP_SIZE = 512;
    static int ITERATIONS = 150;
    static int DROPS_PER_ITER = 4000;
    static float LRATE = 0.1f;
    static float GRAVITY = 1.2f;
    static float MOMENTUM = 0.5f;
    static float EVAP = 0.001f;
    static float DEPOSITION = 0.1f;
    static float ENTRAINMENT = 8.0f;
    static float MIN_VOL = 0.01f;
    static float MAX_AGE = 500;
    static float SEA_LEVEL = 0.35f;
    static int VIEW_SIZE = 6000;

    // 笔刷参数 — 调小一点，保证线条清晰
    static float BASE_RADIUS = 0.3f;
    static float VOL_TO_RADIUS = 0.4f;
    static float MAX_RADIUS = 5.0f;

    final int w, h;
    final float[][] height;
    final float[][] heightOrig;
    final float[][] heightOcean;
    final float[][] discharge;
    final float[][] dischargeTrack;
    final float[][] mx, my;
    final float[][] mxTrack, myTrack;

    final StandalonePreview terrain;
    final long seed;
    final Random rng;

    public BrushHydroSimV6(int seed) {
        this.seed = seed;
        this.terrain = new StandalonePreview(seed);
        this.rng = new Random(seed + 9999);
        this.w = MAP_SIZE;
        this.h = MAP_SIZE;
        this.height = new float[h][w];
        this.heightOrig = new float[h][w];
        this.heightOcean = new float[h][w];
        this.discharge = new float[h][w];
        this.dischargeTrack = new float[h][w];
        this.mx = new float[h][w];
        this.my = new float[h][w];
        this.mxTrack = new float[h][w];
        this.myTrack = new float[h][w];
    }

    void initHeightmap() {
        float scale = (float) VIEW_SIZE / Math.max(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float wx = (x - w / 2f) * scale;
                float wz = (y - h / 2f) * scale;
                float hPure = terrain.computeHeightPure(wx, wz);
                heightOrig[y][x] = hPure;
                heightOcean[y][x] = terrain.computeHeightWithOcean(wx, wz, hPure);
                height[y][x] = hPure;
            }
        }
    }

    float getH(int x, int y) {
        if (x < 0 || x >= w || y < 0 || y >= h) return 0;
        return height[y][x];
    }

    float[] volumeNormal(float fx, float fy, float radius) {
        int ix = (int) fx, iy = (int) fy;
        int r = Math.max(1, (int) radius);
        float gx = getH(ix + r, iy) - getH(ix - r, iy);
        float gy = getH(ix, iy + r) - getH(ix, iy - r);
        float len = (float) Math.sqrt(gx * gx + gy * gy + 0.0001f);
        return new float[]{-gx / len, -gy / len};
    }

    void applyBrushErosion(int cx, int cy, float radius, float amount) {
        int r = (int) Math.ceil(radius);
        if (r <= 1) {
            height[cy][cx] -= amount;
            return;
        }
        float weightSum = 0;
        float[][] wgt = new float[2 * r + 1][2 * r + 1];
        float rad2 = radius * radius;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                int nx = cx + dx, ny = cy + dy;
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                float dist2 = dx * dx + dy * dy;
                if (dist2 > rad2) continue;
                float t = (float) Math.sqrt(dist2) / radius;
                float wval = 1.0f - t * t * (3 - 2 * t);
                wgt[dy + r][dx + r] = wval;
                weightSum += wval;
            }
        }
        if (weightSum <= 0) return;
        for (int dy = -r; dy <= r; dy++) {
            int ny = cy + dy;
            if (ny < 0 || ny >= h) continue;
            for (int dx = -r; dx <= r; dx++) {
                if (wgt[dy + r][dx + r] == 0) continue;
                int nx = cx + dx;
                if (nx < 0 || nx >= w) continue;
                float local = amount * (wgt[dy + r][dx + r] / weightSum);
                height[ny][nx] -= local;
            }
        }
    }

    boolean descend(float[] pos, float[] vel, float[] vsa) {
        int ix = (int) pos[0], iy = (int) pos[1];
        if (ix < 0 || ix >= w || iy < 0 || iy >= h) return false;

        float vol = vsa[0];
        float age = vsa[1];
        float sediment = vsa[2];

        float localDischarge = discharge[iy][ix];
        float radius = BASE_RADIUS + (float) Math.sqrt(localDischarge + vol) * VOL_TO_RADIUS;
        radius = Math.min(radius, MAX_RADIUS);

        if (age > MAX_AGE || vol < MIN_VOL || height[iy][ix] < SEA_LEVEL) {
            applyBrushErosion(ix, iy, radius, -sediment);
            return false;
        }

        float[] n = volumeNormal(pos[0], pos[1], radius);
        vel[0] += GRAVITY * n[0] / vol;
        vel[1] += GRAVITY * n[1] / vol;

        float mdx = mx[iy][ix], mdy = my[iy][ix];
        float mlen = (float) Math.sqrt(mdx * mdx + mdy * mdy);
        float vlen = (float) Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1]);
        if (mlen > 0 && vlen > 0) {
            float dot = (vel[0] * mdx + vel[1] * mdy) / (vlen * mlen);
            float factor = MOMENTUM * dot / (vol + localDischarge + 1f);
            vel[0] += factor * mdx;
            vel[1] += factor * mdy;
        }

        vlen = (float) Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1]);
        if (vlen > 0) {
            float step = Math.max(1.414f, radius * 0.5f);
            vel[0] = (vel[0] / vlen) * step;
            vel[1] = (vel[1] / vlen) * step;
        }

        pos[0] += vel[0];
        pos[1] += vel[1];
        if (pos[0] < 0 || pos[0] >= w || pos[1] < 0 || pos[1] >= h) return false;

        int nix = (int) pos[0], niy = (int) pos[1];
        dischargeTrack[niy][nix] += vol;
        mxTrack[niy][nix] += vol * vel[0];
        myTrack[niy][nix] += vol * vel[1];

        float hHere = height[iy][ix];
        float hThere;
        if (pos[0] < 0 || pos[0] >= w || pos[1] < 0 || pos[1] >= h) {
            hThere = hHere - 0.002f;
        } else {
            int tx = (int) pos[0], ty = (int) pos[1];
            if (tx >= w - 1 || ty >= h - 1) hThere = hHere - 0.002f;
            else {
                float dx = pos[0] - tx, dy = pos[1] - ty;
                hThere = height[ty][tx] * (1 - dx) * (1 - dy) + height[ty][tx + 1] * dx * (1 - dy)
                    + height[ty + 1][tx] * (1 - dx) * dy + height[ty + 1][tx + 1] * dx * dy;
            }
        }

        float nodeDischarge = erf(0.4f * localDischarge);
        float cEq = (1.0f + ENTRAINMENT * nodeDischarge) * (hHere - hThere);
        if (cEq < 0) cEq = 0;
        float cdiff = cEq - sediment;
        float amount = DEPOSITION * cdiff;
        sediment += amount;

        applyBrushErosion(ix, iy, radius, amount);

        sediment = Math.max(-10.0f, Math.min(10.0f, sediment));
        sediment /= (1.0f - EVAP);
        vol *= (1.0f - EVAP);

        vsa[0] = vol;
        vsa[1] = age + 1;
        vsa[2] = sediment;
        return true;
    }

    void simulate() {
        System.out.println("=== BrushHydroSim V6 (体积笔刷侵蚀) ===");
        long t0 = System.nanoTime();
        initHeightmap();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            for (int y = 0; y < h; y++) {
                Arrays.fill(dischargeTrack[y], 0);
                Arrays.fill(mxTrack[y], 0);
                Arrays.fill(myTrack[y], 0);
            }

            for (int i = 0; i < DROPS_PER_ITER; i++) {
                float px = 2 + rng.nextFloat() * (w - 4);
                float py = 2 + rng.nextFloat() * (h - 4);
                if (height[(int) py][(int) px] < SEA_LEVEL + 0.02f) continue;

                float[] pos = {px, py};
                float[] vel = {0, 0};
                float[] vsa = {1.0f, 0, 0.0f};

                while (descend(pos, vel, vsa)) {
                }
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
                System.out.println("  Iter " + (iter + 1) + "/" + ITERATIONS + "  maxD=" + fmt1(maxD));
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
            System.out.println("  p90=" + fmt1(vals.get((int) (n * 0.9))) + " p95=" + fmt1(vals.get((int) (n * 0.95)))
                + " p98=" + fmt1(vals.get((int) (n * 0.98))) + " p99=" + fmt1(vals.get((int) (n * 0.99))));
        }
    }

    void renderAndSave(String filePath) throws Exception {
        simulate();

        // ===== 多面板布局 =====
        int sz = w;
        int gap = 8;
        int panelW = sz;
        int imgW = panelW * 4 + gap * 3;
        int imgH = sz + 40;
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2 = (java.awt.Graphics2D) img.getGraphics();
        g2.fillRect(0, 0, imgW, imgH);
        g2.dispose();

        // 计算 discharge 百分位
        List<Float> allD = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (discharge[y][x] > 0) allD.add(discharge[y][x]);
        Collections.sort(allD);
        int n = allD.size();
        float p95 = n > 0 ? allD.get((int) (n * 0.95f)) : 0;
        float p98 = n > 0 ? allD.get((int) (n * 0.98f)) : 0;
        float p99 = n > 0 ? allD.get((int) (n * 0.99f)) : 0;
        float maxD = n > 0 ? allD.get(n - 1) : 0;

        // 3x3 box blur 扩散 discharge，让河道显示更宽
        float[][] blurD = new float[h][w];
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                float sum = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++)
                        sum += discharge[y + dy][x + dx];
                blurD[y][x] = sum / 9f;
            }

        // 光照方向: 左上角 (-0.8, -0.8, 1.0)
        float[] lDir = {-0.8f, -0.8f, 1.0f};
        float lLen = (float) Math.sqrt(lDir[0] * lDir[0] + lDir[1] * lDir[1] + lDir[2] * lDir[2]);
        lDir[0] /= lLen;
        lDir[1] /= lLen;
        lDir[2] /= lLen;

        for (int py = 0; py < sz; py++) {
            float wz = (py - h / 2f) * ((float) VIEW_SIZE / h);
            for (int px = 0; px < sz; px++) {
                float wx = (px - w / 2f) * ((float) VIEW_SIZE / w);

                float hEroded = height[py][px];
                float hOrig = heightOrig[py][px];
                float hOcean = heightOcean[py][px];
                float c = terrain.sampleContinentRaw(wx, wz);
                float d = blurD[py][px];

                // ===== 第1列：侵蚀后纯地形（光照）=====
                // 使用 -gx, -gy 获得朝上的法线
                float gx1 = (px < w - 1 && px > 0) ? (height[py][px + 1] - height[py][px - 1]) : 0;
                float gy1 = (py < h - 1 && py > 0) ? (height[py + 1][px] - height[py - 1][px]) : 0;
                float nz1 = 0.08f; // 调大 nz 会让阴影更柔和，山峰更平滑
                float nLen1 = (float) Math.sqrt(gx1 * gx1 + gy1 * gy1 + nz1 * nz1);
                float nx1 = -gx1 / nLen1, ny1 = -gy1 / nLen1, nnz1 = nz1 / nLen1;
                
                // 漫反射计算
                float diff1 = nx1 * lDir[0] + ny1 * lDir[1] + nnz1 * lDir[2];
                float shadow = Math.max(0.4f, diff1); // 环境光 0.4
                
                int c1 = terrainColorSmooth(hEroded);
                int r1 = (int) (((c1 >> 16) & 0xFF) * shadow);
                int g1 = (int) (((c1 >> 8) & 0xFF) * shadow);
                int b1 = (int) ((c1 & 0xFF) * shadow);
                img.setRGB(px, py, rgb(Math.min(255, r1), Math.min(255, g1), Math.min(255, b1)));

                // ===== 第2列：侵蚀后 + 河流叠加 =====
                int c2 = terrainColorSmooth(hEroded);
                int r2 = (c2 >> 16) & 0xFF, g2r = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
                if (d > p95) {
                    float t = (float) Math.log1p(d - p95) / (float) Math.log1p(maxD - p95 + 1);
                    t = Math.max(0, Math.min(1, t));
                    if (d > p99) {
                        r2 = (int) (10 + t * 20);
                        g2r = (int) (30 + t * 90);
                        b2 = (int) (120 + t * 135);
                    } else if (d > p98) {
                        r2 = (int) (5 + t * 15);
                        g2r = (int) (20 + t * 70);
                        b2 = (int) (80 + t * 100);
                    } else {
                        r2 = (int) (5 + t * 10);
                        g2r = (int) (15 + t * 50);
                        b2 = (int) (60 + t * 70);
                    }
                }
                r2 = (int) (r2 * shadow);
                g2r = (int) (g2r * shadow);
                b2 = (int) (b2 * shadow);
                img.setRGB(panelW + gap + px, py, rgb(Math.min(255, r2), Math.min(255, g2r), Math.min(255, b2)));

                // ===== 第3列：侵蚀后海洋地形 + 河流 =====
                int c3;
                if (c < 0) {
                    c3 = oceanColor(hOcean);
                } else {
                    c3 = terrainColorSmooth(hEroded);
                }
                int r3 = (c3 >> 16) & 0xFF, g3 = (c3 >> 8) & 0xFF, b3 = c3 & 0xFF;
                if (c >= 0 && d > p95) {
                    float t = (float) Math.log1p(d - p95) / (float) Math.log1p(maxD - p95 + 1);
                    t = Math.max(0, Math.min(1, t));
                    if (d > p99) {
                        r3 = (int) (10 + t * 20);
                        g3 = (int) (30 + t * 90);
                        b3 = (int) (120 + t * 135);
                    } else if (d > p98) {
                        r3 = (int) (5 + t * 15);
                        g3 = (int) (20 + t * 70);
                        b3 = (int) (80 + t * 100);
                    } else {
                        r3 = (int) (5 + t * 10);
                        g3 = (int) (15 + t * 50);
                        b3 = (int) (60 + t * 70);
                    }
                }
                r3 = (int) (r3 * shadow);
                g3 = (int) (g3 * shadow);
                b3 = (int) (b3 * shadow);
                img.setRGB((panelW + gap) * 2 + px, py, rgb(Math.min(255, r3), Math.min(255, g3), Math.min(255, b3)));

                // ===== 第4列：discharge 热力图 =====
                float hmD = d;
                if (hmD < p95) {
                    img.setRGB((panelW + gap) * 3 + px, py, rgb(20, 20, 20));
                } else {
                    float t = (float) Math.log1p(hmD - p95) / (float) Math.log1p(maxD - p95 + 1);
                    t = Math.max(0, Math.min(1, t));
                    img.setRGB((panelW + gap) * 3 + px, py, heatmapColor(t));
                }
            }
        }

        drawLabel(img, "侵蚀后纯地形(光照)", 10, 2, 0xFFFFFF);
        drawLabel(img, "侵蚀后+河流", panelW + gap + 10, 2, 0xFFFFFF);
        drawLabel(img, "海洋地形+河流", (panelW + gap) * 2 + 10, 2, 0xFFFFFF);
        drawLabel(img, "Discharge热力图", (panelW + gap) * 3 + 10, 2, 0xFFFFFF);
        String info = "Seed=" + seed + " V6 Iter=" + ITERATIONS + " Drops=" + DROPS_PER_ITER + " Radius=" + BASE_RADIUS +
            "~" + MAX_RADIUS + " ENT=" + ENTRAINMENT + " DEP=" + DEPOSITION;
        drawLabel(img, info, 10, sz + 2, 0xAAAAAA);

        ImageIO.write(img, "png", new File(filePath));
        System.out.println("Saved: " + filePath);
    }

    int oceanColor(float h) {
        float depth = (SEA_LEVEL - h) * 3;
        int b = 80 + (int) (depth * 80);
        return rgb(10, 40, Math.min(255, b));
    }

    int terrainColorSmooth(float h) {
        h = Math.max(0, Math.min(1, h));
        float[][] stops = {
            {0.00f, 5, 20, 60},
            {0.25f, 20, 60, 120},
            {0.35f, 180, 170, 80},
            {0.45f, 100, 150, 50},
            {0.55f, 60, 130, 50},
            {0.65f, 140, 150, 70},
            {0.75f, 170, 160, 120},
            {0.85f, 190, 185, 170},
            {1.00f, 230, 230, 240}
        };
        int idx = 0;
        for (int i = 0; i < stops.length - 1; i++) {
            if (h >= stops[i][0] && h <= stops[i + 1][0]) {
                idx = i;
                break;
            }
            if (i == stops.length - 2) idx = stops.length - 2;
        }
        float t = (h - stops[idx][0]) / (stops[idx + 1][0] - stops[idx][0] + 0.0001f);
        t = t * t * (3f - 2f * t);
        int r = (int) (stops[idx][1] + t * (stops[idx + 1][1] - stops[idx][1]));
        int g = (int) (stops[idx][2] + t * (stops[idx + 1][2] - stops[idx][2]));
        int b = (int) (stops[idx][3] + t * (stops[idx + 1][3] - stops[idx][3]));
        return rgb(r, g, b);
    }

    int heatmapColor(float t) {
        t = Math.max(0, Math.min(1, t));
        if (t < 0.2f) return rgb(0, 0, (int) (t * 5 * 255));
        else if (t < 0.4f) return rgb(0, (int) ((t - 0.2f) * 5 * 255), 255);
        else if (t < 0.6f) return rgb(0, 255, (int) ((0.6f - t) * 5 * 255));
        else if (t < 0.8f) return rgb((int) ((t - 0.6f) * 5 * 255), 255, 0);
        else return rgb(255, (int) ((1 - t) * 5 * 255), 0);
    }

    void drawLabel(BufferedImage img, String text, int x, int y, int color) {
        java.awt.Graphics g = img.getGraphics();
        g.setColor(new java.awt.Color(color));
        g.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
        g.drawString(text, x, y + 12);
        g.dispose();
    }

    static float erf(float x) {
        float a = Math.abs(x);
        float t = 1.0f / (1.0f + 0.47047f * a);
        float result = 1.0f - t * (0.3480242f + t * (-0.0958798f + t * 0.7478556f)) * (float) Math.exp(-a * a);
        return x >= 0 ? result : -result;
    }

    static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }
    static String fmt1(float v) { return String.format("%.1f", v); }
    static String fmt0(double v) { return String.format("%.0f", v); }

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        String tag = "v1";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed":
                    seed = Integer.parseInt(args[++i]);
                    break;
                case "--tag":
                    tag = args[++i];
                    break;
            }
        }
        BrushHydroSimV6 sim = new BrushHydroSimV6(seed);
        sim.renderAndSave("output/brush_v6_s" + seed + "_" + tag + ".png");
    }
}
