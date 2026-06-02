package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrology 粒子系统 + TerraForged 渲染
 * 
 * 流程：
 * 1. 粒子法模拟：大量粒子从高地出发，带动量/惯性流动
 * 2. 生成 discharge 图（每个细胞的水流量）
 * 3. 提取高 discharge 细胞作为河流骨架
 * 4. 沿骨架用 TerraForged 距离场渲染（valley/bank/bed）
 */
public class HydroRiverVisualizer {

    // ============ 粒子参数 ============
    static int MAP_SIZE = 400;
    static int NUM_PARTICLES = 80000;      // 粒子数
    static float GRAVITY = 2.0f;           // 重力
    static float MOMENTUM = 0.85f;         // 动量保留（0~1，越大越蜿蜒）
    static float EVAP_RATE = 0.002f;       // 蒸发
    static float MIN_VOL = 0.02f;          // 最小水量
    static float MAX_AGE = 800;            // 最大年龄
    static float MAX_SPEED = 6.0f;         // 最大速度

    // ============ 河流提取参数 ============
    static float DISCHARGE_THRESHOLD = 60f;  // discharge > 此值才认为是河流
    static int MIN_BRANCH_LENGTH = 6;        // 最小分支长度（细胞数）

    // ============ 渲染参数 ============
    static float WIDTH_SCALE = 0.06f;        // 宽度 = discharge^0.5 * WIDTH_SCALE
    static float WIDTH_EXP = 0.45f;          // 宽度指数
    static float WIDTH_CAP = 60f;            // 最大宽度
    static float VALLEY_RATIO = 3.0f;        // 河谷 = 河床 * VALLEY_RATIO
    static float BANK_RATIO = 1.8f;          // 河岸 = 河床 * BANK_RATIO

    static float SEA_LEVEL = 0.35f;
    static int VIEW_SIZE = 6000;
    static final int OUTPUT_SIZE = 2048;
    // =============================

    final int w, h;
    final float[][] height;          // 原始地形
    final float[][] smoothH;         // 平滑地形
    final float[][] discharge;       // 累积流量
    final float[][] momentumX;       // X 方向动量
    final float[][] momentumY;       // Y 方向动量

    final StandalonePreview terrain;
    final long seed;
    final Random rng;

    public HydroRiverVisualizer(int seed, int mapSize) {
        this.seed = seed;
        this.terrain = new StandalonePreview(seed);
        this.rng = new Random(seed + 7777);
        this.w = mapSize;
        this.h = mapSize;
        this.height = new float[h][w];
        this.smoothH = new float[h][w];
        this.discharge = new float[h][w];
        this.momentumX = new float[h][w];
        this.momentumY = new float[h][w];
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
        // 5x5 高斯平滑消除局部洼地
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0; float wsum = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                            float wgt = (float)Math.exp(-(dx*dx + dy*dy) / 3f);
                            sum += height[ny][nx] * wgt;
                            wsum += wgt;
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

    // ============ SimpleHydrology 粒子模拟 ============
    void simulate() {
        System.out.println("=== HydroRiver Visualizer ===");
        System.out.println("Map: " + w + "x" + h + "  Particles: " + NUM_PARTICLES);

        long t0 = System.nanoTime();

        for (int i = 0; i < NUM_PARTICLES; i++) {
            // 在高地撒粒子
            float fx, fy;
            int attempts = 0;
            do {
                fx = 2 + rng.nextFloat() * (w - 4);
                fy = 2 + rng.nextFloat() * (h - 4);
                attempts++;
            } while (sampleHeight(fx, fy) < SEA_LEVEL + 0.08f && attempts < 30);
            if (sampleHeight(fx, fy) < SEA_LEVEL + 0.08f) continue;

            float px = fx, py = fy;
            float vx = 0, vy = 0;
            float volume = 1.0f;
            int age = 0;

            while (volume > MIN_VOL && age < MAX_AGE) {
                // === SimpleHydrology 核心：计算地形法线 =====
                float gx = sampleHeight(px + 1.5f, py) - sampleHeight(px - 1.5f, py);
                float gy = sampleHeight(px, py + 1.5f) - sampleHeight(px, py - 1.5f);
                float gLen = (float)Math.sqrt(gx*gx + gy*gy);

                // === 重力 + 动量（SimpleHydrology 核心）=====
                if (gLen > 0.0001f) {
                    // 重力沿法线方向
                    float gravX = -gx / gLen;
                    float gravY = -gy / gLen;
                    vx = vx * MOMENTUM + GRAVITY * gravX * (1 - MOMENTUM) / volume;
                    vy = vy * MOMENTUM + GRAVITY * gravY * (1 - MOMENTUM) / volume;
                }

                // === 动量地图反馈（来自之前粒子的动量）=====
                int ix = (int)px, iy = (int)py;
                if (ix >= 1 && ix < w-1 && iy >= 1 && iy < h-1) {
                    float mx = momentumX[iy][ix];
                    float my = momentumY[iy][ix];
                    float mLen = (float)Math.sqrt(mx*mx + my*my);
                    if (mLen > 0.001f) {
                        vx += mx * 0.1f / mLen;
                        vy += my * 0.1f / mLen;
                    }
                }

                // 限速
                float speed = (float)Math.sqrt(vx*vx + vy*vy);
                if (speed > MAX_SPEED) {
                    vx = vx / speed * MAX_SPEED;
                    vy = vy / speed * MAX_SPEED;
                }

                // === 动态时间步（SimpleHydrology 风格）=====
                float step = 1.0f;
                if (speed > 0.5f) step = Math.min(2.0f, speed * 0.5f);

                px += vx * step;
                py += vy * step;

                if (px < 0 || px >= w || py < 0 || py >= h) break;

                // === 更新 discharge 和动量地图 ==========
                int nix = (int)px, niy = (int)py;
                if (nix >= 0 && nix < w && niy >= 0 && niy < h) {
                    discharge[niy][nix] += volume * step;
                    momentumX[niy][nix] += volume * vx * step;
                    momentumY[niy][nix] += volume * vy * step;
                }

                // 蒸发
                volume *= (1 - EVAP_RATE);
                age++;

                // 进入海洋停止
                if (nix >= 0 && nix < w && niy >= 0 && niy < h) {
                    if (height[niy][nix] < SEA_LEVEL - 0.02f) break;
                }
            }

            if (i % 20000 == 0 && i > 0) {
                System.out.println("  Particles: " + i + "/" + NUM_PARTICLES);
            }
        }

        // 统计 discharge
        float maxD = 0; int nonZero = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                maxD = Math.max(maxD, discharge[y][x]);
                if (discharge[y][x] > 0) nonZero++;
            }
        }
        System.out.println("Max discharge: " + fmt1(maxD) + "  Non-zero cells: " + nonZero);

        // 排序显示分布
        List<Float> vals = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (discharge[y][x] > 0) vals.add(discharge[y][x]);
        Collections.sort(vals);
        int n = vals.size();
        if (n > 0) {
            System.out.println("Discharge p50=" + fmt1(vals.get(n/2)) +
                " p90=" + fmt1(vals.get((int)(n*0.9))) +
                " p99=" + fmt1(vals.get((int)(n*0.99))));
        }

        double totalMs = (System.nanoTime() - t0) / 1e6;
        System.out.println("Simulation time: " + fmt0(totalMs) + "ms");
    }

    // ============ 从 discharge 图提取河流骨架 ============
    List<RiverSegment> extractRivers() {
        System.out.println("Extracting rivers (threshold=" + DISCHARGE_THRESHOLD + ")...");

        // 1. 标记高 discharge 细胞
        boolean[][] isRiver = new boolean[h][w];
        int riverCount = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                isRiver[y][x] = discharge[y][x] > DISCHARGE_THRESHOLD;
                if (isRiver[y][x]) riverCount++;
            }
        }
        System.out.println("River cells (discharge > " + DISCHARGE_THRESHOLD + "): " + riverCount);

        // 2. 连通区域分析
        boolean[][] visited = new boolean[h][w];
        List<List<int[]>> clusters = new ArrayList<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (isRiver[y][x] && !visited[y][x]) {
                    List<int[]> cluster = new ArrayList<>();
                    floodFill(x, y, isRiver, visited, cluster);
                    if (cluster.size() >= MIN_BRANCH_LENGTH) {
                        clusters.add(cluster);
                    }
                }
            }
        }
        System.out.println("Clusters: " + clusters.size());

        // 3. 每个连通区域提取中心线
        List<RiverSegment> segments = new ArrayList<>();
        for (List<int[]> cluster : clusters) {
            List<float[]> skeleton = extractSkeleton(cluster);
            if (skeleton != null && skeleton.size() >= 2) {
                // 转为线段
                for (int i = 0; i < skeleton.size() - 1; i++) {
                    float[] p1 = skeleton.get(i);
                    float[] p2 = skeleton.get(i+1);
                    float d1 = p1[2], d2 = p2[2];
                    // 世界坐标
                    float scale = (float)VIEW_SIZE / Math.max(w, h);
                    float wx1 = (p1[0] - w/2f) * scale;
                    float wz1 = (p1[1] - h/2f) * scale;
                    float wx2 = (p2[0] - w/2f) * scale;
                    float wz2 = (p2[1] - h/2f) * scale;

                    segments.add(new RiverSegment(wx1, wz1, wx2, wz2, d1, d2));
                }
            }
        }

        System.out.println("Total segments: " + segments.size());
        return segments;
    }

    void floodFill(int sx, int sy, boolean[][] isRiver, boolean[][] visited, List<int[]> cluster) {
        Queue<int[]> q = new LinkedList<>();
        q.add(new int[]{sx, sy});
        visited[sy][sx] = true;
        while (!q.isEmpty()) {
            int[] p = q.poll();
            cluster.add(p);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = p[0] + dx, ny = p[1] + dy;
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h
                        && isRiver[ny][nx] && !visited[ny][nx]) {
                        visited[ny][nx] = true;
                        q.add(new int[]{nx, ny});
                    }
                }
            }
        }
    }

    List<float[]> extractSkeleton(List<int[]> cluster) {
        if (cluster.size() < 3) return null;

        // 找两个端点（距离最远的两个点）
        int[] end1 = cluster.get(0), end2 = cluster.get(0);
        float maxDist = 0;
        for (int[] p1 : cluster) {
            for (int[] p2 : cluster) {
                float d = (p1[0]-p2[0])*(p1[0]-p2[0]) + (p1[1]-p2[1])*(p1[1]-p2[1]);
                if (d > maxDist) { maxDist = d; end1 = p1; end2 = p2; }
            }
        }

        // 沿最高 discharge 路径连接两端
        List<float[]> path = new ArrayList<>();
        int cx = end1[0], cy = end1[1];
        path.add(new float[]{cx, cy, discharge[cy][cx]});

        Set<String> used = new HashSet<>();
        used.add(cx + "," + cy);

        int maxSteps = cluster.size() * 2;
        for (int step = 0; step < maxSteps; step++) {
            if (cx == end2[0] && cy == end2[1]) break;

            float bestD = -1;
            int bestX = cx, bestY = cy;

            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = cx + dx, ny = cy + dy;
                    String key = nx + "," + ny;
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h
                        && !used.contains(key)
                        && discharge[ny][nx] > DISCHARGE_THRESHOLD * 0.5f) {
                        if (discharge[ny][nx] > bestD) {
                            bestD = discharge[ny][nx];
                            bestX = nx; bestY = ny;
                        }
                    }
                }
            }

            if (bestD < 0) break;
            cx = bestX; cy = bestY;
            used.add(cx + "," + cy);
            path.add(new float[]{cx, cy, discharge[cy][cx]});
        }

        return path.size() >= 2 ? path : null;
    }

    // ============ 快速渲染：直接采样 discharge 图 ============
    void renderAndSave(String filePath) throws Exception {
        long t0 = System.nanoTime();
        initHeightmap();
        simulate();

        int sz = OUTPUT_SIZE;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_RGB);
        float renderScale = (float)VIEW_SIZE / sz;
        float mapScale = (float)VIEW_SIZE / Math.max(w, h);

        // 找出最大 discharge 用于归一化
        float maxD = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                maxD = Math.max(maxD, discharge[y][x]);

        System.out.println("Rendering discharge map (max=" + fmt1(maxD) + ")...");

        // 直接渲染每像素：地形+discharge覆盖
        for (int py = 0; py < sz; py++) {
            float wz = (py - sz/2f) * renderScale;
            for (int px = 0; px < sz; px++) {
                float wx = (px - sz/2f) * renderScale;

                // 地形颜色
                float h = terrain.computeHeight(wx, wz);
                int color = getTerrainColor(h);

                // 采样 discharge
                float mx = wx / mapScale + w/2f;
                float my = wz / mapScale + h/2f;
                float d = sampleDischarge(mx, my);

                // 如果 discharge 够高，覆盖河流颜色
                if (d > DISCHARGE_THRESHOLD) {
                    float alpha = Math.min(1f, (float)Math.log1p(d - DISCHARGE_THRESHOLD) * 0.15f);
                    int r = (int)(20 + alpha * 10);
                    int g = (int)(60 + alpha * 20);
                    int b = (int)(200 + alpha * 55);
                    color = rgb(r, g, b);
                } else if (d > DISCHARGE_THRESHOLD * 0.1f) {
                    float alpha = d / DISCHARGE_THRESHOLD;
                    int cr = (color >> 16) & 0xFF;
                    int cg = (color >> 8) & 0xFF;
                    int cb = color & 0xFF;
                    int r = (int)((1-alpha)*cr + alpha*40);
                    int g = (int)((1-alpha)*cg + alpha*75);
                    int b = (int)((1-alpha)*cb + alpha*200);
                    color = rgb(r, g, b);
                }

                img.setRGB(px, py, color);
            }
        }

        File f = new File(filePath);
        ImageIO.write(img, "png", f);
        System.out.println("Saved: " + f.getAbsolutePath());
        System.out.println("Total: " + fmt0((System.nanoTime() - t0) / 1e6) + "ms");

        // 额外输出 discharge 热力图（400x400，直接看粒子效果）
        renderHeatmap("../output/hydro_s" + seed + "_heatmap.png", maxD);
    }

    void renderHeatmap(String filePath, float maxD) throws Exception {
        System.out.println("Rendering heatmap (400x400)...");
        BufferedImage hm = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float d = discharge[y][x];
                if (d < 1) {
                    hm.setRGB(x, y, rgb(20, 20, 20)); // 黑色
                } else {
                    // 对数热力图
                    float t = (float)Math.log1p(d) / (float)Math.log1p(maxD);
                    hm.setRGB(x, y, heatmapColor(t));
                }
            }
        }

        ImageIO.write(hm, "png", new File(filePath));
        System.out.println("Heatmap saved: " + filePath);
    }

    int heatmapColor(float t) {
        // 黑→蓝→青→绿→黄→红
        t = Math.max(0, Math.min(1, t));
        if (t < 0.2f) {
            int v = (int)(t * 5 * 255);
            return rgb(0, 0, v);
        } else if (t < 0.4f) {
            int v = (int)((t - 0.2f) * 5 * 255);
            return rgb(0, v, 255);
        } else if (t < 0.6f) {
            int v = (int)((t - 0.4f) * 5 * 255);
            return rgb(0, 255, 255 - v);
        } else if (t < 0.8f) {
            int v = (int)((t - 0.6f) * 5 * 255);
            return rgb(v, 255, 0);
        } else {
            int v = (int)((t - 0.8f) * 5 * 255);
            return rgb(255, 255 - v, 0);
        }
    }

    float sampleDischarge(float fx, float fy) {
        int x = (int)fx, y = (int)fy;
        if (x < 0 || x >= w-1 || y < 0 || y >= h-1) return 0;
        float dx = fx - x, dy = fy - y;
        return discharge[y][x]*(1-dx)*(1-dy) + discharge[y][x+1]*dx*(1-dy)
             + discharge[y+1][x]*(1-dx)*dy + discharge[y+1][x+1]*dx*dy;
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

    static class RiverSegment {
        final float x1, z1, x2, z2, d1, d2;
        RiverSegment(float x1, float z1, float x2, float z2, float d1, float d2) {
            this.x1=x1; this.z1=z1; this.x2=x2; this.z2=z2; this.d1=d1; this.d2=d2;
        }
    }

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        int mapSize = 400;
        int particles = 80000;
        float threshold = 60f;
        String tag = "hydro1";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed": seed = Integer.parseInt(args[++i]); break;
                case "--size": mapSize = Integer.parseInt(args[++i]); break;
                case "--particles": particles = Integer.parseInt(args[++i]); break;
                case "--threshold": threshold = Float.parseFloat(args[++i]); break;
                case "--tag": tag = args[++i]; break;
            }
        }
        NUM_PARTICLES = particles;
        MAP_SIZE = mapSize;
        DISCHARGE_THRESHOLD = threshold;

        HydroRiverVisualizer sim = new HydroRiverVisualizer(seed, mapSize);
        sim.renderAndSave("../output/hydro_s" + seed + "_" + tag + ".png");
    }
}
