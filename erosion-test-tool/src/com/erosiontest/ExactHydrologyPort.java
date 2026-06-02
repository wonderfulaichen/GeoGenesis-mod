package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * ExactHydrologyPort - 忠实移植 SimpleHydrology-master C++ 源码
 * 
 * 关键差异（区别于之前所有 Java 版本）:
 * 1. cascade() 地形松弛 — 每步粒子下降后扩散平滑地形
 * 2. 法线用 4 叉积而非中心差分
 * 3. lodsize 因子
 * 4. 粒子步长固定为 lodsize * sqrt(2)
 */
public class ExactHydrologyPort {

    // === 地图配置 ===
    static final int TILE_SIZE = 512;
    static final int MAP_SIZE = 1;
    static final int GRID_SIZE = MAP_SIZE * TILE_SIZE; // 512
    static final float MAP_SCALE = 80.0f;
    static final int LOD_SIZE = 1;
    
    // === 侵蚀参数 ===
    static final float LRATE = 0.1f;
    static final float MAXDIFF = 0.01f;
    static final float SETTLING = 0.1f; // C++原为0.8，降低防止过度平滑河道

    static final float EVAP_RATE = 0.001f;
    static final float DEPOSITION_RATE = 0.1f;
    static final float ENTRAINMENT = 10.0f;
    static final float GRAVITY = 1.0f;
    static final float MOMENTUM_TRANSFER = 1.0f;
    static final float MIN_VOL = 0.01f;
    static final float MAX_AGE = 500;

    static final float SEA_LEVEL = 0.35f; // 匹配V5，粒子流到海平面停
    static final float VIEW_SIZE = 6000f;

    // === 地形数据 ===
    final int w = GRID_SIZE, h = GRID_SIZE;
    final float[][] height = new float[h][w];
    final float[][] heightOrig = new float[h][w];
    final float[][] heightOcean = new float[h][w];

    // 累积字段
    final float[][] discharge = new float[h][w];
    final float[][] momentumX = new float[h][w];
    final float[][] momentumY = new float[h][w];

    // 跟踪字段（每轮迭代）
    final float[][] dischargeTrack = new float[h][w];
    final float[][] momentumXTrack = new float[h][w];
    final float[][] momentumYTrack = new float[h][w];

    // 根密度（原始代码中用，抑制沉积）
    final float[][] rootDensity = new float[h][w];

    final StandalonePreview terrain;
    final long seed;
    final Random rng;

    // === 统计 ===
    float maxD = 0;
    int totalDrops = 0;

    public ExactHydrologyPort(int seed) {
        this.seed = seed;
        this.rng = new Random(seed + 9999);
        this.terrain = new StandalonePreview(seed);
    }

    // ----------------------------------------------------------------
    // 1. 地形初始化
    // ----------------------------------------------------------------
    void initHeightmap() {
        float scale = VIEW_SIZE / (float) Math.max(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float wx = (x - w / 2f) * scale;
                float wz = (y - h / 2f) * scale;
                float hPure = terrain.computeHeightPure(wx, wz);
                heightOrig[y][x] = hPure;
                heightOcean[y][x] = terrain.computeHeightWithOcean(wx, wz, hPure);
                height[y][x] = hPure;
                rootDensity[y][x] = 0; // 初始无植被
            }
        }
    }

    // ----------------------------------------------------------------
    // 2. 地形访问函数
    // ----------------------------------------------------------------
    boolean oob(int x, int y) {
        return x < 0 || x >= w || y < 0 || y >= h;
    }

    float getHeight(int x, int y) {
        if (oob(x, y)) return 0;
        return height[y][x];
    }

    float getHeight(float fx, float fy) {
        return getHeight((int) fx, (int) fy);
    }

    // ----------------------------------------------------------------
    // 3. 法线计算 — 原始 C++ 的 4 叉积方法
    // ----------------------------------------------------------------
    float[] normal(int x, int y) {
        float nx = 0, ny = 0, nz = 0;
        final float sx = 1.0f, sy = MAP_SCALE, sz = 1.0f;

        // +X/+Y 对角线
        if (!oob(x + LOD_SIZE, y + LOD_SIZE)) {
            float h10 = getHeight(x + LOD_SIZE, y);
            float h01 = getHeight(x, y + LOD_SIZE);
            float h00 = getHeight(x, y);
            float dx1 = sx * LOD_SIZE;
            float dy1 = 0;
            float dz1 = sy * (h10 - h00);
            float dx2 = 0;
            float dy2 = sz * LOD_SIZE;
            float dz2 = sy * (h01 - h00);
            // cross product
            nx += dy1 * dz2 - dz1 * dy2;
            ny += dz1 * dx2 - dx1 * dz2;
            nz += dx1 * dy2 - dy1 * dx2;
        }

        // -X/-Y 对角线
        if (!oob(x - LOD_SIZE, y - LOD_SIZE)) {
            float hm10 = getHeight(x - LOD_SIZE, y);
            float hm01 = getHeight(x, y - LOD_SIZE);
            float h00 = getHeight(x, y);
            float dx1 = 0;
            float dy1 = sz * (-LOD_SIZE);
            float dz1 = sy * (hm01 - h00);
            float dx2 = sx * (-LOD_SIZE);
            float dy2 = 0;
            float dz2 = sy * (hm10 - h00);
            nx += dy1 * dz2 - dz1 * dy2;
            ny += dz1 * dx2 - dx1 * dz2;
            nz += dx1 * dy2 - dy1 * dx2;
        }

        // +X/-Y 对角线
        if (!oob(x + LOD_SIZE, y - LOD_SIZE)) {
            float h10 = getHeight(x + LOD_SIZE, y);
            float hm01 = getHeight(x, y - LOD_SIZE);
            float h00 = getHeight(x, y);
            float dx1 = sx * LOD_SIZE;
            float dy1 = 0;
            float dz1 = sy * (h10 - h00);
            float dx2 = 0;
            float dy2 = sz * (-LOD_SIZE);
            float dz2 = sy * (hm01 - h00);
            nx += dy1 * dz2 - dz1 * dy2;
            ny += dz1 * dx2 - dx1 * dz2;
            nz += dx1 * dy2 - dy1 * dx2;
        }

        // -X/+Y 对角线
        if (!oob(x - LOD_SIZE, y + LOD_SIZE)) {
            float hm10 = getHeight(x - LOD_SIZE, y);
            float h01 = getHeight(x, y + LOD_SIZE);
            float h00 = getHeight(x, y);
            float dx1 = sx * (-LOD_SIZE);
            float dy1 = 0;
            float dz1 = sy * (hm10 - h00);
            float dx2 = 0;
            float dy2 = sz * LOD_SIZE;
            float dz2 = sy * (h01 - h00);
            nx += dy1 * dz2 - dz1 * dy2;
            ny += dz1 * dx2 - dx1 * dz2;
            nz += dx1 * dy2 - dy1 * dx2;
        }

        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        return new float[]{nx, ny, nz};
    }

    // ----------------------------------------------------------------
    // 4. cascade() — 地形松弛（原始代码最关键缺失部分）
    // ----------------------------------------------------------------
    void cascade(int cx, int cy) {
        if (oob(cx, cy)) return;

        int[][] neighbors = {
            {-1, -1}, {-1, 0}, {-1, 1},
            {0, -1},           {0, 1},
            {1, -1},  {1, 0},  {1, 1}
        };

        // 收集有效邻居
        float[][] nh = new float[8][3];
        int nCount = 0;

        for (int[] nn : neighbors) {
            int nx = cx + LOD_SIZE * nn[0];
            int ny = cy + LOD_SIZE * nn[1];
            if (oob(nx, ny)) continue;
            float dist = (float) Math.sqrt(nn[0] * nn[0] + nn[1] * nn[1]);
            nh[nCount][0] = nx;
            nh[nCount][1] = ny;
            nh[nCount][2] = getHeight(nx, ny);
            nCount++;
        }

        if (nCount == 0) return;

        // 按高度排序
        Arrays.sort(nh, 0, nCount, (a, b) -> Float.compare(a[2], b[2]));

        float hCenter = getHeight(cx, cy);

        for (int i = 0; i < nCount; i++) {
            int nx = (int) nh[i][0];
            int ny = (int) nh[i][1];
            float hNeighbor = nh[i][2];
            float dist = (float) Math.sqrt((nx - cx) * (nx - cx) + (ny - cy) * (ny - cy)) / LOD_SIZE;
            if (dist < 0.001f) dist = 1.0f;

            // 全高度差
            float diff = hCenter - hNeighbor;
            if (diff == 0) continue;

            // 计算超额
            float excess;
            if (hNeighbor > 0.1f) {
                excess = Math.abs(diff) - dist * MAXDIFF * LOD_SIZE;
            } else {
                excess = Math.abs(diff);
            }
            if (excess <= 0) continue;

            // 转移量
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

    // ----------------------------------------------------------------
    // 5. 粒子下降 — 忠实复制原始 C++ descend()
    // ----------------------------------------------------------------
    boolean descend(float[] pos, float[] speed, float[] state) {
        int ix = (int) pos[0];
        int iy = (int) pos[1];
        if (oob(ix, iy)) return false;

        float age = state[0];
        float volume = state[1];
        float sediment = state[2];

        // 终止检查
        if (age > MAX_AGE) {
            height[iy][ix] += sediment;
            return false;
        }
        if (volume < MIN_VOL) {
            height[iy][ix] += sediment;
            return false;
        }

        // 有效沉积率（受植被抑制）
        float effD = DEPOSITION_RATE * (1.0f - rootDensity[iy][ix]);
        if (effD < 0) effD = 0;

        // 计算法线
        float[] n = normal(ix, iy);

        // 重力加速 — 使用法线的 X 和 Y 分量（坡度）
        speed[0] += LOD_SIZE * GRAVITY * n[0] / volume;
        speed[1] += LOD_SIZE * GRAVITY * n[1] / volume; 

        // 动量传递
        float fsx = momentumX[iy][ix];
        float fsy = momentumY[iy][ix];
        float fsLen = (float) Math.sqrt(fsx * fsx + fsy * fsy);
        float spLen = (float) Math.sqrt(speed[0] * speed[0] + speed[1] * speed[1]);
        if (fsLen > 0 && spLen > 0) {
            float dot = (speed[0] * fsx + speed[1] * fsy) / (spLen * fsLen);
            float factor = LOD_SIZE * MOMENTUM_TRANSFER * dot / (volume + discharge[iy][ix]);
            speed[0] += factor * fsx;
            speed[1] += factor * fsy;
        }

        // 归一化到固定步长 lodsize * sqrt(2)
        spLen = (float) Math.sqrt(speed[0] * speed[0] + speed[1] * speed[1]);
        if (spLen > 0) {
            float step = LOD_SIZE * 1.41421356f;
            speed[0] = (speed[0] / spLen) * step;
            speed[1] = (speed[1] / spLen) * step;
        }

        // 更新位置
        pos[0] += speed[0];
        pos[1] += speed[1];

        // 更新 discharge、动量跟踪
        dischargeTrack[iy][ix] += volume;
        momentumXTrack[iy][ix] += volume * speed[0];
        momentumYTrack[iy][ix] += volume * speed[1];

        // 获取新位置的高度
        float h2;
        if (oob((int) pos[0], (int) pos[1])) {
            h2 = height[iy][ix] - 0.002f;
        } else {
            h2 = getHeight((int) pos[0], (int) pos[1]);
        }

        // 侵蚀/沉积公式
        float cellDischarge = erf(0.4f * discharge[iy][ix]);
        float cEq = (1.0f + ENTRAINMENT * cellDischarge) * (height[iy][ix] - h2);
        if (cEq < 0) cEq = 0;
        float cdiff = cEq - sediment;

        sediment += effD * cdiff;
        height[iy][ix] -= effD * cdiff;

        // 蒸发（质量守恒）
        sediment /= (1.0f - EVAP_RATE);
        volume *= (1.0f - EVAP_RATE);

        // 越界检查
        if (oob((int) pos[0], (int) pos[1])) {
            volume = 0;
            return false;
        }

        cascade((int) pos[0], (int) pos[1]);

        state[0] = age + 1;
        state[1] = volume;
        state[2] = sediment;
        return true;
    }

    // ----------------------------------------------------------------
    // 6. erode() — 侵蚀主循环
    // ----------------------------------------------------------------
    void erode(int cycles) {
        // 清空跟踪字段
        for (int y = 0; y < h; y++) {
            Arrays.fill(dischargeTrack[y], 0);
            Arrays.fill(momentumXTrack[y], 0);
            Arrays.fill(momentumYTrack[y], 0);
        }

        for (int i = 0; i < cycles; i++) {
            // 在随机位置撒粒子
            float px = rng.nextInt(w);
            float py = rng.nextInt(h);

            if (getHeight((int) px, (int) py) < 0.1f) continue;

            float[] pos = {px, py};
            float[] speed = {0, 0};
            float[] state = {0, 1.0f, 0}; // age, volume, sediment

            while (descend(pos, speed, state)) {
            }

            totalDrops++;
        }

        // 更新累积字段
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                discharge[y][x] = (1.0f - LRATE) * discharge[y][x] + LRATE * dischargeTrack[y][x];
                momentumX[y][x] = (1.0f - LRATE) * momentumX[y][x] + LRATE * momentumXTrack[y][x];
                momentumY[y][x] = (1.0f - LRATE) * momentumY[y][x] + LRATE * momentumYTrack[y][x];
            }
        }
    }

    // ----------------------------------------------------------------
    // 7. 完整仿真
    // ----------------------------------------------------------------
    void simulate() {
        System.out.println("=== ExactHydrologyPort (忠实C++移植) ===");
        long t0 = System.nanoTime();
        initHeightmap();

        int totalCycles = 600000;
        int iterations = 300;
        int dropsPerIter = totalCycles / iterations;

        for (int iter = 0; iter < iterations; iter++) {
            for (int d = 0; d < dropsPerIter; d++) {
                float px = rng.nextInt(w);
                float py = rng.nextInt(h);
                if (getHeight((int) px, (int) py) < 0.1f) continue;

                float[] pos = {px, py};
                float[] speed = {0, 0};
                float[] state = {0, 1.0f, 0};

                while (descend(pos, speed, state)) {
                }
                totalDrops++;
            }

            // 更新累积字段
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    discharge[y][x] = (1.0f - LRATE) * discharge[y][x] + LRATE * dischargeTrack[y][x];
                    momentumX[y][x] = (1.0f - LRATE) * momentumX[y][x] + LRATE * momentumXTrack[y][x];
                    momentumY[y][x] = (1.0f - LRATE) * momentumY[y][x] + LRATE * momentumYTrack[y][x];
                }
            }

            if ((iter + 1) % 60 == 0) {
                float maxD = 0;
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++)
                        maxD = Math.max(maxD, discharge[y][x]);
                System.out.println("  Iter " + (iter + 1) + "/" + iterations + "  drops=" + totalDrops + "  maxD=" + fmt1(maxD));
            }
        }

        double totalMs = (System.nanoTime() - t0) / 1e6;
        System.out.println("Done: " + fmt0(totalMs) + "ms  totalDrops=" + totalDrops);

        // Discharge 统计
        List<Float> vals = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (discharge[y][x] > 0) vals.add(discharge[y][x]);
        Collections.sort(vals);
        int n = vals.size();
        maxD = n > 0 ? vals.get(n - 1) : 0;
        System.out.println("Max discharge: " + fmt1(maxD) + "  Non-zero: " + n);
        if (n > 0) {
            System.out.println("  p90=" + fmt1(vals.get((int) (n * 0.9))) + " p95=" + fmt1(vals.get((int) (n * 0.95)))
                + " p98=" + fmt1(vals.get((int) (n * 0.98))) + " p99=" + fmt1(vals.get((int) (n * 0.99))));
        }
    }

    // ----------------------------------------------------------------
    // 8. 渲染与保存
    // ----------------------------------------------------------------
    void renderAndSave(String filePath) throws Exception {
        simulate();

        int sz = w;
        int gap = 8;
        int panelW = sz;
        int imgW = panelW * 4 + gap * 3;
        int imgH = sz + 50;
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2 = (java.awt.Graphics2D) img.getGraphics();
        g2.fillRect(0, 0, imgW, imgH);
        g2.dispose();

        // Discharge 百分位
        List<Float> allD = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (discharge[y][x] > 0) allD.add(discharge[y][x]);
        Collections.sort(allD);
        int n = allD.size();
        float p95 = n > 0 ? allD.get((int) (n * 0.95f)) : 0;
        float p98 = n > 0 ? allD.get((int) (n * 0.98f)) : 0;
        float p99 = n > 0 ? allD.get((int) (n * 0.99f)) : 0;
        maxD = n > 0 ? allD.get(n - 1) : 0;

        // 3x3 box blur discharge
        float[][] blurD = new float[h][w];
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                float sum = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++)
                        sum += discharge[y + dy][x + dx];
                blurD[y][x] = sum / 9f;
            }

        for (int py = 0; py < sz; py++) {
            float wz = (py - h / 2f) * (VIEW_SIZE / (float) h);
            for (int px = 0; px < sz; px++) {
                float wx = (px - w / 2f) * (VIEW_SIZE / (float) w);

                float hEroded = height[py][px];
                float hOcean = heightOcean[py][px];
                float c = terrain.sampleContinentRaw(wx, wz);
                float d = blurD[py][px];

                float temp = terrain.sampleTemperature(wx, wz);
                float moist = terrain.sampleMoisture(wx, wz, (c + 1f) * 0.5f, temp);
                int biomeColor = terrain.minecraftBiomeColor(c, temp, moist, hEroded, 0);

                // ===== 第1列：侵蚀后纯地形（无光照，V5风格）=====
                int c1 = terrainColorSmooth(hEroded);
                img.setRGB(px, py, c1);

                // ===== 第2列：侵蚀后+河流 =====
                int c2 = terrainColorSmooth(hEroded);
                int r2 = (c2 >> 16) & 0xFF, g2r = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
                if (d > p95) {
                    float t = (float) Math.log1p(d - p95) / (float) Math.log1p(maxD - p95 + 1);
                    t = Math.max(0, Math.min(1, t));
                    if (d > p99) {
                        r2 = (int) (10 + t * 20);
                        g2r = (int) (30 + t * 80);
                        b2 = (int) (120 + t * 135);
                    } else if (d > p98) {
                        r2 = (int) (5 + t * 15);
                        g2r = (int) (20 + t * 60);
                        b2 = (int) (80 + t * 100);
                    } else {
                        r2 = (int) (5 + t * 10);
                        g2r = (int) (15 + t * 40);
                        b2 = (int) (60 + t * 60);
                    }
                }
                img.setRGB(panelW + gap + px, py, rgb(Math.min(255, r2), Math.min(255, g2r), Math.min(255, b2)));

                // ===== 第3列：海洋地形+河流（用 Minecraft 颜色风格）=====
                int c3;
                if (c < 0) {
                    c3 = biomeColor;
                } else {
                    c3 = biomeColor;
                }
                int r3 = (c3 >> 16) & 0xFF, g3 = (c3 >> 8) & 0xFF, b3 = c3 & 0xFF;
                if (c >= 0 && d > p95) {
                    float t = (float) Math.log1p(d - p95) / (float) Math.log1p(maxD - p95 + 1);
                    t = Math.max(0, Math.min(1, t));
                    if (d > p99) {
                        r3 = (int) (10 + t * 20);
                        g3 = (int) (30 + t * 80);
                        b3 = (int) (120 + t * 135);
                    } else if (d > p98) {
                        r3 = (int) (5 + t * 15);
                        g3 = (int) (20 + t * 60);
                        b3 = (int) (80 + t * 100);
                    } else {
                        r3 = (int) (5 + t * 10);
                        g3 = (int) (15 + t * 40);
                        b3 = (int) (60 + t * 60);
                    }
                }
                img.setRGB((panelW + gap) * 2 + px, py, rgb(Math.min(255, r3), Math.min(255, g3), Math.min(255, b3)));

                // ===== 第4列：热力图 =====
                if (d < p95) {
                    img.setRGB((panelW + gap) * 3 + px, py, rgb(20, 20, 20));
                } else {
                    float t = (float) Math.log1p(d - p95) / (float) Math.log1p(maxD - p95 + 1);
                    t = Math.max(0, Math.min(1, t));
                    img.setRGB((panelW + gap) * 3 + px, py, heatColor(t));
                }
            }
        }

        drawLabel(img, "侵蚀后纯地形", 10, 2, 0xFFFFFF);
        drawLabel(img, "侵蚀后+河流", panelW + gap + 10, 2, 0xFFFFFF);
        drawLabel(img, "Minecraft地形+河流", (panelW + gap) * 2 + 10, 2, 0xFFFFFF);
        drawLabel(img, "Discharge热力图", (panelW + gap) * 3 + 10, 2, 0xFFFFFF);
        String info = "Seed=" + seed + " ExactPort ENT=" + ENTRAINMENT + " DEP=" + DEPOSITION_RATE +
            " Cascade=YES  Drops=" + totalDrops;
        drawLabel(img, info, 10, sz + 5, 0xAAAAAA);

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

    int heatColor(float t) {
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

    static int rgb(int r, int g, int b) { return (r << 16) | (g << 8) | b; }
    static String fmt1(float v) { return String.format("%.1f", v); }
    static String fmt0(double v) { return String.format("%.0f", v); }

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        String tag = "v1";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed": seed = Integer.parseInt(args[++i]); break;
                case "--tag": tag = args[++i]; break;
            }
        }
        ExactHydrologyPort sim = new ExactHydrologyPort(seed);
        sim.renderAndSave("output/exact_s" + seed + "_" + tag + ".png");
    }
}
