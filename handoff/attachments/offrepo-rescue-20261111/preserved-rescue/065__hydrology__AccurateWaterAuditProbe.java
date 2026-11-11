package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Arrays;

/**
 * 【1 块精度】水体短板审计（2026-09-17）—— 修正度量学。
 *
 * <h2>为什么必须重做</h2>
 * <p>此前的审计在建在 <b>24wu 粗格</b>上：同一水体粗格报违反 <b>+26.076 块</b>，
 * 而 <b>1 块精度</b>只有 <b>+5.99 块</b> ⇒ <b>夸大 ≈ 4.3 倍</b>。
 * 结论（甚至"河还是湖"的相对判断）在此前都建立在被夸大的读数上。</p>
 *
 * <h2>本探针的做法</h2>
 * <ol>
 *   <li><b>口径 = 实际放置</b>（{@link GeoGenesisTerrain#getChunkCells}，含侵蚀 + 水文雕刻），
 *       以 <b>1 块</b>为单位铺满窗口 ⇒ 与玩家所见一致；</li>
 *   <li>对每个水格，求"<b>半径 R 内最低的非水地面</b>"（= 真实盆沿）⇒
 *       {@code 违反 = riverSurfaceY − 该最低值}；</li>
 *   <li>求最低值用<b>由粗到细搜索</b>（用户建议）：先在 R 内按 8 块步长粗扫锁定候选，
 *       再在候选 ±8 块内按 1 块精扫 ⇒ 不牺牲结果、显著省采样。</li>
 * </ol>
 *
 * <p>输出：违反分布（分桶）、最坏点坐标、以及"粗扫 vs 由粗到细"的耗时与一致性对照。</p>
 *
 * <pre>{@code gradlew runAccurateWaterAuditProbe [-PprobeArgs="seed blockX blockZ radiusBlocks"]}</pre>
 */
public final class AccurateWaterAuditProbe {

    private AccurateWaterAuditProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : 24;      // 用户 F3 的块坐标
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 632;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 96;  // 块

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);

        int w = 2 * radius + 1;
        double[][] h = new double[w][w];
        double[][] lvl = new double[w][w];
        boolean[][] wat = new boolean[w][w];

        long t0 = System.nanoTime();
        int cx0 = (bx - radius) >> 4, cx1 = (bx + radius) >> 4;
        int cz0 = (bz - radius) >> 4, cz1 = (bz + radius) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                Cell[] cells = gt.getChunkCells(cx, cz);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int wx = cx * 16 + lx, wz = cz * 16 + lz;
                        int i = wx - (bx - radius), j = wz - (bz - radius);
                        if (i < 0 || i >= w || j < 0 || j >= w) continue;
                        Cell c = cells[lx * 16 + lz];
                        h[j][i] = c.height;
                        lvl[j][i] = c.riverSurfaceY;
                        wat[j][i] = c.riverType != 0;
                    }
                }
            }
        }
        long tBuild = (System.nanoTime() - t0) / 1_000_000;
        int waterCells = 0, oceanCells = 0;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                if (wat[j][i]) {
                    waterCells++;
                    if (h[j][i] <= 63) oceanCells++;
                }
            }
        }
        System.out.printf("=== AccurateWaterAuditProbe seed=%d 中心块(%d,%d) 半径 %d 块（%d×%d）===%n",
                seed, bx, bz, radius, w, w);
        System.out.printf("铺场（1 块精度，含侵蚀+雕刻）：%d ms；水格 %d（其中海平面以下 %d）%n",
                tBuild, waterCells, oceanCells);

        // ===== ★ 按【多半径】分别审计（判据必须区分"紧邻"与"远处"）=====
        //   ⚠ 关键：真实地形里"64 块外必有更低处"总是成立（河流在谷地）——
        //     所以【半径 64】的读数为 98.8% 违反并不构成违反。
        //     物理上真正的短板是【紧邻】（1~2 块）：水能否从旁边流走。
        System.out.println();
        int[] radii = {1, 2, 4, 8, 16, 32, 64};
        for (int r : radii) {
            long t1 = System.nanoTime();
            int[] bucket = new int[5];
            double worst = 0, wX = 0, wZ = 0, wLv = 0, wRim = 0;
            int viol = 0, n = 0;
            for (int j = 0; j < w; j++) {
                for (int i = 0; i < w; i++) {
                    if (!wat[j][i] || h[j][i] <= 63) continue;
                    double rim = minNonWater(h, wat, i, j, r);
                    if (rim == Double.MAX_VALUE) continue;
                    n++;
                    double v = lvl[j][i] - rim;
                    int b = v < 1 ? 0 : (v < 2 ? 1 : (v < 5 ? 2 : (v < 10 ? 3 : 4)));
                    bucket[b]++;
                    if (v > 0.5) viol++;
                    if (v > worst) {
                        worst = v; wX = bx - radius + i; wZ = bz - radius + j;
                        wLv = lvl[j][i]; wRim = rim;
                    }
                }
            }
            long t2 = (System.nanoTime() - t1) / 1_000_000;
            System.out.printf("[半径 %2d 块] <1:%d 1~2:%d 2~5:%d 5~10:%d ≥10:%d ｜ >0.5 块 %d/%d（%.1f%%）"
                            + " 最坏 %.3f 块 @块(%.0f,%.0f)（水位 %.2f / 盆沿 %.2f）｜ %d ms%n",
                    r, bucket[0], bucket[1], bucket[2], bucket[3], bucket[4],
                    viol, n, n == 0 ? 0 : 100.0 * viol / n,
                    worst, wX, wZ, wLv, wRim, t2);
        }

        // ===== 对照：全域 1 块精扫 vs 由粗到细（取 8 个代表水格计时）=====
        System.out.println();
        System.out.println("[对照] 全域 1 块精扫 vs 由粗到细（在 8 个代表水格上）");
        long a0 = System.nanoTime();
        double s1 = 0;
        int cnt = 0;
        for (int j = 0; j < w && cnt < 8; j += Math.max(1, w / 8)) {
            for (int i = 0; i < w && cnt < 8; i += Math.max(1, w / 8)) {
                if (!wat[j][i]) continue;
                s1 += minNonWater(h, wat, i, j, radius);
                cnt++;
            }
        }
        long a1 = System.nanoTime();
        double s2 = 0;
        cnt = 0;
        for (int j = 0; j < w && cnt < 8; j += Math.max(1, w / 8)) {
            for (int i = 0; i < w && cnt < 8; i += Math.max(1, w / 8)) {
                if (!wat[j][i]) continue;
                s2 += minNonWaterCoarseToFine(h, wat, i, j, radius);
                cnt++;
            }
        }
        long a2 = System.nanoTime();
        System.out.printf("    全域精扫   合计 %.3f  耗时 %d ms%n", s1, (a1 - a0) / 1_000_000);
        System.out.printf("    由粗到细   合计 %.3f  耗时 %d ms%n", s2, (a2 - a1) / 1_000_000);
        System.out.printf("    ⇒ 结果一致：%s%n", Math.abs(s1 - s2) < 1e-9 ? "是" : "否");
        System.out.println();
        System.out.println("判读：这是【1 块精度】的可信基线（含侵蚀与雕刻，与玩家所见一致）。");
        System.out.println("      此前 24wu 粗格读数（最坏 +26 块）应作废。");

        // ===== ★ 违反点附近的【块级水/陆地图】—— 决定修法 =====
        //   若"水边界"与"等高线"不重合（水陆分界切在等高线之间）⇒ 修法 = 块级连通性；
        //   若水边界恰好落在某条直线/网格上 ⇒ 修法 = 域量化粒度。
        System.out.println();
        printBlockMap(gt, null, bx, bz, 12);
    }

    /**
     * 打印块级地图：{@code W}=水、{@code .}=地面低于水位却干（违反）、{@code #}=地面高于水位。
     * 另在上方标出该列地面高度（取整）以便看等高线是否与水界重合。
     */
    private static void printBlockMap(GeoGenesisTerrain gt,
                                      com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork net,
                                      int bx, int bz, int half) {
        // 先取窗口内水格的水面（中位）作为参照水位
        java.util.List<Double> surfs = new java.util.ArrayList<>();
        for (int z = bz - half; z <= bz + half; z++) {
            for (int x = bx - half; x <= bx + half; x++) {
                Cell c = gt.getChunkCells(x >> 4, z >> 4)
                        [Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                if (c.riverType != 0) surfs.add(c.riverSurfaceY);
            }
        }
        if (surfs.isEmpty()) {
            System.out.println("    该窗口无水格，跳过地图。");
            return;
        }
        java.util.Collections.sort(surfs);
        double ref = surfs.get(surfs.size() / 2);      // 参照水位 = 水面中位
        double minSurf = surfs.get(0), maxSurf = surfs.get(surfs.size() - 1);

        System.out.printf("    块级地图（中心块 %d,%d，±%d 块）%n", bx, bz, half);
        System.out.printf("      符号：W=水  o=干但地面低于水位(违反)  .=干且高于水位%n");
        System.out.printf("      参照水位 = 水面中位 %.3f（范围 %.3f~%.3f）%n", ref, minSurf, maxSurf);
        for (int z = bz - half; z <= bz + half; z++) {
            StringBuilder sb = new StringBuilder(String.format("    z=%5d ", z));
            for (int x = bx - half; x <= bx + half; x++) {
                Cell c = gt.getChunkCells(x >> 4, z >> 4)
                        [Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                if (c.riverType != 0) sb.append('W');
                else sb.append(c.height < ref - 0.5 ? 'o' : '.');
            }
            System.out.println(sb);
        }
        int viol = 0, dry = 0;
        for (int z = bz - half; z <= bz + half; z++) {
            for (int x = bx - half; x <= bx + half; x++) {
                Cell c = gt.getChunkCells(x >> 4, z >> 4)
                        [Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                if (c.riverType == 0) { dry++; if (c.height < ref - 0.5) viol++; }
            }
        }
        System.out.printf("    窗口：水格 %d，干格 %d（其中低于水位 %d）%n",
                surfs.size(), dry, viol);
        System.out.println("    判读：① 若大量 'o' 与 'W' 相邻 ⇒ 水边界【超出】等高线 ⇒ 灌到不该灌处；");
        System.out.println("          ② 若水面在本窗口【恒定】⇒ 该处是按湖面铺的水平水板；");
        System.out.println("          ③ 若 'o' 成片且远离 'W' ⇒ 域过大把远处低地也标成了湖域。");
    }

    /** 全域精扫：半径 R 内最低非水地面（1 块步长）。 */
    private static double minNonWater(double[][] h, boolean[][] wat, int i, int j, int r) {
        int w = h.length;
        double best = Double.MAX_VALUE;
        for (int dj = -r; dj <= r; dj++) {
            int jj = j + dj;
            if (jj < 0 || jj >= w) continue;
            for (int di = -r; di <= r; di++) {
                int ii = i + di;
                if (ii < 0 || ii >= w) continue;
                if (wat[jj][ii]) continue;
                if (h[jj][ii] < best) best = h[jj][ii];
            }
        }
        return best;
    }

    /**
     * 由粗到细：先按 8 块步长粗扫锁定最低格，再在其 ±8 块邻域按 1 块精扫。
     * <p>（用户建议的"变化大处加密"的保守实现：粗扫定位候选 → 候选附近精扫。）</p>
     */
    private static double minNonWaterCoarseToFine(double[][] h, boolean[][] wat, int i, int j, int r) {
        int w = h.length;
        final int coarse = 8;
        double best = Double.MAX_VALUE;
        int bi = i, bj = j;
        for (int dj = -r; dj <= r; dj += coarse) {
            int jj = j + dj;
            if (jj < 0 || jj >= w) continue;
            for (int di = -r; di <= r; di += coarse) {
                int ii = i + di;
                if (ii < 0 || ii >= w) continue;
                if (wat[jj][ii]) continue;
                if (h[jj][ii] < best) { best = h[jj][ii]; bi = ii; bj = jj; }
            }
        }
        if (best == Double.MAX_VALUE) return best;
        // 候选 ±coarse 内精扫
        for (int dj = -coarse; dj <= coarse; dj++) {
            int jj = bj + dj;
            if (jj < 0 || jj >= w) continue;
            for (int di = -coarse; di <= coarse; di++) {
                int ii = bi + di;
                if (ii < 0 || ii >= w) continue;
                if (wat[jj][ii]) continue;
                if (h[jj][ii] < best) best = h[jj][ii];
            }
        }
        return best;
    }
}
