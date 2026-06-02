package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrology 风格粒子模拟 + TerraForged 自适应笔刷
 * 
 * 核心改进：
 * 1. 减少粒子数（5000），但记录完整路径
 * 2. 只保留长路径（>30步）作为河流候选
 * 3. 沿路径用自适应笔刷画连续线（不是 discharge 距离场）
 * 4. 笔刷宽度 = 路径长度 × 系数（越长 = 越宽 = 大河）
 */
public class HydrologySimulator {

    // ============ 参数 ============
    static int MAP_SIZE = 400;
    static int NUM_PARTICLES = 5000;      // 少量粒子，但记录完整路径
    static float GRAVITY = 4.0f;          // 更强重力 = 更快流动
    static float MOMENTUM = 0.8f;         // 动量保留（产生蜿蜒）
    static float EVAP_RATE = 0.001f;      // 更低蒸发 = 更长路径
    static float MIN_VOL = 0.01f;
    static float MAX_SPEED = 12.0f;
    static float VOLUME = 1.0f;

    static float SEA_LEVEL = 0.35f;
    static float SEA_NORM = 0.35f;
    static int VIEW_SIZE = 6000;

    // 笔刷参数
    static float WIDTH_BASE = 3f;         // 基础宽度（像素）
    static float WIDTH_PER_STEP = 0.15f;  // 每步增加的宽度
    static float WIDTH_CAP = 40f;         // 最大宽度
    static float VALLEY_RATIO = 2.0f;
    static float BANK_RATIO = 1.4f;
    static int MIN_PATH_LENGTH = 100;     // 最小路径长度才渲染（只保留长河）
    // =============================

    static final int OUTPUT_SIZE = 2048;

    final int w, h;
    final float[][] height;
    final float[][] smoothH;

    final StandalonePreview terrain;
    final long seed;
    final Random rng;

    final List<ParticlePath> allPaths = new ArrayList<>();

    public HydrologySimulator(int seed, int mapSize) {
        this.seed = seed;
        this.terrain = new StandalonePreview(seed);
        this.rng = new Random(seed + 8888);
        this.w = mapSize;
        this.h = mapSize;
        this.height = new float[h][w];
        this.smoothH = new float[h][w];
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
        // 强平滑（11x11 高斯模糊）消除局部洼地
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0; float wsum = 0;
                for (int dy = -5; dy <= 5; dy++) {
                    for (int dx = -5; dx <= 5; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                            float weight = (float)Math.exp(-(dx*dx + dy*dy) / 8f);
                            sum += height[ny][nx] * weight;
                            wsum += weight;
                        }
                    }
                }
                smoothH[y][x] = sum / wsum;
            }
        }
    }

    float sampleHeight(float fx, float fy) {
        int x = (int)fx, y = (int)fy;
        if (x < 0 || x >= w-1 || y < 0 || y >= h-1) return SEA_LEVEL;
        float dx = fx - x, dy = fy - y;
        float h00 = smoothH[y][x], h10 = smoothH[y][x+1];
        float h01 = smoothH[y+1][x], h11 = smoothH[y+1][x+1];
        return h00*(1-dx)*(1-dy) + h10*dx*(1-dy) + h01*(1-dx)*dy + h11*dx*dy;
    }

    void simulate() {
        System.out.println("=== SimpleHydrology + Adaptive Brush ===");
        System.out.println("Map: " + w + "x" + h + "  Particles: " + NUM_PARTICLES);

        long t0 = System.nanoTime();

        for (int i = 0; i < NUM_PARTICLES; i++) {
            // 只在高地撒粒子
            float fx, fy;
            int attempts = 0;
            do {
                fx = 5 + rng.nextFloat() * (w - 10);
                fy = 5 + rng.nextFloat() * (h - 10);
                attempts++;
            } while (sampleHeight(fx, fy) < SEA_LEVEL + 0.1f && attempts < 30);
            if (sampleHeight(fx, fy) < SEA_LEVEL + 0.1f) continue;

            float px = fx, py = fy;
            float vx = 0, vy = 0;
            float volume = VOLUME;
            int age = 0;

            List<PathPoint> path = new ArrayList<>();
            path.add(new PathPoint(px, py));

            while (volume > MIN_VOL && age < 1500) {
                // 计算梯度
                float gx = sampleHeight(px + 1.5f, py) - sampleHeight(px - 1.5f, py);
                float gy = sampleHeight(px, py + 1.5f) - sampleHeight(px, py - 1.5f);

                float gLen = (float)Math.sqrt(gx*gx + gy*gy);
                if (gLen > 0.0001f) {
                    // 重力 + 动量
                    vx = vx * MOMENTUM + GRAVITY * (-gx / gLen) * (1 - MOMENTUM);
                    vy = vy * MOMENTUM + GRAVITY * (-gy / gLen) * (1 - MOMENTUM);
                }

                // 限速
                float speed = (float)Math.sqrt(vx*vx + vy*vy);
                if (speed > MAX_SPEED) {
                    vx = vx / speed * MAX_SPEED;
                    vy = vy / speed * MAX_SPEED;
                }

                px += vx;
                py += vy;

                if (px < 0 || px >= w || py < 0 || py >= h) break;

                path.add(new PathPoint(px, py));

                volume *= (1 - EVAP_RATE);
                age++;

                // 进入海洋停止
                int ix = (int)px, iy = (int)py;
                if (ix >= 0 && ix < w && iy >= 0 && iy < h) {
                    if (height[iy][ix] < SEA_LEVEL - 0.02f) break;
                }
            }

            if (path.size() >= MIN_PATH_LENGTH) {
                allPaths.add(new ParticlePath(path));
            }
        }

        double totalMs = (System.nanoTime() - t0) / 1e6;
        System.out.println("Valid paths (len>=" + MIN_PATH_LENGTH + "): " + allPaths.size());
        System.out.println("Simulation time: " + fmt0(totalMs) + "ms");

        // 统计路径长度
        if (!allPaths.isEmpty()) {
            allPaths.sort((a, b) -> Integer.compare(b.points.size(), a.points.size()));
            System.out.println("Longest path: " + allPaths.get(0).points.size() + " steps");
            if (allPaths.size() > 1) {
                System.out.println("2nd longest: " + allPaths.get(1).points.size() + " steps");
            }
        }
    }

    // ============ 渲染：沿路径画自适应笔刷 ============
    void renderAndSave(String filePath) throws Exception {
        initHeightmap();
        simulate();

        if (allPaths.isEmpty()) {
            System.out.println("No valid paths!");
            return;
        }

        int sz = OUTPUT_SIZE;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_RGB);
        float scale = (float)VIEW_SIZE / Math.max(w, h);
        float renderScale = (float)VIEW_SIZE / sz;

        // 先画地形
        System.out.println("Rendering terrain...");
        for (int py = 0; py < sz; py++) {
            float wz = (py - sz/2f) * renderScale;
            for (int px = 0; px < sz; px++) {
                float wx = (px - sz/2f) * renderScale;
                float h = terrain.computeHeight(wx, wz);
                img.setRGB(px, py, getTerrainColor(h));
            }
        }

        // 按路径长度排序（先画大河）
        allPaths.sort((a, b) -> Integer.compare(b.points.size(), a.points.size()));

        System.out.println("Rendering " + allPaths.size() + " paths with adaptive brush...");

        // 只画前 N 条最长的路径（避免画面太乱）
        int maxPaths = Math.min(allPaths.size(), 200);
        for (int pi = 0; pi < maxPaths; pi++) {
            ParticlePath path = allPaths.get(pi);
            List<PathPoint> pts = path.points;

            // 路径越长 = 河越宽
            float pathLen = pts.size();
            float baseWidth = Math.min(WIDTH_CAP, WIDTH_BASE + pathLen * WIDTH_PER_STEP);

            // 沿路径画线段
            for (int i = 0; i < pts.size() - 1; i++) {
                PathPoint p1 = pts.get(i);
                PathPoint p2 = pts.get(i+1);

                // 世界坐标 → 像素坐标
                float wx1 = (p1.x - w/2f) * scale;
                float wz1 = (p1.y - h/2f) * scale;
                float wx2 = (p2.x - w/2f) * scale;
                float wz2 = (p2.y - h/2f) * scale;

                float px1 = wx1 / renderScale + sz/2f;
                float py1 = wz1 / renderScale + sz/2f;
                float px2 = wx2 / renderScale + sz/2f;
                float py2 = wz2 / renderScale + sz/2f;

                // 宽度随位置变化（上游窄、下游宽）
                float t1 = i / (float)pts.size();
                float t2 = (i+1) / (float)pts.size();
                float w1 = baseWidth * (0.5f + 0.5f * t1);  // 上游 0.5x，下游 1x
                float w2 = baseWidth * (0.5f + 0.5f * t2);

                drawBrushLine(img, px1, py1, px2, py2, w1, w2, sz);
            }
        }

        File f = new File(filePath);
        ImageIO.write(img, "png", f);
        System.out.println("Saved: " + f.getAbsolutePath());
    }

    void drawBrushLine(BufferedImage img, float x1, float y1, float x2, float y2,
                       float w1, float w2, int sz) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float)Math.sqrt(dx*dx + dy*dy);
        if (len < 0.3f) return;

        float step = 0.6f;
        int steps = (int)(len / step) + 1;

        for (int i = 0; i <= steps; i++) {
            float t = i / (float)steps;
            float cx = x1 + dx * t;
            float cy = y1 + dy * t;
            float w = w1 + t * (w2 - w1);

            float bedR = w * 0.4f;
            float bankR = w * 0.7f;
            float valleyR = w;
            int r = (int)(valleyR + 1);

            for (int dy2 = -r; dy2 <= r; dy2++) {
                for (int dx2 = -r; dx2 <= r; dx2++) {
                    int px = (int)(cx + dx2);
                    int py = (int)(cy + dy2);
                    if (px < 0 || px >= sz || py < 0 || py >= sz) continue;

                    float dist = (float)Math.sqrt(dx2*dx2 + dy2*dy2);
                    if (dist > valleyR) continue;

                    int color;
                    if (dist <= bedR) {
                        float depth = 1f - dist / bedR;
                        int blue = 160 + (int)(depth * 95);
                        color = rgb(10, 40, Math.min(255, blue));
                    } else if (dist <= bankR) {
                        float ft = (dist - bedR) / (bankR - bedR);
                        ft = ft * ft * (3 - 2 * ft);
                        int r2 = (int)(30 + ft * 80);
                        int g = (int)(60 + ft * 40);
                        int b = (int)(200 - ft * 120);
                        color = rgb(r2, g, b);
                    } else {
                        float ft = (dist - bankR) / (valleyR - bankR);
                        ft = ft * ft * (3 - 2 * ft);
                        int br = (int)(110 + ft * 50);
                        color = rgb(br, (int)(80 + ft * 30), 45);
                    }
                    img.setRGB(px, py, color);
                }
            }
        }
    }

    int getTerrainColor(float h) {
        if (h < SEA_NORM - 0.05f) {
            float depth = (SEA_NORM - h) * 3;
            int b = 80 + (int)(depth * 80);
            return rgb(10, 40, Math.min(255, b));
        } else if (h < SEA_NORM) {
            return rgb(190, 180, 60);
        } else {
            float hn = (h - SEA_NORM) / (1f - SEA_NORM);
            int v = (int)(hn * 200) + 55;
            return rgb(v, v, v);
        }
    }

    static int rgb(int r, int g, int b) { return (r<<16) | (g<<8) | b; }
    static String fmt0(double v) { return String.format("%.0f", v); }

    // ============ 内部类 ============
    static class PathPoint {
        final float x, y;
        PathPoint(float x, float y) { this.x=x; this.y=y; }
    }

    static class ParticlePath {
        final List<PathPoint> points;
        ParticlePath(List<PathPoint> points) { this.points = points; }
    }

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        int mapSize = 400;
        int particles = 5000;
        String tag = "sh1";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed": seed = Integer.parseInt(args[++i]); break;
                case "--size": mapSize = Integer.parseInt(args[++i]); break;
                case "--particles": particles = Integer.parseInt(args[++i]); break;
                case "--tag": tag = args[++i]; break;
            }
        }
        NUM_PARTICLES = particles;
        MAP_SIZE = mapSize;

        HydrologySimulator sim = new HydrologySimulator(seed, mapSize);
        sim.renderAndSave("../output/hydro_s" + seed + "_" + tag + ".png");
    }
}
