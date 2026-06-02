package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * TerraForged 风格河流生成器 — 最终版
 * 
 * 核心：
 * 1. 从高地源头追踪长河路径（D4 流向）
 * 2. 将路径转为 RiverNode 线段（带弯曲位移）
 * 3. 距离场渲染：每个像素找最近线段，计算 valley/bank/bed
 */
public class TerraRiverVisualizer {

    // ============ 参数 ============
    static int GRID_SIZE = 20;
    static float CELL_SPACING = 300f;
    static int MAX_RIVER_PATH = 15;

    // 笔刷参数（像素单位）
    static float WIDTH_BASE = 6f;
    static float WIDTH_PER_CELL = 3f;
    static float WIDTH_CAP = 40f;
    static float VALLEY_RATIO = 3f;
    static float BANK_RATIO = 1.8f;

    static float SEA_LEVEL = 0.35f;
    static int VIEW_SIZE = 6000;
    static final int OUTPUT_SIZE = 2048;
    // =============================

    final StandalonePreview terrain;
    final long seed;
    final Random rng;

    Cell[][] cells;
    List<RiverNode> allNodes = new ArrayList<>();

    public TerraRiverVisualizer(int seed) {
        this.seed = seed;
        this.terrain = new StandalonePreview(seed);
        this.rng = new Random(seed + 9999);
    }

    static class Cell {
        final int gx, gy;
        final float px, py;
        float noise;
        Cell downstream;
        float value;

        Cell(int gx, int gy, float px, float py) {
            this.gx = gx; this.gy = gy;
            this.px = px; this.py = py;
        }
    }

    static class RiverNode {
        final float ax, ay, bx, by;
        final float ah, bh, ar, br, displacement;
        RiverNode(float ax, float ay, float bx, float by,
                  float ah, float bh, float ar, float br, float displacement) {
            this.ax = ax; this.ay = ay; this.bx = bx; this.by = by;
            this.ah = ah; this.bh = bh; this.ar = ar; this.br = br;
            this.displacement = displacement;
        }
    }

    void generateCells() {
        int size = GRID_SIZE;
        cells = new Cell[size][size];

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float baseX = (x - size/2f) * CELL_SPACING;
                float baseY = (y - size/2f) * CELL_SPACING;
                float jx = (float)(Math.sin(x * 1.7 + y * 0.3) * 0.3 + Math.cos(y * 2.3) * 0.2);
                float jy = (float)(Math.cos(x * 0.9 + y * 1.5) * 0.3 + Math.sin(x * 1.1) * 0.2);
                cells[y][x] = new Cell(x, y, baseX + jx * CELL_SPACING, baseY + jy * CELL_SPACING);
            }
        }

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                Cell c = cells[y][x];
                c.noise = terrain.computeHeight(c.px, c.py);
                c.value = c.noise;
            }
        }
    }

    void computeFlow() {
        int size = GRID_SIZE;
        int[][] dirs = {{1,0}, {0,1}, {-1,0}, {0,-1}};

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                Cell a = cells[y][x];
                Cell minCell = a;
                float minValue = a.value;

                for (int[] d : dirs) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx >= 0 && nx < size && ny >= 0 && ny < size) {
                        Cell b = cells[ny][nx];
                        if (b.value < minValue) {
                            minValue = b.value;
                            minCell = b;
                        }
                    }
                }

                if (minCell != a) {
                    a.downstream = minCell;
                }
            }
        }
    }

    void traceRivers() {
        int size = GRID_SIZE;

        // 找源头
        boolean[][] hasUpstream = new boolean[size][size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                Cell c = cells[y][x];
                if (c.downstream != null) {
                    hasUpstream[c.downstream.gy][c.downstream.gx] = true;
                }
            }
        }

        List<Cell> sources = new ArrayList<>();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                Cell c = cells[y][x];
                if (!hasUpstream[y][x] && c.noise > SEA_LEVEL + 0.1f) {
                    sources.add(c);
                }
            }
        }

        sources.sort((a, b) -> Float.compare(b.noise, a.noise));
        System.out.println("Sources: " + sources.size());

        // 追踪路径并生成 RiverNode
        Set<Cell> used = new HashSet<>();
        for (Cell src : sources) {
            if (used.contains(src)) continue;

            List<Cell> path = new ArrayList<>();
            Cell cur = src;
            for (int step = 0; step < MAX_RIVER_PATH && cur != null; step++) {
                if (used.contains(cur)) break;
                path.add(cur);
                used.add(cur);
                if (cur.noise < SEA_LEVEL) break;
                cur = cur.downstream;
            }

            if (path.size() >= 3) {
                generateNodesFromPath(path);
            }
        }

        System.out.println("River nodes: " + allNodes.size());
    }

    void generateNodesFromPath(List<Cell> path) {
        int n = path.size();
        float baseWidth = Math.min(WIDTH_CAP, WIDTH_BASE + n * WIDTH_PER_CELL);

        for (int i = 0; i < n - 1; i++) {
            Cell a = path.get(i);
            Cell b = path.get(i+1);

            float ah = a.noise;
            float bh = b.noise;
            float ar = baseWidth * (0.4f + 0.6f * i / (n-1));
            float br = baseWidth * (0.4f + 0.6f * (i+1) / (n-1));

            // 中点偏移产生弯曲
            float mx = (a.px + b.px) * 0.5f;
            float my = (a.py + b.py) * 0.5f;

            float dx = b.px - a.px;
            float dy = b.py - a.py;
            float nx = -dy, ny = dx;
            float nLen = (float)Math.sqrt(nx*nx + ny*ny);
            if (nLen > 0) { nx /= nLen; ny /= nLen; }

            float offset = (rng.nextFloat() - 0.5f) * CELL_SPACING * 0.4f;
            mx += nx * offset;
            my += ny * offset;

            float disp = offset / CELL_SPACING;

            allNodes.add(new RiverNode(a.px, a.py, mx, my, ah, (ah+bh)*0.5f, ar, (ar+br)*0.5f, disp));
            allNodes.add(new RiverNode(mx, my, b.px, b.py, (ah+bh)*0.5f, bh, (ar+br)*0.5f, br, -disp));
        }
    }

    // ============ 距离场渲染 ============
    void renderAndSave(String filePath) throws Exception {
        System.out.println("=== TerraForged River Final ===");
        System.out.println("Grid: " + GRID_SIZE + "x" + GRID_SIZE + "  Cell: " + CELL_SPACING);

        long t0 = System.nanoTime();

        generateCells();
        computeFlow();
        traceRivers();

        int sz = OUTPUT_SIZE;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_RGB);
        float renderScale = (float)VIEW_SIZE / sz;

        // 地形
        System.out.println("Rendering terrain...");
        for (int py = 0; py < sz; py++) {
            float wz = (py - sz/2f) * renderScale;
            for (int px = 0; px < sz; px++) {
                float wx = (px - sz/2f) * renderScale;
                img.setRGB(px, py, getTerrainColor(terrain.computeHeight(wx, wz)));
            }
        }

        // 距离场河流
        System.out.println("Rendering rivers (distance field)...");
        RiverNode[] nodes = allNodes.toArray(new RiverNode[0]);

        for (int py = 0; py < sz; py++) {
            float wz = (py - sz/2f) * renderScale;
            for (int px = 0; px < sz; px++) {
                float wx = (px - sz/2f) * renderScale;

                float minDist = Float.MAX_VALUE;
                float bestT = 0;
                RiverNode bestNode = null;

                for (RiverNode node : nodes) {
                    float t = getProjection(wx, wz, node);
                    float d2 = getDistance2(wx, wz, t, node);
                    if (d2 < minDist) {
                        minDist = d2;
                        bestT = t;
                        bestNode = node;
                    }
                }

                if (bestNode != null) {
                    float dist = (float)Math.sqrt(minDist);
                    float radius = bestNode.ar + bestT * (bestNode.br - bestNode.ar);

                    if (dist < radius * VALLEY_RATIO) {
                        img.setRGB(px, py, getRiverColor(dist, radius));
                    }
                }
            }
        }

        File f = new File(filePath);
        ImageIO.write(img, "png", f);
        System.out.println("Saved: " + f.getAbsolutePath());
        System.out.println("Total: " + fmt0((System.nanoTime() - t0) / 1e6) + "ms");
    }

    float getProjection(float x, float y, RiverNode node) {
        float dx = node.bx - node.ax;
        float dy = node.by - node.ay;
        float v = (x - node.ax) * dx + (y - node.ay) * dy;
        float len2 = dx*dx + dy*dy;
        return len2 == 0 ? 0 : Math.max(0, Math.min(1, v / len2));
    }

    float getDistance2(float x, float y, float t, RiverNode node) {
        float pad = 0.05f;
        float alpha;
        if (t <= pad || t >= 1 - pad) alpha = 0;
        else if (t < 0.5f) alpha = (t - pad) / (0.5f - pad);
        else alpha = (1 - pad - t) / (0.5f - pad);
        alpha = alpha * alpha * (3 - 2 * alpha);
        alpha *= node.displacement * CELL_SPACING;

        float tx = node.ax + t * (node.bx - node.ax);
        float ty = node.ay + t * (node.by - node.ay);

        float nx = -(ty - node.ay);
        float ny = (tx - node.ax);
        float nLen = (float)Math.sqrt(nx*nx + ny*ny);
        if (nLen > 0) { nx /= nLen; ny /= nLen; }

        float px = tx - ny * alpha;
        float py = ty + nx * alpha;

        float dx = x - px;
        float dy = y - py;
        return dx*dx + dy*dy;
    }

    int getRiverColor(float dist, float radius) {
        float bedR = radius * 0.45f;
        float bankR = radius * 0.75f;
        float valleyR = radius * VALLEY_RATIO;

        if (dist <= bedR) {
            float depth = 1f - dist / bedR;
            return rgb(15, 60, 200 + (int)(depth * 55));
        } else if (dist <= bankR) {
            float ft = (dist - bedR) / (bankR - bedR);
            ft = ft * ft * (3 - 2 * ft);
            return rgb((int)(35 + ft * 90), (int)(70 + ft * 45), (int)(210 - ft * 140));
        } else if (dist <= valleyR) {
            float ft = (dist - bankR) / (valleyR - bankR);
            ft = ft * ft * (3 - 2 * ft);
            return rgb((int)(125 + ft * 55), (int)(85 + ft * 35), 50);
        }
        return 0;
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
    static String fmt0(double v) { return String.format("%.0f", v); }

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        String tag = "final";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed": seed = Integer.parseInt(args[++i]); break;
                case "--grid": GRID_SIZE = Integer.parseInt(args[++i]); break;
                case "--cell": CELL_SPACING = Float.parseFloat(args[++i]); break;
                case "--tag": tag = args[++i]; break;
            }
        }
        new TerraRiverVisualizer(seed).renderAndSave("../output/terra_s" + seed + "_" + tag + ".png");
    }
}
