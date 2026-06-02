package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * 混合河网生成器
 * 
 * 核心思路：
 * 1. 用 TerraForged 规划主河道（保证树状结构和弯曲）
 * 2. 用 SimpleHydrology 在主河道之间生成支流（真实侵蚀）
 * 3. 主河道雕刻地形 + 支流侵蚀地形 = 最终地形
 * 
 * 优势：
 * - 主河道有清晰的树状结构和自然弯曲
 * - 支流跟随真实地形梯度
 * - 地形被侵蚀，形成凹陷河道
 */
public class HybridRiverGen {

    // ============ TerraForged 参数 ============
    static int GRID_W = 24;
    static int GRID_H = 24;
    static float CELL_SPACING = 250f;
    static float JITTER = 0.75f;
    static float SEA_THRESHOLD = 0.35f;
    static int MIN_RIVER_LENGTH = 5;
    static float MEANDER_AMP = 0.6f;
    static float WARP_STRENGTH = 0.3f;
    static float WIDTH_BASE = 8f;
    static float WIDTH_PER_LEVEL = 4f;

    // ============ SimpleHydrology 参数 ============
    static int MAP_SIZE = 512;
    static int SUB_ITERATIONS = 100;
    static int SUB_DROPS_PER_ITER = 1000;
    static float LRATE = 0.1f;
    static float GRAVITY = 1.0f;
    static float MOMENTUM = 1.0f;
    static float EVAP = 0.001f;
    static float DEPOSITION = 0.1f;
    static float ENTRAINMENT = 5.0f;  // 降低，防止数值爆炸
    static float MIN_VOL = 0.01f;
    static float MAX_AGE = 300;
    static float MAXDIFF = 0.01f;
    static float SETTLING = 0.8f;

    // ============ 混合参数 ============
    static float MAIN_RIVER_DEPTH = 0.08f;  // 主河道雕刻深度
    static float MAIN_RIVER_WIDTH = 0.02f;  // 主河道影响范围（归一化）
    static float TRIBUTARY_CAP = 200f;      // 支流 discharge 上限

    static int VIEW_SIZE = 6000;
    static final int OUTPUT_SIZE = 2048;

    // ============ 数据结构 ============
    static class Cell {
        float wx, wz;
        float noise, height;
        int ci, cj;
        Cell lowestNeighbor;
        boolean isRiver, isSource;
        int riverLevel;
    }

    static class RiverSeg {
        float x1, z1, x2, z2;
        float h1, h2, r1, r2;
        Cell a, b;
        int level;
    }

    final long seed;
    final Random rng;
    final StandalonePreview terrain;
    final List<Cell> cells = new ArrayList<>();
    final List<RiverSeg> segments = new ArrayList<>();
    final List<float[]> curvedSegs = new ArrayList<>();

    // 地形和 discharge
    final int w, h;
    final float[][] height;
    final float[][] discharge;
    final float[][] dischargeTrack;
    final float[][] mx, my;
    final float[][] mxTrack, myTrack;
    final float[][] riverMask;  // 主河道掩码

    public HybridRiverGen(int seed) {
        this.seed = seed;
        this.rng = new Random(seed + 8888);
        this.terrain = new StandalonePreview(seed);
        this.w = MAP_SIZE;
        this.h = MAP_SIZE;
        this.height = new float[h][w];
        this.discharge = new float[h][w];
        this.dischargeTrack = new float[h][w];
        this.mx = new float[h][w];
        this.my = new float[h][w];
        this.mxTrack = new float[h][w];
        this.myTrack = new float[h][w];
        this.riverMask = new float[h][w];
    }

    // ============ Phase 1: TerraForged 主河道规划 ============
    void planMainRivers() {
        System.out.println("=== Phase 1: Planning main rivers (TerraForged) ===");

        // 1. Jittered Grid
        int gridSeed = (int)seed + 1111;
        for (int ci = 0; ci < GRID_W; ci++) {
            for (int cj = 0; cj < GRID_H; cj++) {
                int hash = hash(gridSeed, ci, cj);
                float jx = (cj & 1) == 0 ? 0.5f : 0f;
                float ox = (hashF(hash, 0) - 0.5f) * JITTER;
                float oz = (hashF(hash, 1) - 0.5f) * JITTER;

                float wx = (ci + jx + ox) * CELL_SPACING - GRID_W * CELL_SPACING / 2f;
                float wz = (cj + oz) * CELL_SPACING - GRID_H * CELL_SPACING / 2f;

                Cell cell = new Cell();
                cell.wx = wx; cell.wz = wz;
                cell.noise = terrain.computeHeight(wx, wz);
                cell.height = cell.noise;
                cell.ci = ci; cell.cj = cj;
                cells.add(cell);
            }
        }

        // 替换高度为"到最近海洋的距离"
        for (Cell cell : cells) {
            if (cell.height <= SEA_THRESHOLD) continue;
            float minDist = Float.MAX_VALUE;
            for (Cell other : cells) {
                if (other.height <= SEA_THRESHOLD) {
                    float dx = cell.wx - other.wx;
                    float dz = cell.wz - other.wz;
                    float d = dx*dx + dz*dz;
                    if (d < minDist) minDist = d;
                }
            }
            float maxDist = (float)Math.hypot(GRID_W * CELL_SPACING, GRID_H * CELL_SPACING);
            cell.height = Math.max(SEA_THRESHOLD, 0.3f + (1f - (float)Math.sqrt(minDist) / maxDist) * 0.6f);
        }

        // 2. D8 连接
        int[][] dirs = {{1,0}, {0,1}, {-1,0}, {0,-1}, {1,1}, {-1,1}, {1,-1}, {-1,-1}};
        for (Cell cell : cells) {
            if (cell.height < SEA_THRESHOLD) {
                cell.lowestNeighbor = null;
                continue;
            }
            Cell lowest = null;
            float lowestH = Float.MAX_VALUE;
            for (int[] d : dirs) {
                Cell nb = getCell(cell.ci + d[0], cell.cj + d[1]);
                if (nb == null) continue;
                if (nb.height < lowestH) {
                    lowestH = nb.height;
                    lowest = nb;
                }
            }
            cell.lowestNeighbor = (lowest != null && lowest.height < cell.height) ? lowest : null;
        }

        // 3. 过滤短路径
        Map<String, RiverSeg> allSegs = new HashMap<>();
        for (Cell cell : cells) {
            if (cell.lowestNeighbor == null) continue;
            String key = cell.ci + "," + cell.cj + "->" + cell.lowestNeighbor.ci + "," + cell.lowestNeighbor.cj;
            if (!allSegs.containsKey(key)) {
                RiverSeg seg = new RiverSeg();
                seg.a = cell; seg.b = cell.lowestNeighbor;
                seg.x1 = cell.wx; seg.z1 = cell.wz;
                seg.x2 = cell.lowestNeighbor.wx; seg.z2 = cell.lowestNeighbor.wz;
                seg.h1 = cell.height; seg.h2 = cell.lowestNeighbor.height;
                seg.r1 = WIDTH_BASE; seg.r2 = WIDTH_BASE;
                seg.level = 0;
                allSegs.put(key, seg);
            }
        }

        // 找源头并追踪路径
        Set<String> keepSet = new HashSet<>();
        for (Cell cell : cells) {
            if (cell.height < SEA_THRESHOLD) continue;
            boolean hasUpstream = false;
            for (Cell c : cells) {
                if (c.lowestNeighbor == cell) { hasUpstream = true; break; }
            }
            if (!hasUpstream) cell.isSource = true;
        }

        for (Cell cell : cells) {
            if (!cell.isSource) continue;
            List<String> pathKeys = new ArrayList<>();
            Cell cur = cell;
            while (cur != null && cur.lowestNeighbor != null) {
                String k = cur.ci + "," + cur.cj + "->" + cur.lowestNeighbor.ci + "," + cur.lowestNeighbor.cj;
                pathKeys.add(k);
                cur = cur.lowestNeighbor;
                if (pathKeys.size() > 100) break;
            }
            if (pathKeys.size() >= MIN_RIVER_LENGTH) {
                keepSet.addAll(pathKeys);
            }
        }

        for (String key : keepSet) {
            RiverSeg seg = allSegs.get(key);
            if (seg != null) {
                segments.add(seg);
                seg.a.isRiver = true;
                seg.b.isRiver = true;
            }
        }

        // 重新标记源头
        for (Cell cell : cells) cell.isSource = false;
        for (RiverSeg seg : segments) {
            boolean hasUpstream = false;
            for (RiverSeg s : segments) {
                if (s.b == seg.a) { hasUpstream = true; break; }
            }
            if (!hasUpstream) seg.a.isSource = true;
        }

        // 4. 计算河流等级和宽度
        for (Cell cell : cells) {
            if (!cell.isSource || !cell.isRiver) continue;
            int level = 1;
            Cell cur = cell;
            while (cur != null && cur.lowestNeighbor != null && cur.isRiver) {
                cur.riverLevel = Math.max(cur.riverLevel, level);
                Cell next = cur.lowestNeighbor;

                for (RiverSeg seg : segments) {
                    if (seg.a == cur || seg.b == cur) {
                        seg.level = Math.max(seg.level, level);
                        seg.r1 = WIDTH_BASE * (1f + seg.level * WIDTH_PER_LEVEL / 8f);
                        seg.r2 = WIDTH_BASE * (1f + seg.level * WIDTH_PER_LEVEL / 8f);
                    }
                }

                int upstreamCount = 0;
                for (Cell c : cells) {
                    if (c.isRiver && c.lowestNeighbor == next && c != cur) {
                        upstreamCount++;
                    }
                }
                if (upstreamCount > 0) level += upstreamCount;
                cur = next;
            }
        }

        // 5. 生成弯曲段
        for (RiverSeg seg : segments) {
            int h = hash((int)seed + 3333, seg.a.ci, seg.a.cj);
            List<float[]> curved = generateCurvedSegments(seg, h);
            curvedSegs.addAll(curved);
        }

        System.out.println("  Grid: " + GRID_W + "x" + GRID_H + " = " + cells.size() + " cells");
        System.out.println("  River segments: " + segments.size());
        System.out.println("  Curved segments: " + curvedSegs.size());
    }

    List<float[]> generateCurvedSegments(RiverSeg seg, int hash) {
        List<float[]> result = new ArrayList<>();
        float ax = seg.x1, az = seg.z1;
        float bx = seg.x2, bz = seg.z2;
        float mx = (ax + bx) * 0.5f;
        float mz = (az + bz) * 0.5f;
        float cx = (ax + mx) * 0.5f;
        float cz = (az + mz) * 0.5f;
        float nx = -(cz - az);
        float nz = (cx - ax);
        float dir = hashF(hash, 2) < 0.5f ? -1f : 1f;
        float amp0 = 0.7f + hashF(hash, 3) * 0.3f;
        float displacement = MEANDER_AMP * dir * amp0;
        cx += nx * displacement;
        cz += nz * displacement;
        float mh = (seg.h1 + seg.h2) * 0.5f;
        float ch = (seg.h1 + mh) * 0.5f;
        float mr = (seg.r1 + seg.r2) * 0.5f;
        float cr = (seg.r1 + mr) * 0.5f;
        float warp1 = WARP_STRENGTH * amp0 * (hashF(hash, 4) - 0.5f);
        float warp2 = WARP_STRENGTH * amp0 * (hashF(hash, 5) - 0.5f);
        result.add(new float[]{ax, az, cx + warp1, cz + warp1 * 0.5f, seg.h1, ch, seg.r1, cr, seg.level});
        result.add(new float[]{cx + warp1, cz + warp1 * 0.5f, mx + warp2, mz + warp2 * 0.5f, ch, mh, cr, mr, seg.level});
        return result;
    }

    // ============ Phase 2: 初始化地形并标记主河道 ============
    void initTerrain() {
        System.out.println("=== Phase 2: Initializing terrain ===");

        float scale = (float)VIEW_SIZE / Math.max(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                height[y][x] = terrain.computeHeight((x - w/2f) * scale, (y - h/2f) * scale);
            }
        }

        // 标记主河道掩码
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float wx = (x - w/2f) * scale;
                float wz = (y - h/2f) * scale;

                // 找最近的主河道段
                float minDist = Float.MAX_VALUE;
                float bestWidth = 0;
                for (float[] s : curvedSegs) {
                    float sx1 = s[0], sz1 = s[1], sx2 = s[2], sz2 = s[3];
                    float segWidth = Math.max(s[6], s[7]) * 2f;
                    if (wx < Math.min(sx1, sx2) - segWidth || wx > Math.max(sx1, sx2) + segWidth
                        || wz < Math.min(sz1, sz2) - segWidth || wz > Math.max(sz1, sz2) + segWidth)
                        continue;
                    float dx = sx2 - sx1, dz = sz2 - sz1;
                    float len2 = dx*dx + dz*dz;
                    float t = len2 > 0 ? ((wx - sx1)*dx + (wz - sz1)*dz) / len2 : 0;
                    t = Math.max(0, Math.min(1, t));
                    float cx = sx1 + t*dx, cz = sz1 + t*dz;
                    float dist = (float)Math.hypot(wx - cx, wz - cz);
                    if (dist < minDist) {
                        minDist = dist;
                        bestWidth = segWidth;
                    }
                }

                // 如果在主河道附近，标记掩码并雕刻地形
                if (minDist < bestWidth * 0.5f) {
                    float depth = MAIN_RIVER_DEPTH * (1f - minDist / (bestWidth * 0.5f));
                    riverMask[y][x] = 1f;
                    height[y][x] -= depth;
                }
            }
        }

        System.out.println("  Terrain initialized with main river carving");
    }

    // ============ Phase 3: SimpleHydrology 支流侵蚀 ============
    void erodeTributaries() {
        System.out.println("=== Phase 3: Eroding tributaries (SimpleHydrology) ===");

        for (int iter = 0; iter < SUB_ITERATIONS; iter++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    dischargeTrack[y][x] = 0;
                    mxTrack[y][x] = 0;
                    myTrack[y][x] = 0;
                }
            }

            for (int i = 0; i < SUB_DROPS_PER_ITER; i++) {
                float px = rng.nextFloat() * w;
                float py = rng.nextFloat() * h;

                // 只在陆地上撒粒子，且不在主河道上
                if (getH((int)px, (int)py) < SEA_THRESHOLD) continue;
                if (riverMask[(int)py][(int)px] > 0.5f) continue;

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
                System.out.println("  Iter " + (iter+1) + "/" + SUB_ITERATIONS + "  maxD=" + fmt1(maxD));
            }
        }

        float maxD = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                maxD = Math.max(maxD, discharge[y][x]);
            }
        }
        System.out.println("  Max discharge: " + fmt1(maxD));
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
        int[][] n = {{-1,-1}, {-1,0}, {-1,1}, {0,-1}, {0,1}, {1,-1}, {1,0}, {1,1}};
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
        float cappedDischarge = Math.min(nodeDischarge, TRIBUTARY_CAP);
        float c_eq = (1.0f + ENTRAINMENT * cappedDischarge) * (height[iy][ix] - h2);
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

    // ============ Phase 4: 渲染 ============
    void renderAndSave(String filePath) throws Exception {
        System.out.println("=== Phase 4: Rendering ===");

        int sz = OUTPUT_SIZE;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_RGB);
        float renderScale = (float)VIEW_SIZE / sz;
        float mapScale = (float)w / sz;

        // 计算 discharge 统计
        float maxD = 0;
        List<Float> allD = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                maxD = Math.max(maxD, discharge[y][x]);
                if (discharge[y][x] > 0) allD.add(discharge[y][x]);
            }
        }
        Collections.sort(allD);
        float p90 = allD.size() > 0 ? allD.get((int)(allD.size() * 0.90f)) : 0;
        float p95 = allD.size() > 0 ? allD.get((int)(allD.size() * 0.95f)) : 0;
        float p98 = allD.size() > 0 ? allD.get((int)(allD.size() * 0.98f)) : 0;
        System.out.println("  Discharge: max=" + fmt1(maxD) + " p90=" + fmt1(p90) + " p95=" + fmt1(p95) + " p98=" + fmt1(p98));

        // 预计算主河道的距离场 - 使用更密集的采样点
        List<float[]> riverPoints = new ArrayList<>();
        for (float[] s : curvedSegs) {
            float sx1 = s[0], sz1 = s[1], sx2 = s[2], sz2 = s[3];
            float r = Math.max(s[6], s[7]);
            int steps = (int)(Math.hypot(sx2-sx1, sz2-sz1) / 10f) + 1;
            for (int i = 0; i <= steps; i++) {
                float t = i / (float)steps;
                riverPoints.add(new float[]{
                    sx1 + t*(sx2-sx1),
                    sz1 + t*(sz2-sz1),
                    r,
                    s[8]  // level
                });
            }
        }
        System.out.println("  River points: " + riverPoints.size());

        for (int py = 0; py < sz; py++) {
            for (int px = 0; px < sz; px++) {
                float wx = (px - sz/2f) * renderScale;
                float wz = (py - sz/2f) * renderScale;

                // 地形颜色
                float terrainH = terrain.computeHeight(wx, wz);
                int color = getTerrainColor(terrainH);

                // 检查是否在主河道上 - 使用点距离而非线段距离
                float minDist = Float.MAX_VALUE;
                float bestRadius = 0;
                int bestLevel = 0;

                for (float[] p : riverPoints) {
                    float dx = wx - p[0];
                    float dz = wz - p[1];
                    float dist = dx*dx + dz*dz;
                    if (dist < minDist) {
                        minDist = dist;
                        bestRadius = p[2];
                        bestLevel = (int)p[3];
                    }
                }
                minDist = (float)Math.sqrt(minDist);

                // 渲染主河道 - 使用平滑渐变
                if (minDist < bestRadius * 1.5f) {
                    float d = minDist / (bestRadius * 1.5f);
                    // 河床中心 = 深蓝，边缘 = 浅蓝
                    int r = (int)(10 + d * 30);
                    int g = (int)(30 + d * 70);
                    int b = (int)(150 + d * 80);
                    color = rgb(r, g, b);
                }

                // 渲染支流（基于 discharge）- 只在非主河道区域
                int mx = (int)(px * mapScale);
                int my = (int)(py * mapScale);
                if (mx < w && my < h && minDist >= bestRadius * 1.5f) {
                    float d = discharge[my][mx];
                    // 使用更高的阈值过滤噪声
                    if (d > p95) {
                        float t = (float)Math.log1p(d - p95) / (float)Math.log1p(maxD - p95 + 1);
                        t = Math.min(1, t);
                        int r = (int)(15 + t * 25);
                        int g = (int)(40 + t * 90);
                        int b = (int)(130 + t * 125);
                        color = rgb(r, g, b);
                    }
                }

                img.setRGB(px, py, color);
            }
        }

        ImageIO.write(img, "png", new File(filePath));
        System.out.println("Saved: " + filePath);
    }

    // ============ 辅助方法 ============
    Cell getCell(int ci, int cj) {
        for (Cell c : cells) {
            if (c.ci == ci && c.cj == cj) return c;
        }
        return null;
    }

    int hash(int seedVal, int x, int y) {
        int h = seedVal + x * 374761393 + y * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return h ^ (h >> 16);
    }

    float hashF(int hash, int offset) {
        int h = hash + offset * 7919;
        h = (h ^ (h >> 13)) * 1274126177;
        h = h ^ (h >> 16);
        return (h & 0x7FFFFFFF) / (float)0x7FFFFFFF;
    }

    int getTerrainColor(float h) {
        if (h < SEA_THRESHOLD - 0.05f) {
            return rgb(10, 40, Math.min(255, 80 + (int)((SEA_THRESHOLD - h) * 240)));
        } else if (h < SEA_THRESHOLD) {
            return rgb(190, 180, 60);
        } else {
            int v = (int)((h - SEA_THRESHOLD) / (1f - SEA_THRESHOLD) * 200) + 55;
            return rgb(v, v, v);
        }
    }

    static int rgb(int r, int g, int b) { return (r<<16) | (g<<8) | b; }
    static String fmt1(float v) { return String.format("%.1f", v); }

    // ============ 主流程 ============
    void generate() {
        planMainRivers();
        initTerrain();
        erodeTributaries();
    }

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        String tag = "v1";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed": seed = Integer.parseInt(args[++i]); break;
                case "--tag": tag = args[++i]; break;
            }
        }

        HybridRiverGen gen = new HybridRiverGen(seed);
        gen.generate();
        gen.renderAndSave("../output/hybrid_s" + seed + "_" + tag + ".png");
    }
}
