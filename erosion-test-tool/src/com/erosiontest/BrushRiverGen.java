package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * 笔刷河流生成器
 * 
 * 核心思路：
 * 1. TerraForged 规划多级河道路径（主河道 → 一级支流 → 二级支流）
 * 2. 沿路径使用多尺度圆形笔刷直接雕刻地形
 * 3. 笔刷半径和深度随河流等级变化
 * 4. 无需粒子，直接笔刷侵蚀
 * 
 * vs SimpleHydrology（粒子侵蚀）：
 * - 粒子侵蚀需要数十万粒子才能形成清晰河道
 * - 笔刷直接按规划路径雕刻，效率高且可控
 * 
 * vs 纯 TerraForged（画线）：
 * - 画线只改变渲染颜色，不改变地形
 * - 笔刷实际修改地形高度，形成凹陷河道
 */
public class BrushRiverGen {

    // ============ 河流层级配置 ============
    static float SEA_THRESHOLD = 0.35f;
    static int VIEW_SIZE = 6000;
    static final int OUTPUT_SIZE = 1024;

    static class RiverLevelConfig {
        int gridW, gridH;
        float cellSpacing;
        float jitter;
        int minLength;
        float meanderAmp;
        float widthBase;
        int brushRadius;     // 笔刷半径（像素）
        float brushDepth;    // 笔刷深度
        int passes;          // 笔刷遍数

        RiverLevelConfig(int gw, int gh, float cs, float j, int ml, float ma, float wb, int br, float bd, int p) {
            gridW = gw; gridH = gh;
            cellSpacing = cs; jitter = j;
            minLength = ml; meanderAmp = ma;
            widthBase = wb; brushRadius = br;
            brushDepth = bd; passes = p;
        }
    }

    static RiverLevelConfig[] LEVELS = {
        // 主河道：笔刷半径4，深度0.008
        new RiverLevelConfig(24, 24, 250f, 0.75f, 5, 0.6f, 8f, 4, 0.008f, 1),
        // 一级支流：笔刷半径2，深度0.005
        new RiverLevelConfig(48, 48, 125f, 0.6f, 3, 0.4f, 4f, 2, 0.005f, 1),
        // 二级支流：笔刷半径1，深度0.003
        new RiverLevelConfig(96, 96, 62f, 0.5f, 2, 0.3f, 2f, 1, 0.003f, 1),
    };

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
        int hierarchy;
    }

    final long seed;
    final Random rng;
    final StandalonePreview terrain;
    final List<RiverSeg> allSegments = new ArrayList<>();
    final List<float[]> allCurvedSegs = new ArrayList<>();

    // 地形高度图
    final int mapSize = 512;
    final float[][] heightMap;
    final float scale;

    public BrushRiverGen(int seed) {
        this.seed = seed;
        this.rng = new Random(seed + 8888);
        this.terrain = new StandalonePreview(seed);
        this.heightMap = new float[mapSize][mapSize];
        this.scale = (float)VIEW_SIZE / mapSize;
    }

    // ============ Phase 1: 初始化地形 ============
    void initTerrain() {
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                float wx = (x - mapSize/2f) * scale;
                float wz = (y - mapSize/2f) * scale;
                heightMap[y][x] = terrain.computeHeight(wx, wz);
            }
        }
        System.out.println("Terrain initialized: " + mapSize + "x" + mapSize);
    }

    // ============ Phase 2: TerraForged 河网规划 ============
    void generateLevel(int levelIdx, RiverLevelConfig config) {
        System.out.println("=== Generating Level " + levelIdx + " ===");

        List<Cell> cells = new ArrayList<>();
        List<RiverSeg> segments = new ArrayList<>();
        List<float[]> curvedSegs = new ArrayList<>();

        int gridSeed = (int)seed + 1111 + levelIdx * 777;

        for (int ci = 0; ci < config.gridW; ci++) {
            for (int cj = 0; cj < config.gridH; cj++) {
                int hash = hash(gridSeed, ci, cj);
                float jx = (cj & 1) == 0 ? 0.5f : 0f;
                float ox = (hashF(hash, 0) - 0.5f) * config.jitter;
                float oz = (hashF(hash, 1) - 0.5f) * config.jitter;

                float wx = (ci + jx + ox) * config.cellSpacing - config.gridW * config.cellSpacing / 2f;
                float wz = (cj + oz) * config.cellSpacing - config.gridH * config.cellSpacing / 2f;

                Cell cell = new Cell();
                cell.wx = wx; cell.wz = wz;
                cell.noise = terrain.computeHeight(wx, wz);
                cell.height = cell.noise;
                cell.ci = ci; cell.cj = cj;
                cells.add(cell);
            }
        }

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
            float maxDist = (float)Math.hypot(config.gridW * config.cellSpacing, config.gridH * config.cellSpacing);
            cell.height = Math.max(SEA_THRESHOLD, 0.3f + (1f - (float)Math.sqrt(minDist) / maxDist) * 0.6f);
        }

        int[][] dirs = {{1,0}, {0,1}, {-1,0}, {0,-1}, {1,1}, {-1,1}, {1,-1}, {-1,-1}};
        for (Cell cell : cells) {
            if (cell.height < SEA_THRESHOLD) continue;
            Cell lowest = null;
            float lowestH = Float.MAX_VALUE;
            for (int[] d : dirs) {
                Cell nb = getCell(cells, cell.ci + d[0], cell.cj + d[1]);
                if (nb == null) continue;
                if (nb.height < lowestH) {
                    lowestH = nb.height;
                    lowest = nb;
                }
            }
            cell.lowestNeighbor = (lowest != null && lowest.height < cell.height) ? lowest : null;
        }

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
                seg.r1 = config.widthBase; seg.r2 = config.widthBase;
                seg.level = 0;
                seg.hierarchy = levelIdx;
                allSegs.put(key, seg);
            }
        }

        for (Cell cell : cells) {
            if (cell.height < SEA_THRESHOLD) continue;
            boolean hasUpstream = false;
            for (Cell c : cells) {
                if (c.lowestNeighbor == cell) { hasUpstream = true; break; }
            }
            if (!hasUpstream) cell.isSource = true;
        }

        Set<String> keepSet = new HashSet<>();
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
            if (pathKeys.size() >= config.minLength) {
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

        for (Cell cell : cells) cell.isSource = false;
        for (RiverSeg seg : segments) {
            boolean hasUpstream = false;
            for (RiverSeg s : segments) {
                if (s.b == seg.a) { hasUpstream = true; break; }
            }
            if (!hasUpstream) seg.a.isSource = true;
        }

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
                        seg.r1 = config.widthBase * (1f + seg.level * 0.5f);
                        seg.r2 = config.widthBase * (1f + seg.level * 0.5f);
                    }
                }
                int upstreamCount = 0;
                for (Cell c : cells) {
                    if (c.isRiver && c.lowestNeighbor == next && c != cur) upstreamCount++;
                }
                if (upstreamCount > 0) level += upstreamCount;
                cur = next;
            }
        }

        for (RiverSeg seg : segments) {
            int h = hash((int)seed + 3333 + levelIdx * 111, seg.a.ci, seg.a.cj);
            List<float[]> curved = generateCurvedSegments(seg, h, config.meanderAmp);
            curvedSegs.addAll(curved);
        }

        allSegments.addAll(segments);
        allCurvedSegs.addAll(curvedSegs);

        System.out.println("  Grid: " + config.gridW + "x" + config.gridH + " = " + cells.size());
        System.out.println("  Segments: " + segments.size() + "  Curved: " + curvedSegs.size());
    }

    List<float[]> generateCurvedSegments(RiverSeg seg, int hashVal, float meanderAmp) {
        List<float[]> result = new ArrayList<>();
        float ax = seg.x1, az = seg.z1, bx = seg.x2, bz = seg.z2;
        float mx = (ax + bx) * 0.5f, mz = (az + bz) * 0.5f;
        float cx = (ax + mx) * 0.5f, cz = (az + mz) * 0.5f;
        float nx = -(cz - az), nz = (cx - ax);
        float dir = hashF(hashVal, 2) < 0.5f ? -1f : 1f;
        float amp0 = 0.7f + hashF(hashVal, 3) * 0.3f;
        float displacement = meanderAmp * dir * amp0;
        cx += nx * displacement; cz += nz * displacement;
        float mh = (seg.h1 + seg.h2) * 0.5f, ch = (seg.h1 + mh) * 0.5f;
        float mr = (seg.r1 + seg.r2) * 0.5f, cr = (seg.r1 + mr) * 0.5f;
        float warp1 = 0.2f * amp0 * (hashF(hashVal, 4) - 0.5f);
        float warp2 = 0.2f * amp0 * (hashF(hashVal, 5) - 0.5f);
        result.add(new float[]{ax, az, cx + warp1, cz + warp1 * 0.5f, seg.h1, ch, seg.r1, cr, seg.level, seg.hierarchy});
        result.add(new float[]{cx + warp1, cz + warp1 * 0.5f, mx + warp2, mz + warp2 * 0.5f, ch, mh, cr, mr, seg.level, seg.hierarchy});
        return result;
    }

    // ============ Phase 3: 笔刷雕刻地形 ============
    void carveRivers() {
        System.out.println("=== Phase 3: Carving rivers with brush ===");

        // 对每个层级，沿弯曲段使用圆形笔刷雕刻地形（先雕刻小河，再雕刻大河覆盖）
        for (int levelIdx = LEVELS.length - 1; levelIdx >= 0; levelIdx--) {
            RiverLevelConfig config = LEVELS[levelIdx];
            List<float[]> levelSegs = new ArrayList<>();

            for (float[] s : allCurvedSegs) {
                if ((int)s[9] == levelIdx) {
                    levelSegs.add(s);
                }
            }

            int count = 0;
            for (float[] s : levelSegs) {
                float sx1 = s[0], sz1 = s[1], sx2 = s[2], sz2 = s[3];
                float segLevel = s[8];
                float hierarchy = s[9];

                // 根据河流等级调整笔刷参数
                float depthMul = 1f + segLevel * 0.3f;
                float brushDepth = config.brushDepth * depthMul;
                int brushRadius = config.brushRadius;

                // 粒度：沿线段每 2 像素一个雕刻点
                float segLen = (float)Math.hypot(sx2 - sx1, sz2 - sz1);
                int steps = Math.max(1, (int)(segLen / (brushRadius * 0.5f)));

                for (int i = 0; i <= steps; i++) {
                    float t = i / (float)steps;
                    float wx = sx1 + t * (sx2 - sx1);
                    float wz = sz1 + t * (sz2 - sz1);

                    // 世界坐标转地图像素坐标
                    int mx = (int)((wx / VIEW_SIZE + 0.5f) * mapSize);
                    int my = (int)((wz / VIEW_SIZE + 0.5f) * mapSize);

                    // 应用圆形笔刷
                    applyBrush(mx, my, brushRadius, brushDepth);
                }
                count++;
            }

            // 平滑河道
            smoothRiverChannel(config.brushRadius);

            System.out.println("  Level " + levelIdx + ": carved " + count + " segments (brush r=" + config.brushRadius + ", depth=" + config.brushDepth + ")");
        }

        // 限制高度不超出范围
        for (int y = 0; y < mapSize; y++)
            for (int x = 0; x < mapSize; x++)
                heightMap[y][x] = Math.max(0f, Math.min(1f, heightMap[y][x]));
    }

    void applyBrush(int cx, int cy, int radius, float depth) {
        int r2 = radius * radius;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int px = cx + dx;
                int py = cy + dy;
                if (px < 0 || px >= mapSize || py < 0 || py >= mapSize) continue;

                float d2 = dx*dx + dy*dy;
                if (d2 < r2) {
                    // 距离越近挖得越深（中心最深）
                    float factor = 1f - (float)Math.sqrt(d2) / radius;
                    float carve = depth * factor * factor; // 二次衰减更平滑
                    heightMap[py][px] -= carve;
                }
            }
        }
    }

    void smoothRiverChannel(int brushRadius) {
        // 轻量平滑，消除笔刷产生的锯齿
        int smoothR = Math.max(1, brushRadius / 2);
        float[][] smoothed = new float[mapSize][mapSize];
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                float sum = 0, wsum = 0;
                for (int dy = -smoothR; dy <= smoothR; dy++) {
                    for (int dx = -smoothR; dx <= smoothR; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < mapSize && ny >= 0 && ny < mapSize) {
                            float w = 1f;
                            sum += heightMap[ny][nx] * w;
                            wsum += w;
                        }
                    }
                }
                smoothed[y][x] = sum / wsum;
            }
        }
        // 只混合平滑后的河道区域
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                heightMap[y][x] = heightMap[y][x] * 0.3f + smoothed[y][x] * 0.7f;
            }
        }
    }

    // ============ Phase 4: 对比渲染 ============
    void renderAndSave(String filePath) throws Exception {
        System.out.println("=== Rendering comparison ===");

        // 已经在 initTerrain 中保存了原始地形到 originalHeight，现在复制
        float[][] original = new float[mapSize][mapSize];
        for (int y = 0; y < mapSize; y++)
            for (int x = 0; x < mapSize; x++)
                original[y][x] = terrain.computeHeight((x - mapSize/2f) * scale, (y - mapSize/2f) * scale);

        int sz = OUTPUT_SIZE;
        int gap = 8;
        int panelW = sz;
        int panelH = sz;
        int imgW = panelW * 3 + gap * 2;
        int imgH = panelH + 80; // 底部留文字空间
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        img.getGraphics().fillRect(0, 0, imgW, imgH);

        float renderScale = (float)VIEW_SIZE / sz;

        // 计算高度范围
        float minH = Float.MAX_VALUE, maxH = -Float.MAX_VALUE;
        float maxDelta = 0;
        for (int py = 0; py < sz; py++) {
            for (int px = 0; px < sz; px++) {
                float wx = (px - sz/2f) * renderScale;
                float wz = (py - sz/2f) * renderScale;
                int mx = clamp((int)((wx / VIEW_SIZE + 0.5f) * mapSize), 0, mapSize-1);
                int my = clamp((int)((wz / VIEW_SIZE + 0.5f) * mapSize), 0, mapSize-1);
                minH = Math.min(minH, Math.min(original[my][mx], heightMap[my][mx]));
                maxH = Math.max(maxH, Math.max(original[my][mx], heightMap[my][mx]));
                maxDelta = Math.max(maxDelta, Math.abs(heightMap[my][mx] - original[my][mx]));
            }
        }
        float range = maxH - minH;
        if (range < 0.01f) range = 0.01f;
        System.out.println("  Height range: " + fmt(minH) + " ~ " + fmt(maxH) + "  maxDelta=" + fmt(maxDelta));

        // 渲染三面板
        for (int py = 0; py < sz; py++) {
            for (int px = 0; px < sz; px++) {
                float wx = (px - sz/2f) * renderScale;
                float wz = (py - sz/2f) * renderScale;
                int mx = clamp((int)((wx / VIEW_SIZE + 0.5f) * mapSize), 0, mapSize-1);
                int my = clamp((int)((wz / VIEW_SIZE + 0.5f) * mapSize), 0, mapSize-1);

                float ho = original[my][mx];
                float hc = heightMap[my][mx];
                float delta = hc - ho;

                // 第1列：原始地形
                img.setRGB(px, py, getTerrainColor(ho));

                // 第2列：雕刻后
                img.setRGB(panelW + gap + px, py, getTerrainColor(hc));

                // 第3列：差异（红=沉积, 绿=侵蚀）
                img.setRGB((panelW + gap) * 2 + px, py, deltaColor(delta, maxDelta));
            }
        }

        // 文字标签
        drawLabel(img, "原始地形", 10, 2, 0xFFFFFF);
        drawLabel(img, "笔刷雕刻后", panelW + gap + 10, 2, 0xFFFFFF);
        drawLabel(img, "差异（红=沉积 绿=侵蚀）", (panelW + gap) * 2 + 10, 2, 0xFFFFFF);

        // 底部统计信息
        String info = "Seed=" + seed + " L0:r4/d0.008  L1:r2/d0.005  L2:r1/d0.003";
        drawLabel(img, info, 10, sz + 2, 0xAAAAAA);

        ImageIO.write(img, "png", new File(filePath));
        System.out.println("Saved: " + filePath);
    }

    int deltaColor(float delta, float maxDelta) {
        if (maxDelta < 0.001f) return 0x808080;
        float t = delta / maxDelta * 2f; // -2 ~ 2, 映射到 0~1
        // 负 = 侵蚀（绿色），正 = 沉积（红色）
        if (delta < 0) {
            float d = Math.min(1f, -delta / maxDelta);
            int v = (int)(d * 255);
            return rgb(0, v, 0);
        } else {
            float d = Math.min(1f, delta / maxDelta);
            int v = (int)(d * 255);
            return rgb(v, 0, 0);
        }
    }

    void drawLabel(BufferedImage img, String text, int x, int y, int color) {
        // 简单像素文字：不依赖字体，只画一个背景条
        java.awt.Graphics g = img.getGraphics();
        g.setColor(new java.awt.Color(color));
        g.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
        g.drawString(text, x, y + 12);
        g.dispose();
    }

    int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    static String fmt(float v) { return String.format("%.3f", v); }

    // ============ 辅助方法 ============
    Cell getCell(List<Cell> cells, int ci, int cj) {
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

    float hashF(int hashVal, int offset) {
        int h = hashVal + offset * 7919;
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

    void generate() {
        initTerrain();
        for (int i = 0; i < LEVELS.length; i++) {
            generateLevel(i, LEVELS[i]);
        }
        carveRivers();
    }

    public static void main(String[] args) throws Exception {
        int seed = 99999;
        String tag = "v1";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed": seed = Integer.parseInt(args[++i]); break;
                case "--tag": tag = args[++i]; break;
            }
        }

        BrushRiverGen gen = new BrushRiverGen(seed);
        gen.generate();
        gen.renderAndSave("../output/brushriver_s" + seed + "_" + tag + ".png");
    }
}
