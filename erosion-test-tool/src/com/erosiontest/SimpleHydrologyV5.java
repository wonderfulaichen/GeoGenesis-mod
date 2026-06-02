package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * SimpleHydrology V5 - 忠实复刻 V4 核心逻辑 + 渲染时笔刷平滑
 * 
 * 核心原理（与 V4 完全一致）：
 * 1. 多轮迭代（ITERATIONS），每轮随机撒粒子
 * 2. 粒子沿地形梯度下坡，受动量地图（mx/my）影响
 * 3. 固定步长移动，双线性采样高度
 * 4. 指数平滑更新 discharge（流量地图）
 * 5. 侵蚀/沉积改变地形（与 V4 相同）
 * 
 * 改进（仅渲染阶段）：
 * - 使用圆形笔刷对 discharge 做平滑采样，让河流显示更平滑
 * - 分层阈值：p95=支流, p98=一级支流, p99=主干
 */
public class SimpleHydrologyV5 {

    static int MAP_SIZE = 1024;
    static int ITERATIONS = 200;
    static int DROPS_PER_ITER = 4000;
    static float LRATE = 0.1f;
    static float GRAVITY = 1.0f;
    static float MOMENTUM = 0.3f;
    static float EVAP = 0.001f;
    static float DEPOSITION = 0.1f;
    static float ENTRAINMENT = 10.0f;
    static float MIN_VOL = 0.01f;
    static float MAX_AGE = 500;
    static float MAXDIFF = 0.01f;
    static float SETTLING = 0.4f;

    static float SEA_LEVEL = 0.35f;
    static int VIEW_SIZE = 2000;
    static final int OUTPUT_SIZE = 2048;

    final int w, h;
    final float[][] height;
    final float[][] heightPure;  // 纯地形（无海洋凹陷）
    final float[][] discharge;
    final float[][] dischargePhase1;  // 第一轮的 discharge（纯地形）
    final float[][] mxPhase1, myPhase1;  // 第一轮的动量方向
    final float[][] dischargeTrack;
    final float[][] mx, my;
    final float[][] mxTrack, myTrack;

    final StandalonePreview terrain;
    final long seed;
    final Random rng;
    float phase1P98;  // Phase 1 的 p98 阈值，用于 Phase 2 定位河流路径

    public SimpleHydrologyV5(int seed, int mapSize) {
        this.seed = seed;
        this.terrain = new StandalonePreview(seed);
        this.rng = new Random(seed + 9999);
        this.w = mapSize;
        this.h = mapSize;
        this.height = new float[h][w];
        this.heightPure = new float[h][w];
        this.discharge = new float[h][w];
        this.dischargePhase1 = new float[h][w];
        this.mxPhase1 = new float[h][w];
        this.myPhase1 = new float[h][w];
        this.dischargeTrack = new float[h][w];
        this.mx = new float[h][w];
        this.my = new float[h][w];
        this.mxTrack = new float[h][w];
        this.myTrack = new float[h][w];
    }

    // 第一轮：纯地形（无海洋凹陷）
    void initPhase1() {
        float scale = (float)VIEW_SIZE / Math.max(w, h);
        float minH = 1, maxH = 0;
        float minT = 1, maxT = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float wx = (x - w/2f) * scale;
                float wz = (y - h/2f) * scale;
                
                // 检查 sampleTerrainBase 的范围
                float t = terrain.sampleTerrainBase(wx, wz);
                minT = Math.min(minT, t);
                maxT = Math.max(maxT, t);
                
                // 使用纯地形高度（不添加海洋凹陷）
                float h_val = terrain.computeHeightPure(wx, wz);
                height[y][x] = h_val;
                heightPure[y][x] = h_val;  // 保存纯地形
                minH = Math.min(minH, h_val);
                maxH = Math.max(maxH, h_val);
            }
        }
        System.out.println("Phase1 height range: " + minH + " - " + maxH);
        System.out.println("sampleTerrainBase range: " + minT + " - " + maxT);
    }

    // 第二轮：添加海洋凹陷
    void initPhase2() {
        float scale = (float)VIEW_SIZE / Math.max(w, h);
        float minH = 1, maxH = 0;
        int oceanCount = 0;
        int[] cBuckets = new int[5]; // c < -0.5, -0.5~0, 0~0.05, 0.05~0.5, > 0.5
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float wx = (x - w/2f) * scale;
                float wz = (y - h/2f) * scale;
                float c = terrain.sampleContinentRaw(wx, wz);
                if (c < -0.5f) cBuckets[0]++;
                else if (c < 0) cBuckets[1]++;
                else if (c < 0.05f) cBuckets[2]++;
                else if (c < 0.5f) cBuckets[3]++;
                else cBuckets[4]++;

                // 添加海洋凹陷
                float h_val = terrain.computeHeightWithOcean(wx, wz, height[y][x]);
                height[y][x] = h_val;
                minH = Math.min(minH, h_val);
                maxH = Math.max(maxH, h_val);
                if (h_val < SEA_LEVEL) oceanCount++;
            }
        }
        System.out.println("Phase2 height range: " + minH + " - " + maxH + "  Ocean pixels: " + oceanCount);
        System.out.println("c distribution: <-0.5=" + cBuckets[0] + " -0.5~0=" + cBuckets[1] + " 0~0.05=" + cBuckets[2] + " 0.05~0.5=" + cBuckets[3] + " >0.5=" + cBuckets[4]);
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
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                float h1 = height[iy + dy][ix + dx];
                float diff = h0 - h1;
                if (Math.abs(diff) <= MAXDIFF) continue;
                float maxdiff = SETTLING * diff;
                height[iy + dy][ix + dx] += maxdiff;
                totalOut += maxdiff;
            }
        }
        height[iy][ix] -= totalOut;
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

        // 粒子进入海洋时停止并沉积（河流不应在海洋继续作用）
        if (height[iy][ix] < SEA_LEVEL) {
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

        // 使用 erf 压缩 discharge，与原版 SimpleHydrology 一致
        float nodeDischarge = erf(0.4f * discharge[iy][ix]);
        float c_eq = (1.0f + ENTRAINMENT * nodeDischarge) * (height[iy][ix] - h2);
        if (c_eq < 0) c_eq = 0;

        float cdiff = c_eq - sediment;

        sediment += effD * cdiff;
        height[iy][ix] -= effD * cdiff;

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
        System.out.println("=== SimpleHydrology V5 (两轮生成: 纯地形+海岸补充) ===");
        System.out.println("Map: " + w + "x" + h + "  Iter: " + ITERATIONS + "  Drops/Iter: " + DROPS_PER_ITER);

        long t0 = System.nanoTime();

        // ========== 第一轮：纯地形（无海洋凹陷）==========
        System.out.println("--- Phase 1: Pure Terrain (No Ocean) ---");
        initPhase1();

        for (int iter = 0; iter < ITERATIONS / 2; iter++) {
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

                // 纯地形阶段：只在大陆上生成粒子，跳过低于海平面的区域
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
                System.out.println("  Phase1 Iter " + (iter+1) + "/" + (ITERATIONS/2) + "  maxD=" + fmt1(maxD));
            }
        }

        // ========== 保存第一轮的 discharge 和动量，并清零用于第二轮 ==========
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                dischargePhase1[y][x] = discharge[y][x];
                mxPhase1[y][x] = mx[y][x];
                myPhase1[y][x] = my[y][x];
                discharge[y][x] = 0;
            }

        // 计算 Phase 1 的 p98 阈值
        List<Float> phase1Vals = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (dischargePhase1[y][x] > 0) phase1Vals.add(dischargePhase1[y][x]);
        Collections.sort(phase1Vals);
        phase1P98 = phase1Vals.size() > 0 ? phase1Vals.get((int)(phase1Vals.size() * 0.98f)) : 0;
        System.out.println("Phase1 p98=" + fmt1(phase1P98));

        // 收集 Phase 1 河流路径上的像素坐标（discharge > p98_1）
        List<int[]> riverPixels = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (dischargePhase1[y][x] > phase1P98) riverPixels.add(new int[]{x, y});
        System.out.println("Phase1 river pixels: " + riverPixels.size());

        // ========== 第二轮：添加海洋，海岸附近补充 ==========
        System.out.println("--- Phase 2: Add Ocean + Coastline Supplement ---");
        initPhase2();

        // 找到河口像素：Phase 1 河流路径上，纯地形高度在入海口附近的像素
        // 使用纯地形高度（heightPure）分类，避免被 Phase 2 海洋凹陷干扰
        List<int[]> riverMouthPixels = new ArrayList<>();
        List<int[]> inlandRiverPixels = new ArrayList<>();
        for (int[] rp : riverPixels) {
            int x = rp[0], y = rp[1];
            float hp = heightPure[y][x];
            if (hp >= SEA_LEVEL - 0.08f && hp <= SEA_LEVEL + 0.15f) {
                riverMouthPixels.add(rp);
            } else if (hp > SEA_LEVEL + 0.15f) {
                inlandRiverPixels.add(rp);
            }
        }
        System.out.println("River mouth pixels: " + riverMouthPixels.size()
            + "  inland river: " + inlandRiverPixels.size()
            + "  submerged: " + (riverPixels.size() - riverMouthPixels.size() - inlandRiverPixels.size()));

        for (int iter = ITERATIONS / 2; iter < ITERATIONS; iter++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    dischargeTrack[y][x] = 0;
                    mxTrack[y][x] = 0;
                    myTrack[y][x] = 0;
                }
            }

            for (int i = 0; i < DROPS_PER_ITER; i++) {
                float px, py;
                // 80% 从 Phase 1 内陆河流路径（维持河道）+ 20% 随机陆地（覆盖面）
                if (rng.nextFloat() < 0.80f && inlandRiverPixels.size() > 0) {
                    int[] rp = inlandRiverPixels.get(rng.nextInt(inlandRiverPixels.size()));
                    px = rp[0] + (rng.nextFloat() - 0.5f) * 2f;
                    py = rp[1] + (rng.nextFloat() - 0.5f) * 2f;
                } else {
                    px = rng.nextFloat() * w;
                    py = rng.nextFloat() * h;
                }

                // 只在大陆上生成粒子（高度 >= 海平面）
                float h_val = getH((int)px, (int)py);
                if (h_val < SEA_LEVEL) continue;

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
                System.out.println("  Phase2 Iter " + (iter+1) + "/" + ITERATIONS + "  maxD=" + fmt1(maxD));
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
                + " p95=" + fmt1(vals.get((int)(n*0.95))) + " p98=" + fmt1(vals.get((int)(n*0.98)))
                + " p99=" + fmt1(vals.get((int)(n*0.99))));
        }
    }

    // ============ 渲染时方形窗口最大采样 discharge（与 V4 一致） ============
    float sampleDischargeMax(int cx, int cy, int radius) {
        float max = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int nx = cx + dx, ny = cy + dy;
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                max = Math.max(max, discharge[ny][nx]);
            }
        }
        return max;
    }

    void renderAndSave(String filePath) throws Exception {
        simulate();

        int sz = w;
        int gap = 8;
        int panelW = sz;
        int imgW = panelW * 4 + gap * 3;  // 4列
        int imgH = sz + 40;
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        img.getGraphics().fillRect(0, 0, imgW, imgH);

        // 计算组合 discharge（第3、4列用）- 海洋区域不清零，只覆盖 Phase 1 幽灵值
        float maxD = 0;
        float[][] displayD = new float[h][w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                if (height[y][x] < SEA_LEVEL) {
                    displayD[y][x] = 0;
                } else {
                    displayD[y][x] = Math.max(dischargePhase1[y][x], discharge[y][x]);
                }
                maxD = Math.max(maxD, displayD[y][x]);
            }

        List<Float> allD = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (displayD[y][x] > 0) allD.add(displayD[y][x]);
        Collections.sort(allD);
        float p90 = allD.size() > 0 ? allD.get((int)(allD.size() * 0.90f)) : 0;
        float p95 = allD.size() > 0 ? allD.get((int)(allD.size() * 0.95f)) : 0;
        float p98 = allD.size() > 0 ? allD.get((int)(allD.size() * 0.98f)) : 0;
        float p99 = allD.size() > 0 ? allD.get((int)(allD.size() * 0.99f)) : 0;
        float p995 = allD.size() > 0 ? allD.get((int)(allD.size() * 0.995f)) : 0;

        // 计算 Phase 1 discharge（第2列用）的百分位值
        float maxD1 = 0;
        List<Float> allD1 = new ArrayList<>();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                maxD1 = Math.max(maxD1, dischargePhase1[y][x]);
                if (dischargePhase1[y][x] > 0) allD1.add(dischargePhase1[y][x]);
            }
        Collections.sort(allD1);
float p98_1 = allD1.size() > 0 ? allD1.get((int)(allD1.size() * 0.98f)) : 0;
float p985_1 = allD1.size() > 0 ? allD1.get((int)(allD1.size() * 0.985f)) : 0;
float p99_1 = allD1.size() > 0 ? allD1.get((int)(allD1.size() * 0.99f)) : 0;
float p995_1 = allD1.size() > 0 ? allD1.get((int)(allD1.size() * 0.995f)) : 0;
float riverMin_1 = p98_1;
System.out.println("Rendering (p90=" + fmt1(p90) + " p95=" + fmt1(p95) + " p98=" + fmt1(p98) + " p99=" + fmt1(p99) + " p995=" + fmt1(p995) + ")...");
System.out.println("Phase1 discharge: maxD1=" + fmt1(maxD1) + " p98_1=" + fmt1(p98_1) + " p985_1=" + fmt1(p985_1) + " p99_1=" + fmt1(p99_1) + " p995_1=" + fmt1(p995_1) + " riverMin_1=" + fmt1(riverMin_1));

// 对 displayD 做 3x3 box blur，扩散河流影响范围
float[][] blurD = new float[h][w];
for (int y = 1; y < h - 1; y++)
    for (int x = 1; x < w - 1; x++) {
        float sum = 0;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++)
                sum += displayD[y+dy][x+dx];
        blurD[y][x] = sum / 9f;
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

        for (int py = 0; py < sz; py++) {
            for (int px = 0; px < sz; px++) {
                int mx = Math.min(px, w - 1);
                int my = Math.min(py, h - 1);

                float terrainH = height[my][mx];

                // StandalonePreview biome 颜色
                float wx = (mx - w/2f) * ((float)VIEW_SIZE / w);
                float wz = (my - h/2f) * ((float)VIEW_SIZE / h);
                float c = terrain.sampleContinentRaw(wx, wz);
                float temp = terrain.sampleTemperature(wx, wz);
                float moist = terrain.sampleMoisture(wx, wz, (c+1f)*0.5f, temp);
                int biomeColor = terrain.minecraftBiomeColor(c, temp, moist, terrainH, 0);

                // 第1列：纯地形（无海洋凹陷）- 平滑地形色阶
                float hp = heightPure[my][mx];
                int pureColor = terrainColorSmooth(hp);
                img.setRGB(px, py, pureColor);

                // 第2列：纯地形 + 河流（使用模糊后的 discharge，展示更宽河道）
                float d1 = blurD[my][mx];
                int riverColorPure = pureColor;
                if (d1 > riverMin_1) {
                    float t = (float)Math.log1p(d1 - riverMin_1) / (float)Math.log1p(maxD1 - riverMin_1 + 1);
                    t = Math.max(0, Math.min(1, t));
                    if (d1 > p995_1) {
                        int r = (int)(10 + t * 20);
                        int g = (int)(30 + t * 80);
                        int b = (int)(120 + t * 135);
                        riverColorPure = rgb(r, g, b);
                    } else if (d1 > p99_1) {
                        int r = (int)(5 + t * 15);
                        int g = (int)(20 + t * 60);
                        int b = (int)(80 + t * 100);
                        riverColorPure = rgb(r, g, b);
                    } else {
                        int r = (int)(5 + t * 10);
                        int g = (int)(15 + t * 40);
                        int b = (int)(60 + t * 60);
                        riverColorPure = rgb(r, g, b);
                    }
                }
                img.setRGB(panelW + gap + px, py, riverColorPure);

                // 第3列：添加海洋后的地形 + 河流（使用模糊后的 discharge）
                float d = blurD[my][mx];
                int riverColorOcean = biomeColor;
                // 用 continent 噪声判断海洋：与 biomeColor 一致的海洋检测
                if (c < 0.0f) {
                    riverColorOcean = biomeColor;  // 海洋区域不画河流
                } else if (d > riverMin_1) {
                    float t = (float)Math.log1p(d - riverMin_1) / (float)Math.log1p(maxD1 - riverMin_1 + 1);
                    t = Math.max(0, Math.min(1, t));
                    if (d > p995_1) {
                        int r = (int)(10 + t * 20);
                        int g = (int)(30 + t * 80);
                        int b = (int)(120 + t * 135);
                        riverColorOcean = rgb(r, g, b);
                    } else if (d > p99_1) {
                        int r = (int)(5 + t * 15);
                        int g = (int)(20 + t * 60);
                        int b = (int)(80 + t * 100);
                        riverColorOcean = rgb(r, g, b);
                    } else {
                        int r = (int)(5 + t * 10);
                        int g = (int)(15 + t * 40);
                        int b = (int)(60 + t * 60);
                        riverColorOcean = rgb(r, g, b);
                    }
                }
               img.setRGB((panelW + gap) * 2 + px, py, riverColorOcean);
            }
        }

        // 第4列：discharge 热力图
        for (int py = 0; py < sz; py++) {
            for (int px = 0; px < sz; px++) {
                int mx = Math.min(px, w - 1);
                int my = Math.min(py, h - 1);
                float hmD = displayD[my][mx];
                if (hmD < p90) {
                    img.setRGB((panelW+gap)*3+px, py, rgb(20, 20, 20));
                } else {
                    float t = (float)Math.log1p(hmD - p90) / (float)Math.log1p(maxD - p90 + 1);
                    t = Math.max(0, Math.min(1, t));
                    img.setRGB((panelW+gap)*3+px, py, heatmapColor(t));
                }
            }
        }

        drawLabel(img, "纯地形(无海洋)", 10, 2, 0xFFFFFF);
        drawLabel(img, "纯地形+河流", panelW+gap+10, 2, 0xFFFFFF);
        drawLabel(img, "海洋地形+河流", (panelW+gap)*2+10, 2, 0xFFFFFF);
        drawLabel(img, "Discharge热力图", (panelW+gap)*3+10, 2, 0xFFFFFF);
        String info = "Seed=" + seed + " Iter=" + ITERATIONS + " Drops=" + DROPS_PER_ITER;
        drawLabel(img, info, 10, sz+2, 0xAAAAAA);

        ImageIO.write(img, "png", new File(filePath));
        System.out.println("Saved: " + filePath);
    }

    int terrainColorSmooth(float h) {
        h = Math.max(0, Math.min(1, h));
        float[][] stops = {
            {0.00f, 5, 20, 60},      // 深海蓝
            {0.25f, 20, 60, 120},    // 浅海
            {0.35f, 180, 170, 80},   // 沙滩
            {0.45f, 100, 150, 50},   // 低地绿
            {0.55f, 60, 130, 50},    // 森林
            {0.65f, 140, 150, 70},   // 丘陵
            {0.75f, 170, 160, 120},  // 山地棕
            {0.85f, 190, 185, 170},  // 高海拔灰
            {1.00f, 230, 230, 240}   // 雪峰白
        };
        int idx = 0;
        for (int i = 0; i < stops.length - 1; i++) {
            if (h >= stops[i][0] && h <= stops[i+1][0]) { idx = i; break; }
            if (i == stops.length - 2) idx = stops.length - 2;
        }
        float t = (h - stops[idx][0]) / (stops[idx+1][0] - stops[idx][0] + 0.0001f);
        t = t * t * (3f - 2f * t);
        int r = (int)(stops[idx][1] + t * (stops[idx+1][1] - stops[idx][1]));
        int g = (int)(stops[idx][2] + t * (stops[idx+1][2] - stops[idx][2]));
        int b = (int)(stops[idx][3] + t * (stops[idx+1][3] - stops[idx][3]));
        return rgb(r, g, b);
    }

    int heatmapColor(float t) {
        t = Math.max(0, Math.min(1, t));
        if (t < 0.2f) return rgb(0, 0, (int)(t*5*255));
        else if (t < 0.4f) return rgb(0, (int)((t-0.2f)*5*255), 255);
        else if (t < 0.6f) return rgb(0, 255, (int)((0.6f-t)*5*255));
        else if (t < 0.8f) return rgb((int)((t-0.6f)*5*255), 255, 0);
        else return rgb(255, (int)((1-t)*5*255), 0);
    }

    void drawLabel(BufferedImage img, String text, int x, int y, int color) {
        java.awt.Graphics g = img.getGraphics();
        g.setColor(new java.awt.Color(color));
        g.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
        g.drawString(text, x, y + 12);
        g.dispose();
    }

    // 误差函数 erf，与原版 SimpleHydrology 一致
    static float erf(float x) {
        // 使用近似公式
        float a = Math.abs(x);
        float t = 1.0f / (1.0f + 0.47047f * a);
        float result = 1.0f - t * (0.3480242f + t * (-0.0958798f + t * 0.7478556f)) * (float)Math.exp(-a * a);
        return x >= 0 ? result : -result;
    }

    static int rgb(int r, int g, int b) { return (r<<16) | (g<<8) | b; }
    static String fmt1(float v) { return String.format("%.1f", v); }
    static String fmt0(double v) { return String.format("%.0f", v); }

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        int mapSize = MAP_SIZE;
        int iter = ITERATIONS;
        int drops = DROPS_PER_ITER;
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

        SimpleHydrologyV5 sim = new SimpleHydrologyV5(seed, mapSize);
        sim.renderAndSave("output/simplehydro5_s" + seed + "_" + tag + ".png");
    }
}
