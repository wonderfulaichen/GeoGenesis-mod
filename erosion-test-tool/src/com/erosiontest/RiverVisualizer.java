package com.erosiontest;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * 河流可视化 v4 — 自适应笔刷渲染
 * 
 * 核心：
 * 1. D8 细胞法生成清晰的河流路径（折线）
 * 2. 自适应笔刷沿路径一笔画出连续河流
 * 3. 笔刷宽度 = f(累积流量)，边缘高斯平滑衰减
 * 4. 每像素计算到最近河流段的距离 + 宽度插值
 */
public class RiverVisualizer {

    // ============ 可调参数 ============
    static int CELL_SIZE = 64;           // 更小的细胞间距 = 更精细的河流
    static float FLOW_ACCUM_MIN = 3f;    // 更低阈值 = 更多支流
    static float MAX_GRADIENT = 0.0005f; // 最大水面梯度
    static float WIDTH_EXP = 0.4f;       // 宽度指数（更大的变化）
    static float WIDTH_SCALE = 10f;      // 宽度缩放
    static float WIDTH_CAP = 80f;        // 最大宽度
    static float DEPTH_SCALE = 0.02f;    // 深度缩放
    static float DEPTH_CAP = 0.15f;      // 最大深度
    static float VALLEY_RATIO = 2.5f;    // 河谷范围 = 河床宽度 × VALLEY_RATIO
    static float BANK_RATIO = 1.5f;      // 河岸范围 = 河床宽度 × BANK_RATIO
    // =============================

    static final int SEA_LEVEL = 63;
    static final int MIN_Y = -64;
    static final int MAX_Y = 256;
    static final float SEA_NORM = (float)(SEA_LEVEL - MIN_Y) / (MAX_Y - MIN_Y);

    static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    static final int[] DZ = {0, 1, 1, 1, 0, -1, -1, -1};

    static final int OUTPUT_SIZE = 2048;
    static final int VIEW_SIZE = 6000;
    static final float SCALE = (float)VIEW_SIZE / OUTPUT_SIZE;

    final StandalonePreview terrain;
    final long seed;
    final Random rng;
    final ImprovedNoise jitterX, jitterZ;

    final Map<Long, CellData> cellCache = new HashMap<>();
    final Map<Long, Float> smoothHeightCache = new HashMap<>();
    final Map<Long, Float> accumMap = new HashMap<>();
    final List<RiverPath> allPaths = new ArrayList<>();
    boolean tracingDone = false;

    public RiverVisualizer(int seed) {
        this.seed = seed;
        this.terrain = new StandalonePreview(seed);
        this.rng = new Random(seed + 7777);
        this.jitterX = new ImprovedNoise(rng.nextLong());
        this.jitterZ = new ImprovedNoise(rng.nextLong());
    }

    float cellCX(int ci, int cj) {
        float base = ci * CELL_SIZE + CELL_SIZE * 0.5f;
        float jx = (float) jitterX.noise(ci * 0.23, 0.0, cj * 0.23);
        return base + jx * CELL_SIZE * 0.25f;
    }
    float cellCZ(int ci, int cj) {
        float base = cj * CELL_SIZE + CELL_SIZE * 0.5f;
        float jz = (float) jitterZ.noise(ci * 0.23, 0.0, cj * 0.23);
        return base + jz * CELL_SIZE * 0.25f;
    }
    static long ck(int ci, int cj) { return ((long)ci << 32) | (cj & 0xFFFFFFFFL); }

    void buildCell(int ci, int cj) {
        long key = ck(ci, cj);
        if (cellCache.containsKey(key)) return;
        cellCache.put(key, new CellData(ci, cj, cellCX(ci, cj), cellCZ(ci, cj), -1));
    }

    void computeAllFlowDirs() {
        for (CellData cell : cellCache.values()) {
            long key = ck(cell.ci, cell.cj);
            if (smoothHeightCache.containsKey(key)) continue;
            float sum = terrain.computeHeight(cell.wx, cell.wz);
            int cnt = 1;
            for (int k = 0; k < 8; k++) {
                long nk = ck(cell.ci + DX[k], cell.cj + DZ[k]);
                CellData nb = cellCache.get(nk);
                if (nb != null) { sum += terrain.computeHeight(nb.wx, nb.wz); cnt++; }
            }
            smoothHeightCache.put(key, sum / cnt);
        }
        for (CellData cell : new ArrayList<>(cellCache.values())) {
            if (cell.flowDir >= 0) continue;
            cell.flowDir = computeFlowDir(cell);
        }
    }

    float smoothHeight(int ci, int cj) {
        Long key = ck(ci, cj);
        Float sh = smoothHeightCache.get(key);
        if (sh != null) return sh;
        return terrain.computeHeight(cellCX(ci, cj), cellCZ(ci, cj));
    }

    int computeFlowDir(CellData cell) {
        float h = smoothHeight(cell.ci, cell.cj);
        int best = -1;
        float bestDrop = 0f;
        for (int k = 0; k < 8; k++) {
            int ni = cell.ci + DX[k], nj = cell.cj + DZ[k];
            if (cellCache.containsKey(ck(ni, nj))) {
                float drop = (h - smoothHeight(ni, nj));
                if (drop > bestDrop) { bestDrop = drop; best = k; }
            }
        }
        return best;
    }

    void traceAllRivers() {
        if (tracingDone) return;
        computeAllFlowDirs();

        // 计算累积流量
        accumMap.clear();
        for (CellData c : cellCache.values()) {
            if (c.flowDir >= 0) accumMap.put(ck(c.ci, c.cj), computeAccum(c.ci, c.cj, new HashSet<>()));
        }

        // 统计
        if (!accumMap.isEmpty()) {
            List<Float> vals = new ArrayList<>(accumMap.values());
            Collections.sort(vals);
            int n = vals.size();
            System.out.println("Accum: n=" + n + " p50=" + fmt1(vals.get(n/2)) +
                " p90=" + fmt1(vals.get((int)(n*0.9))) + " max=" + fmt1(vals.get(n-1)));
        }

        // 找河流起点
        List<CellData> starts = new ArrayList<>();
        for (CellData c : cellCache.values()) {
            if (c.flowDir < 0) continue;
            float acc = accumMap.getOrDefault(ck(c.ci, c.cj), 0f);
            if (acc < FLOW_ACCUM_MIN) continue;
            float h = terrain.computeHeight(c.wx, c.wz);
            if (h < SEA_NORM * 0.5f) continue;

            boolean hasUp = false;
            for (int k = 0; k < 8; k++) {
                long uk = ck(c.ci + DX[k], c.cj + DZ[k]);
                CellData up = cellCache.get(uk);
                if (up != null && up.flowDir >= 0) {
                    int fi = up.ci + DX[up.flowDir], fj = up.cj + DZ[up.flowDir];
                    if (fi == c.ci && fj == c.cj && accumMap.getOrDefault(uk, 0f) >= FLOW_ACCUM_MIN) {
                        hasUp = true; break;
                    }
                }
            }
            if (!hasUp) starts.add(c);
        }
        System.out.println("River starts: " + starts.size());

        // 追踪河流（大→小排序，先处理大河）
        starts.sort((a,b) -> Float.compare(accumMap.getOrDefault(ck(b.ci,b.cj), 0f),
                                           accumMap.getOrDefault(ck(a.ci,a.cj), 0f)));

        Set<Long> traced = new HashSet<>();
        for (CellData src : starts) {
            RiverPath path = traceRiverPath(src, traced);
            if (path != null) allPaths.add(path);
        }
        tracingDone = true;
    }

    float computeAccum(int ci, int cj, Set<Long> visited) {
        long key = ck(ci, cj);
        if (visited.contains(key)) return 0;
        visited.add(key);
        float total = 1f;
        for (int k = 0; k < 8; k++) {
            int ui = ci + DX[k], uj = cj + DZ[k];
            long uk = ck(ui, uj);
            CellData up = cellCache.get(uk);
            if (up != null && up.flowDir >= 0) {
                int fi = ui + DX[up.flowDir], fj = uj + DZ[up.flowDir];
                if (fi == ci && fj == cj) total += computeAccum(ui, uj, visited);
            }
        }
        return total;
    }

    RiverPath traceRiverPath(CellData src, Set<Long> traced) {
        List<RiverNode> nodes = new ArrayList<>();
        int curI = src.ci, curJ = src.cj;

        for (int step = 0; step < 500; step++) {
            long key = ck(curI, curJ);
            CellData cell = cellCache.get(key);
            if (cell == null) break;

            float h = terrain.computeHeight(cell.wx, cell.wz);

            if (traced.contains(key)) {
                if (nodes.size() > 0) {
                    nodes.add(new RiverNode(cell.wx, cell.wz, h,
                        accumMap.getOrDefault(key, 1f)));
                }
                break;
            }

            if (h < SEA_NORM * 0.25f) {
                nodes.add(new RiverNode(cell.wx, cell.wz, h,
                    accumMap.getOrDefault(key, 1f)));
                traced.add(key);
                break;
            }

            if (nodes.size() > 0 && h > terrain.computeHeight(
                    nodes.get(nodes.size()-1).x, nodes.get(nodes.size()-1).z) + 0.02f) break;

            nodes.add(new RiverNode(cell.wx, cell.wz, h,
                accumMap.getOrDefault(key, 1f)));
            traced.add(key);

            if (cell.flowDir < 0) break;
            curI = cell.ci + DX[cell.flowDir];
            curJ = cell.cj + DZ[cell.flowDir];
        }

        if (nodes.size() < 3) return null;

        int n = nodes.size();
        float[] accums = new float[n];
        for (int i = 0; i < n; i++) accums[i] = nodes.get(i).accum;

        // 链式水面高度传递
        float[] wls = new float[n];
        wls[n-1] = Math.min(nodes.get(n-1).h, SEA_NORM);
        for (int i = n-2; i >= 0; i--) {
            float dist = (float)Math.hypot(nodes.get(i).x - nodes.get(i+1).x,
                                           nodes.get(i).z - nodes.get(i+1).z);
            wls[i] = Math.min(wls[i+1] + MAX_GRADIENT * dist,
                             nodes.get(i).h - 0.003f);
            wls[i] = Math.max(wls[i], SEA_NORM);
        }
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 1; i < n; i++) {
                float dist = (float)Math.hypot(nodes.get(i).x - nodes.get(i-1).x,
                                               nodes.get(i).z - nodes.get(i-1).z);
                float maxDrop = MAX_GRADIENT * dist;
                if (wls[i-1] - wls[i] > maxDrop)
                    wls[i] = Math.max(wls[i-1] - maxDrop, SEA_NORM);
            }
        }

        // 生成路径段
        List<RiverSeg> segs = new ArrayList<>();
        for (int i = 0; i < n-1; i++) {
            float ah = wls[i], bh = wls[i+1];
            if (bh > ah) bh = ah - 0.002f;
            float aw = riverWidth(accums[i]);
            float bw = riverWidth(accums[i+1]);
            float ad = riverDepth(accums[i]);
            float bd = riverDepth(accums[i+1]);
            segs.add(new RiverSeg(nodes.get(i).x, nodes.get(i).z,
                                  nodes.get(i+1).x, nodes.get(i+1).z,
                                  ah, bh, aw, bw, ah-ad, bh-bd));
        }
        return new RiverPath(segs);
    }

    float riverWidth(float a) { return Math.min(2f + (float)Math.pow(a, WIDTH_EXP) * WIDTH_SCALE, WIDTH_CAP); }
    float riverDepth(float a) { return Math.min(0.005f + (float)Math.log1p(a) * DEPTH_SCALE, DEPTH_CAP); }

    // ============ 自适应笔刷渲染 ============
    void renderAndSave(String filePath) throws Exception {
        System.out.println("=== River Visualizer v4 (Adaptive Brush) ===");
        System.out.println("CELL_SIZE: " + CELL_SIZE + "  FLOW_ACCUM_MIN: " + FLOW_ACCUM_MIN);
        System.out.println("WIDTH: exp=" + WIDTH_EXP + " scale=" + WIDTH_SCALE + " cap=" + WIDTH_CAP);

        int halfCells = VIEW_SIZE / CELL_SIZE / 2 + 3;
        for (int ci = -halfCells; ci <= halfCells; ci++)
            for (int cj = -halfCells; cj <= halfCells; cj++)
                buildCell(ci, cj);
        System.out.println("Cells: " + cellCache.size());

        long t0 = System.nanoTime();
        traceAllRivers();

        int totalSegs = 0;
        for (RiverPath p : allPaths) totalSegs += p.segs.size();
        System.out.println("Paths: " + allPaths.size() + "  Segments: " + totalSegs);

        // 收集所有段到数组
        List<RiverSeg> allSegs = new ArrayList<>();
        for (RiverPath p : allPaths) allSegs.addAll(p.segs);
        RiverSeg[] segArray = allSegs.toArray(new RiverSeg[0]);
        System.out.println("Total segments: " + segArray.length);

        float minW = Float.MAX_VALUE, maxW = 0;
        for (RiverSeg s : segArray) { minW = Math.min(minW, s.aw); maxW = Math.max(maxW, s.aw); }
        System.out.println("Width range: " + fmt1(minW) + " ~ " + fmt1(maxW));

        int sz = OUTPUT_SIZE;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_RGB);

        System.out.println("Rendering (adaptive brush)...");

        // 对每个像素，找最近河流段并计算自适应笔刷颜色
        for (int py = 0; py < sz; py++) {
            float wz = (py - sz/2f) * SCALE;
            for (int px = 0; px < sz; px++) {
                float wx = (px - sz/2f) * SCALE;

                float h = terrain.computeHeight(wx, wz);

                // 找最近的河流段
                float minDist = Float.MAX_VALUE;
                float bestT = 0;
                RiverSeg bestSeg = null;

                for (RiverSeg s : segArray) {
                    // 包围盒快速剔除
                    float bw = Math.max(s.aw, s.bw);
                    float minX = Math.min(s.x1, s.x2) - bw * VALLEY_RATIO;
                    float maxX = Math.max(s.x1, s.x2) + bw * VALLEY_RATIO;
                    float minZ = Math.min(s.z1, s.z2) - bw * VALLEY_RATIO;
                    float maxZ = Math.max(s.z1, s.z2) + bw * VALLEY_RATIO;
                    if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;

                    float dx = s.x2 - s.x1, dz = s.z2 - s.z1;
                    float len2 = dx*dx + dz*dz;
                    float t = len2 > 0 ? ((wx - s.x1)*dx + (wz - s.z1)*dz) / len2 : 0;
                    t = Math.max(0, Math.min(1, t));
                    float cx = s.x1 + t*dx, cz = s.z1 + t*dz;
                    float dist = (float)Math.hypot(wx - cx, wz - cz);

                    if (dist < minDist) {
                        minDist = dist;
                        bestT = t;
                        bestSeg = s;
                    }
                }

                int color;
                if (bestSeg != null) {
                    // 插值该点的河宽和水面高度
                    float w = bestSeg.aw + bestT * (bestSeg.bw - bestSeg.aw);
                    float waterH = bestSeg.ah + bestT * (bestSeg.bh - bestSeg.ah);
                    float bedH = bestSeg.abed + bestT * (bestSeg.bbed - bestSeg.abed);
                    float depth = waterH - bedH;

                    float bedR = w;            // 河床半径 = 河宽
                    float bankR = w * BANK_RATIO;  // 河岸半径
                    float valleyR = w * VALLEY_RATIO; // 河谷半径

                    if (minDist <= valleyR) {
                        // 在笔刷范围内 — 用自适应笔刷着色
                        color = brushColor(minDist, bedR, bankR, valleyR, depth, waterH);
                    } else {
                        color = getTerrainColor(h);
                    }
                } else {
                    color = getTerrainColor(h);
                }
                img.setRGB(px, py, color);
            }
        }

        File f = new File(filePath);
        ImageIO.write(img, "png", f);
        double totalMs = (System.nanoTime() - t0) / 1e6;
        System.out.println("Saved: " + f.getAbsolutePath());
        System.out.println("Total: " + fmt0(totalMs) + "ms");
    }

    /** 自适应笔刷颜色 */
    int brushColor(float dist, float bedR, float bankR, float valleyR,
                   float depth, float waterH) {
        // 归一化距离 0~1
        float t = dist / valleyR;
        // 平滑衰减（smoothstep）
        float alpha = 1f - t * t * (3 - 2 * t);

        if (dist <= bedR) {
            // 河床：蓝色（深度决定深浅）
            float depthFactor = Math.min(1f, depth * 5f);
            int blue = 160 + (int)(depthFactor * 95);
            int r = 10 + (int)((1 - depthFactor) * 20);
            int g = 40 + (int)((1 - depthFactor) * 20);
            return rgb(r, g, Math.min(255, blue));
        } else if (dist <= bankR) {
            // 河岸：蓝→棕渐变
            float ft = (dist - bedR) / (bankR - bedR);
            ft = ft * ft * (3 - 2 * ft);
            int r = (int)(30 + ft * 80);
            int g = (int)(60 + ft * 40);
            int b = (int)(200 - ft * 120);
            return rgb(r, g, b);
        } else {
            // 河谷：棕色渐变
            float ft = (dist - bankR) / (valleyR - bankR);
            ft = ft * ft * (3 - 2 * ft);
            int br = (int)(110 + ft * 50);
            int g = (int)(80 + ft * 30);
            return rgb(br, g, 45 + (int)(ft * 10));
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
    static String fmt1(float v) { return String.format("%.1f", v); }
    static String fmt0(double v) { return String.format("%.0f", v); }

    // ============ 内部类 ============
    static class CellData {
        final int ci, cj; final float wx, wz; int flowDir;
        CellData(int ci, int cj, float wx, float wz, int flowDir) {
            this.ci=ci; this.cj=cj; this.wx=wx; this.wz=wz; this.flowDir=flowDir;
        }
    }
    static class RiverNode {
        final float x, z, h, accum;
        RiverNode(float x, float z, float h, float accum) { this.x=x; this.z=z; this.h=h; this.accum=accum; }
    }
    static class RiverSeg {
        final float x1, z1, x2, z2;
        final float ah, bh, aw, bw, abed, bbed;
        RiverSeg(float x1, float z1, float x2, float z2,
                 float ah, float bh, float aw, float bw,
                 float abed, float bbed) {
            this.x1=x1; this.z1=z1; this.x2=x2; this.z2=z2;
            this.ah=ah; this.bh=bh; this.aw=aw; this.bw=bw;
            this.abed=abed; this.bbed=bbed;
        }
    }
    static class RiverPath {
        final List<RiverSeg> segs;
        RiverPath(List<RiverSeg> segs) { this.segs = segs; }
    }

    public static void main(String[] args) throws Exception {
        int seed = 12345;
        String tag = "brush1";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed": seed = Integer.parseInt(args[++i]); break;
                case "--cell": CELL_SIZE = Integer.parseInt(args[++i]); break;
                case "--accum": FLOW_ACCUM_MIN = Float.parseFloat(args[++i]); break;
                case "--wexp": WIDTH_EXP = Float.parseFloat(args[++i]); break;
                case "--wscale": WIDTH_SCALE = Float.parseFloat(args[++i]); break;
                case "--wcap": WIDTH_CAP = Float.parseFloat(args[++i]); break;
                case "--grad": MAX_GRADIENT = Float.parseFloat(args[++i]); break;
                case "--valley": VALLEY_RATIO = Float.parseFloat(args[++i]); break;
                case "--bank": BANK_RATIO = Float.parseFloat(args[++i]); break;
                case "--tag": tag = args[++i]; break;
            }
        }
        RiverVisualizer rv = new RiverVisualizer(seed);
        rv.renderAndSave("../output/river_s" + seed + "_c" + CELL_SIZE + "_" + tag + ".png");
    }
}
