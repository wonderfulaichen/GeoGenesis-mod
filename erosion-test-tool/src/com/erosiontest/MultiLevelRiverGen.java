package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * 多级河流生成器
 * 
 * 核心思路：
 * 1. 主河道（Level 0）：24x24 网格，长距离，宽
 * 2. 一级支流（Level 1）：48x48 网格，中等距离，中等宽度
 * 3. 二级支流（Level 2）：96x96 网格，短距离，细
 * 
 * 优势：
 * - 真正的树状结构
 * - 每级河流都有自然弯曲
 * - 宽度随级别变化
 * - 性能高效
 */
public class MultiLevelRiverGen {

    // ============ 全局参数 ============
    static float SEA_THRESHOLD = 0.35f;
    static int VIEW_SIZE = 6000;
    static final int OUTPUT_SIZE = 2048;

    // ============ 河流层级配置 ============
    static class RiverLevel {
        int gridW, gridH;
        float cellSpacing;
        float jitter;
        int minLength;
        float meanderAmp;
        float widthBase;
        float widthPerLevel;
        float depth;

        RiverLevel(int gw, int gh, float cs, float j, int ml, float ma, float wb, float wpl, float d) {
            gridW = gw; gridH = gh;
            cellSpacing = cs; jitter = j;
            minLength = ml; meanderAmp = ma;
            widthBase = wb; widthPerLevel = wpl;
            depth = d;
        }
    }

    static RiverLevel[] LEVELS = {
        new RiverLevel(24, 24, 250f, 0.75f, 5, 0.6f, 8f, 4f, 0.08f),   // 主河道
        new RiverLevel(48, 48, 125f, 0.6f, 3, 0.4f, 4f, 2f, 0.05f),   // 一级支流
        new RiverLevel(96, 96, 62f, 0.5f, 2, 0.3f, 2f, 1f, 0.03f),    // 二级支流
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
        int hierarchy; // 0=主河道, 1=一级支流, 2=二级支流
    }

    final long seed;
    final Random rng;
    final StandalonePreview terrain;
    final List<RiverSeg> allSegments = new ArrayList<>();
    final List<float[]> allCurvedSegs = new ArrayList<>();

    public MultiLevelRiverGen(int seed) {
        this.seed = seed;
        this.rng = new Random(seed + 8888);
        this.terrain = new StandalonePreview(seed);
    }

    // ============ 生成单级河流 ============
    void generateLevel(int levelIdx, RiverLevel config) {
        System.out.println("=== Generating Level " + levelIdx + " ===");

        List<Cell> cells = new ArrayList<>();
        List<RiverSeg> segments = new ArrayList<>();
        List<float[]> curvedSegs = new ArrayList<>();

        int gridSeed = (int)seed + 1111 + levelIdx * 777;

        // 1. Jittered Grid
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
            float maxDist = (float)Math.hypot(config.gridW * config.cellSpacing, config.gridH * config.cellSpacing);
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
                Cell nb = getCell(cells, cell.ci + d[0], cell.cj + d[1]);
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
                seg.r1 = config.widthBase; seg.r2 = config.widthBase;
                seg.level = 0;
                seg.hierarchy = levelIdx;
                allSegs.put(key, seg);
            }
        }

        // 找源头
        for (Cell cell : cells) {
            if (cell.height < SEA_THRESHOLD) continue;
            boolean hasUpstream = false;
            for (Cell c : cells) {
                if (c.lowestNeighbor == cell) { hasUpstream = true; break; }
            }
            if (!hasUpstream) cell.isSource = true;
        }

        // 追踪路径并过滤
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

        // 重新标记源头
        for (Cell cell : cells) cell.isSource = false;
        for (RiverSeg seg : segments) {
            boolean hasUpstream = false;
            for (RiverSeg s : segments) {
                if (s.b == seg.a) { hasUpstream = true; break; }
            }
            if (!hasUpstream) seg.a.isSource = true;
        }

        // 计算河流等级
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
                        seg.r1 = config.widthBase * (1f + seg.level * config.widthPerLevel / 8f);
                        seg.r2 = config.widthBase * (1f + seg.level * config.widthPerLevel / 8f);
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

        // 生成弯曲段
        for (RiverSeg seg : segments) {
            int h = hash((int)seed + 3333 + levelIdx * 111, seg.a.ci, seg.a.cj);
            List<float[]> curved = generateCurvedSegments(seg, h, config.meanderAmp);
            curvedSegs.addAll(curved);
        }

        allSegments.addAll(segments);
        allCurvedSegs.addAll(curvedSegs);

        System.out.println("  Grid: " + config.gridW + "x" + config.gridH + " = " + cells.size() + " cells");
        System.out.println("  River segments: " + segments.size());
        System.out.println("  Curved segments: " + curvedSegs.size());
    }

    List<float[]> generateCurvedSegments(RiverSeg seg, int hash, float meanderAmp) {
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
        float displacement = meanderAmp * dir * amp0;
        cx += nx * displacement;
        cz += nz * displacement;
        float mh = (seg.h1 + seg.h2) * 0.5f;
        float ch = (seg.h1 + mh) * 0.5f;
        float mr = (seg.r1 + seg.r2) * 0.5f;
        float cr = (seg.r1 + mr) * 0.5f;
        float warp1 = 0.2f * amp0 * (hashF(hash, 4) - 0.5f);
        float warp2 = 0.2f * amp0 * (hashF(hash, 5) - 0.5f);
        result.add(new float[]{ax, az, cx + warp1, cz + warp1 * 0.5f, seg.h1, ch, seg.r1, cr, seg.level, seg.hierarchy});
        result.add(new float[]{cx + warp1, cz + warp1 * 0.5f, mx + warp2, mz + warp2 * 0.5f, ch, mh, cr, mr, seg.level, seg.hierarchy});
        return result;
    }

    // ============ 渲染 ============
    void renderAndSave(String filePath) throws Exception {
        System.out.println("=== Rendering ===");

        int sz = OUTPUT_SIZE;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_RGB);
        float renderScale = (float)VIEW_SIZE / sz;

        // 预计算所有河流点，并按层级分组
        List<float[]>[] levelPoints = new ArrayList[LEVELS.length];
        for (int i = 0; i < LEVELS.length; i++) levelPoints[i] = new ArrayList<>();

        for (float[] s : allCurvedSegs) {
            float sx1 = s[0], sz1 = s[1], sx2 = s[2], sz2 = s[3];
            float r = Math.max(s[6], s[7]);
            int hierarchy = (int)s[9];
            int steps = (int)(Math.hypot(sx2-sx1, sz2-sz1) / 10f) + 1;
            for (int i = 0; i <= steps; i++) {
                float t = i / (float)steps;
                levelPoints[hierarchy].add(new float[]{
                    sx1 + t*(sx2-sx1),
                    sz1 + t*(sz2-sz1),
                    r
                });
            }
        }

        int totalPoints = 0;
        for (int i = 0; i < LEVELS.length; i++) {
            System.out.println("  Level " + i + " points: " + levelPoints[i].size());
            totalPoints += levelPoints[i].size();
        }
        System.out.println("  Total river points: " + totalPoints);

        // 渲染 - 从低级到高级（先画支流，再画主河道覆盖）
        for (int py = 0; py < sz; py++) {
            for (int px = 0; px < sz; px++) {
                float wx = (px - sz/2f) * renderScale;
                float wz = (py - sz/2f) * renderScale;

                // 地形颜色
                float terrainH = terrain.computeHeight(wx, wz);
                int color = getTerrainColor(terrainH);

                // 从低级到高级检查
                for (int level = LEVELS.length - 1; level >= 0; level--) {
                    List<float[]> points = levelPoints[level];
                    float minDist = Float.MAX_VALUE;
                    float bestRadius = 0;

                    // 只检查当前层级的点
                    for (float[] p : points) {
                        float dx = wx - p[0];
                        float dz = wz - p[1];
                        float dist = dx*dx + dz*dz;
                        if (dist < minDist) {
                            minDist = dist;
                            bestRadius = p[2];
                        }
                    }
                    minDist = (float)Math.sqrt(minDist);

                    if (minDist < bestRadius * 1.2f) {
                        float d = minDist / (bestRadius * 1.2f);
                        if (level == 0) {
                            // 主河道 = 深蓝
                            int r = (int)(10 + d * 20);
                            int g = (int)(20 + d * 50);
                            int b = (int)(150 + d * 80);
                            color = rgb(r, g, b);
                        } else if (level == 1) {
                            // 一级支流 = 中蓝
                            int r = (int)(15 + d * 30);
                            int g = (int)(40 + d * 60);
                            int b = (int)(130 + d * 90);
                            color = rgb(r, g, b);
                        } else {
                            // 二级支流 = 浅蓝
                            int r = (int)(20 + d * 40);
                            int g = (int)(60 + d * 70);
                            int b = (int)(110 + d * 100);
                            color = rgb(r, g, b);
                        }
                        break; // 找到最近的层级就停止
                    }
                }

                img.setRGB(px, py, color);
            }
        }

        ImageIO.write(img, "png", new File(filePath));
        System.out.println("Saved: " + filePath);
    }

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

    // ============ 主流程 ============
    void generate() {
        for (int i = 0; i < LEVELS.length; i++) {
            generateLevel(i, LEVELS[i]);
        }
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

        MultiLevelRiverGen gen = new MultiLevelRiverGen(seed);
        gen.generate();
        gen.renderAndSave("../output/multilevel_s" + seed + "_" + tag + ".png");
    }
}
