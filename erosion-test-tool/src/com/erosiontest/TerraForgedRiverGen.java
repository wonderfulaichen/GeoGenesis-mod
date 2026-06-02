package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * TerraForged 风格河流生成器
 * 
 * 核心算法（完整移植 TerraForged 0.3.x）：
 * 1. Jittered Grid：规则网格 + hash 随机偏移
 * 2. 每个细胞用噪声值作为海拔
 * 3. 图连接：每个细胞找到最低邻居，从高到低形成河网
 * 4. 河流段：中点垂向位移产生弯曲
 * 5. 距离场渲染：valley/bank/bed 三层
 * 6. 地形雕刻：沿河流压低地形
 */
public class TerraForgedRiverGen {

    // ============ 网格参数 ============
    static int GRID_W = 20;            // 网格宽
    static int GRID_H = 20;            // 网格高
    static float CELL_SPACING = 300f;  // 细胞间距（世界坐标）
    static float JITTER = 0.75f;       // 偏移幅度
    static float SEA_THRESHOLD = 0.35f; // 海洋高度阈值
    static int MIN_RIVER_LENGTH = 4;   // 最小河流段数

    // ============ 弯曲参数 ============
    static float MEANDER_AMP = 0.5f;   // 弯曲幅度（增大产生更明显弯曲）
    static float WARP_STRENGTH = 0.3f; // 噪声 warp 强度

    // ============ 宽度参数 ============
    static float WIDTH_BASE = 6f;      // 基础河宽
    static float WIDTH_PER_LEVEL = 3f; // 每级河流增加的宽度
    static float VALLEY_RATIO = 3.5f;  // 河谷范围倍数
    static float BANK_RATIO = 1.8f;    // 河岸范围倍数

    // ============ 渲染参数 ============
    static int VIEW_SIZE = 6000;
    static final int OUTPUT_SIZE = 2048;

    // ============ 细胞系统 ============
    static class Cell {
        float wx, wz;       // 世界坐标
        float noise;        // 噪声海拔
        float height;       // 计算后的高度
        int ci, cj;         // 网格坐标
        Cell lowestNeighbor; // 最低邻居
        boolean isRiver;    // 是否在河流上
        boolean isSource;   // 是否是源头
        int riverLevel;     // 河流等级（支流=1，干流=2等）
    }

    // ============ 河流段 ============
    static class RiverSeg {
        float x1, z1, x2, z2;
        float h1, h2;       // 端点高度
        float r1, r2;       // 端点半径
        Cell a, b;          // 源细胞和目标细胞
        int level;           // 河流等级
    }

    final long seed;
    final Random rng;
    final StandalonePreview terrain;
    final List<Cell> cells = new ArrayList<>();
    final List<RiverSeg> segments = new ArrayList<>();

    public TerraForgedRiverGen(int seed) {
        this.seed = seed;
        this.rng = new Random(seed + 8888);
        this.terrain = new StandalonePreview(seed);
    }

    // ============ 1. Jittered Grid 初始化 ============
    void buildGrid() {
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
        // 这保证了清晰的大尺度梯度，让河流能长距离流动
        for (Cell cell : cells) {
            if (cell.height <= SEA_THRESHOLD) continue;
            // 计算到最近海洋细胞的距离
            float minDist = Float.MAX_VALUE;
            for (Cell other : cells) {
                if (other.height <= SEA_THRESHOLD) {
                    float dx = cell.wx - other.wx;
                    float dz = cell.wz - other.wz;
                    float d = dx*dx + dz*dz;
                    if (d < minDist) minDist = d;
                }
            }
            // 高度 = 距离归一化（越远越高）
            float maxDist = (float)Math.hypot(GRID_W * CELL_SPACING, GRID_H * CELL_SPACING);
            cell.height = Math.max(SEA_THRESHOLD, 0.3f + (1f - (float)Math.sqrt(minDist) / maxDist) * 0.6f);
        }

        System.out.println("Grid: " + GRID_W + "x" + GRID_H + " = " + cells.size() + " cells");
    }

    // ============ 2. 图连接（D8 无条件 + 路径过滤）============
    void buildConnections() {
        int[][] dirs = {{1,0}, {0,1}, {-1,0}, {0,-1},
                        {1,1}, {-1,1}, {1,-1}, {-1,-1}}; // D8

        // 第1步：每个细胞无条件连接到最低邻居
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

        // 第2步：从每个源头开始追踪完整路径，只保留长路径（>= MIN_RIVER_LENGTH）
        Set<String> keepSet = new HashSet<>();
        Set<String> allSet = new HashSet<>();

        // 先标记所有连接
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

        // 找所有源头（没有上游指向它的陆地细胞）
        for (Cell cell : cells) {
            if (cell.height < SEA_THRESHOLD) continue;
            boolean hasUpstream = false;
            for (Cell c : cells) {
                if (c.lowestNeighbor == cell) { hasUpstream = true; break; }
            }
            if (!hasUpstream) cell.isSource = true;
        }

        // 从每个源头追踪下游，记录经过的连接
        int longRivers = 0;
        for (Cell cell : cells) {
            if (!cell.isSource) continue;
            List<String> pathKeys = new ArrayList<>();
            Cell cur = cell;
            while (cur != null && cur.lowestNeighbor != null) {
                String k = cur.ci + "," + cur.cj + "->" + cur.lowestNeighbor.ci + "," + cur.lowestNeighbor.cj;
                pathKeys.add(k);
                cur = cur.lowestNeighbor;
                if (pathKeys.size() > 100) break; // 安全上限
            }
            if (pathKeys.size() >= MIN_RIVER_LENGTH) {
                keepSet.addAll(pathKeys);
                longRivers++;
            }
        }

        // 用保留的连接创建段
        for (String key : keepSet) {
            RiverSeg seg = allSegs.get(key);
            if (seg != null) {
                segments.add(seg);
                seg.a.isRiver = true;
                seg.b.isRiver = true;
            }
        }

        // 重新标记源头（只有那些在保留路径中的源头）
        for (Cell cell : cells) cell.isSource = false;
        for (RiverSeg seg : segments) {
            boolean hasUpstream = false;
            for (RiverSeg s : segments) {
                if (s.b == seg.a) { hasUpstream = true; break; }
            }
            if (!hasUpstream) seg.a.isSource = true;
        }

        System.out.println("River segments: " + segments.size() + "  Long rivers: " + longRivers);
    }

    // ============ 3. 河流等级 + 宽度计算 ============
    void assignLevels() {
        // 从源头开始，下游递增等级
        for (Cell cell : cells) {
            if (!cell.isSource || !cell.isRiver) continue;
            int level = 1;
            Cell cur = cell;
            while (cur != null && cur.lowestNeighbor != null && cur.isRiver) {
                cur.riverLevel = Math.max(cur.riverLevel, level);
                Cell next = cur.lowestNeighbor;
                // 宽度基于等级
                float r = 1f + level * 0.5f;
                cur.riverLevel = level;

                // 更新该细胞出发的段
                for (RiverSeg seg : segments) {
                    if (seg.a == cur || seg.b == cur) {
                        seg.level = Math.max(seg.level, level);
                        seg.r1 = WIDTH_BASE * (1f + seg.level * WIDTH_PER_LEVEL / 8f);
                        seg.r2 = WIDTH_BASE * (1f + seg.level * WIDTH_PER_LEVEL / 8f);
                    }
                }

                // 计数上游合并
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
    }

    // ============ 4. 生成弯曲河流段 ============
    List<float[]> generateCurvedSegments(RiverSeg seg, int hash) {
        List<float[]> result = new ArrayList<>();

        float ax = seg.x1, az = seg.z1;
        float bx = seg.x2, bz = seg.z2;

        // 中点
        float mx = (ax + bx) * 0.5f;
        float mz = (az + bz) * 0.5f;

        // A到M的1/4点 C = (A + M)/2
        float cx = (ax + mx) * 0.5f;
        float cz = (az + mz) * 0.5f;

        // AC的垂线方向
        float nx = -(cz - az);
        float nz = (cx - ax);

        // 随机方向
        float dir = hashF(hash, 2) < 0.5f ? -1f : 1f;
        float amp0 = 0.7f + hashF(hash, 3) * 0.3f;

        float displacement = MEANDER_AMP * dir * amp0;
        cx += nx * displacement;
        cz += nz * displacement;

        // 中间高度
        float mh = (seg.h1 + seg.h2) * 0.5f;
        float ch = (seg.h1 + mh) * 0.5f;
        float mr = (seg.r1 + seg.r2) * 0.5f;
        float cr = (seg.r1 + mr) * 0.5f;

        // 二次 warp
        float warp1 = WARP_STRENGTH * amp0 * (hashF(hash, 4) - 0.5f);
        float warp2 = WARP_STRENGTH * amp0 * (hashF(hash, 5) - 0.5f);

        result.add(new float[]{ax, az, cx + warp1, cz + warp1 * 0.5f, seg.h1, ch, seg.r1, cr, seg.level});
        result.add(new float[]{cx + warp1, cz + warp1 * 0.5f, mx + warp2, mz + warp2 * 0.5f, ch, mh, cr, mr, seg.level});

        return result;
    }

    // ============ 5. 主生成流程 ============
    void generate() {
        buildGrid();
        buildConnections();
        assignLevels();

        // 统计
        int totalSegs = segments.size();
        int sourceCount = (int)cells.stream().filter(c -> c.isSource).count();
        int riverCells = (int)cells.stream().filter(c -> c.isRiver).count();
        System.out.println("Sources: " + sourceCount + "  River cells: " + riverCells);
    }

    // ============ 6. 渲染 ============
    void renderAndSave(String filePath) throws Exception {
        generate();

        int sz = OUTPUT_SIZE;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_RGB);
        float renderScale = (float)VIEW_SIZE / sz;

        // 预计算所有弯曲段
        List<float[]> curvedSegs = new ArrayList<>();
        int segIdx = 0;
        for (RiverSeg seg : segments) {
            int hash = hash((int)seed + 3333, seg.a.ci, seg.a.cj);
            List<float[]> curved = generateCurvedSegments(seg, hash);
            curvedSegs.addAll(curved);
            segIdx++;
        }
        System.out.println("Curved segments: " + curvedSegs.size());

        // 渲染地形 + 河流
        System.out.println("Rendering...");
        float[][] segArray = new float[curvedSegs.size()][];
        for (int i = 0; i < curvedSegs.size(); i++) segArray[i] = curvedSegs.get(i);

        for (int py = 0; py < sz; py++) {
            float wz = (py - sz/2f) * renderScale;
            for (int px = 0; px < sz; px++) {
                float wx = (px - sz/2f) * renderScale;

                // 地形
                float h = terrain.computeHeight(wx, wz);
                int color = getTerrainColor(h);

                // 距离场找最近河流段
                float minDist = Float.MAX_VALUE;
                float bestWidth = 0;
                float bestT = 0;
                int bestLevel = 0;

                for (float[] s : segArray) {
                    float w = Math.max(s[6], s[7]) * VALLEY_RATIO;
                    float sx1 = s[0], sz1 = s[1], sx2 = s[2], sz2 = s[3];
                    
                    // AABB 快速剔除
                    if (wx < Math.min(sx1, sx2) - w || wx > Math.max(sx1, sx2) + w
                        || wz < Math.min(sz1, sz2) - w || wz > Math.max(sz1, sz2) + w)
                        continue;

                    float dx = sx2 - sx1, dz = sz2 - sz1;
                    float len2 = dx*dx + dz*dz;
                    float t = len2 > 0 ? ((wx - sx1)*dx + (wz - sz1)*dz) / len2 : 0;
                    t = Math.max(0, Math.min(1, t));
                    float cx = sx1 + t*dx, cz = sz1 + t*dz;
                    float dist = (float)Math.hypot(wx - cx, wz - cz);

                    if (dist < minDist) {
                        minDist = dist;
                        bestWidth = w * (1 - t) + w * t; // 插值宽度
                        bestT = t;
                        bestLevel = (int)s[8];
                    }
                }

                if (minDist < bestWidth) {
                    float levelFactor = 1f + bestLevel * 0.2f;
                    float valleyR = bestWidth * levelFactor;

                    if (minDist < valleyR * BANK_RATIO / VALLEY_RATIO) {
                        // 河床/河岸
                        float d = minDist / (bestWidth * BANK_RATIO / VALLEY_RATIO);
                        d = Math.min(1, d);
                        int r = (int)(20 + d * 40);
                        int g = (int)(50 + d * 60);
                        int b = (int)(180 + d * 50);
                        color = rgb(r, g, b);
                    } else {
                        // 河谷
                        float d = (minDist - bestWidth * BANK_RATIO / VALLEY_RATIO)
                            / (bestWidth - bestWidth * BANK_RATIO / VALLEY_RATIO);
                        d = Math.min(1, d);
                        int r = (int)(60 + d * 80);
                        int g = (int)(110 - d * 30);
                        int b = (int)(230 - d * 140);
                        color = rgb(r, g, b);
                    }
                }

                img.setRGB(px, py, color);
            }
        }

        ImageIO.write(img, "png", new File(filePath));
        System.out.println("Saved: " + filePath);
    }

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

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        int gridW = 20, gridH = 20;
        float spacing = 300f;
        String tag = "tf1";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed": seed = Integer.parseInt(args[++i]); break;
                case "--grid": gridW = gridH = Integer.parseInt(args[++i]); break;
                case "--spacing": spacing = Float.parseFloat(args[++i]); break;
                case "--tag": tag = args[++i]; break;
            }
        }
        GRID_W = gridW; GRID_H = gridH; CELL_SPACING = spacing;

        TerraForgedRiverGen gen = new TerraForgedRiverGen(seed);
        gen.renderAndSave("../output/terra_s" + seed + "_" + tag + ".png");
    }
}
