package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【干墙格归因】探针（2026-09-17）—— 回答"水边缘的直边是哪道闸门掐出来的"。
 *
 * <h2>背景</h2>
 * <p>用户标注的水体视图里，湖岸仍有<b>轴对齐的直边</b>（横平竖直台阶）——
 * 轴对齐 ⇒ 网格限，而不是"洪泛精度不够"。而 {@code GeoGenesisTerrain.lakeFineFlood}
 * <b>只能写 carver 回传了湖节点的列</b>；能否拿到湖节点取决于 carver 的采样/认领域
 * ⇒ 若上游在网格尺度上就切断了节点回传，再精的洪泛也够不到。</p>
 *
 * <h2>本探针做什么</h2>
 * <p>铺出"实际放置口径"的窗口 → 找出全部【干墙格】（非水 / 4 邻有水 / 自身低于邻居水位）
 * → 对每格<b>重跑 carver</b>（{@code carveColumnAt}）并打印各闸门状态：</p>
 * <pre>
 *   cat A : col == null               → 无任何河线命中（真·流域外）
 *   cat B : lakeNode == null          → 有河命中但【未回传湖节点】（湖域外 / 弃湖 / 无湖命中）
 *   cat C : lakeNode != null, 非湖列   → 被拒列（应被块级精修覆盖 → 看"本 chunk 种子数"）
 *   cat D : lakePlan == true          → 湖列（粗格判定）却仍是干的
 * </pre>
 * <p>同时打印 wu 坐标的 {@code mod 6 / mod 12} 余数（粗格对齐特征）与
 * "本 chunk 的湖种子数"（= {@code lakePlan && 高度 < 水位-0.5} 的列数）。</p>
 *
 * <pre>{@code gradlew runWallAttributionProbe [-PprobeArgs="seed wuX wuZ halfWu minDepth"]}</pre>
 *
 * <p>第 5 参 {@code minDepth} 默认 {@code 0.5}：深度 &lt; 0.5 块的<b>浅水墙</b>
 * （地面与水面落在同一格、放不下任何一个水块 ⇒ 物理上本就该是干的）
 * 会被排除 —— 否则它们会淹没真实信号（实测 421 个里绝大多数属这一类）。</p>
 */
public final class WallAttributionProbe {

    private WallAttributionProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        double wuX = args.length > 1 ? Double.parseDouble(args[1]) : 12.0;
        double wuZ = args.length > 2 ? Double.parseDouble(args[2]) : 316.0;
        int halfWu = args.length > 3 ? Integer.parseInt(args[3]) : 96;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double hs = tp.horizontalScale();
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);

        int half = (int) Math.round(halfWu * hs);
        int n = 2 * half + 1;
        int bx0 = (int) Math.floor(wuX * hs) - half;
        int bz0 = (int) Math.floor(wuZ * hs) - half;

        double[] h = new double[n * n];
        boolean[] wat = new boolean[n * n];
        double[] lvl = new double[n * n];
        Arrays.fill(lvl, Double.NaN);
        for (int cx = bx0 >> 4; cx <= (bx0 + n - 1) >> 4; cx++) {
            for (int cz = bz0 >> 4; cz <= (bz0 + n - 1) >> 4; cz++) {
                Cell[] cs = gt.getChunkCells(cx, cz);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int gx = cx * 16 + lx - bx0, gz = cz * 16 + lz - bz0;
                        if (gx < 0 || gx >= n || gz < 0 || gz >= n) continue;
                        Cell c = cs[lx * 16 + lz];
                        int i = gz * n + gx;
                        h[i] = c.height;
                        wat[i] = c.riverType != 0;
                        if (wat[i]) lvl[i] = c.riverSurfaceY;
                    }
                }
            }
        }

        System.out.printf("=== WallAttributionProbe seed=%d wu(%.0f,%.0f)±%dwu 窗口=%dx%d 块 ===%n",
                seed, wuX, wuZ, halfWu, n, n);
        System.out.println("判据：干墙 = 非水 + 4 邻有水 + 高度 < 该邻居水位");

        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, 0L);
        final int[] dxx = {1, -1, 0, 0};
        final int[] dzz = {0, 0, 1, -1};
        Map<String, Integer> catCount = new LinkedHashMap<>();
        Map<String, Integer> mod6 = new LinkedHashMap<>();
        Map<String, Integer> mod12 = new LinkedHashMap<>();
        Map<Long, Integer> seedCache = new LinkedHashMap<>();
        int walls = 0, shown = 0, shallow = 0;

        System.out.println();
        System.out.printf("%-14s %-13s %9s %9s %9s  %-4s %-8s %-6s %9s %9s %9s %6s%n",
                "block(x,z)", "chunk(lx,lz)", "h放置", "邻水位L", "L-h", "cat",
                "node?", "lake", "levelY", "inFlood", "floodLvl", "种子");
        for (int gz = 0; gz < n; gz++) {
            for (int gx = 0; gx < n; gx++) {
                int i = gz * n + gx;
                if (wat[i]) continue;
                double L = Double.NaN;
                for (int d = 0; d < 4; d++) {
                    int jx = gx + dxx[d], jz = gz + dzz[d];
                    if (jx < 0 || jx >= n || jz < 0 || jz >= n) continue;
                    int j = jz * n + jx;
                    if (wat[j] && (Double.isNaN(L) || lvl[j] > L)) L = lvl[j];
                }
                if (Double.isNaN(L) || h[i] >= L) continue;      // 不是干墙
                // ★ 只统计【真·干墙】：深度 < 0.5 块的格是"地面与水面同一格、放不下任何一个水块"
                //   （落块水放 (floor(height), floor(spill)]，两者相同则无水块）⇒ 物理上本就该是干的，
                //   不是伪影。此前把它们计入会淹没真实信号（421 个里绝大多数属这一类）。
                double minDepth = args.length > 4 ? Double.parseDouble(args[4]) : 0.5;
                if (L - h[i] < minDepth) { shallow++; continue; }
                walls++;
                int bx = bx0 + gx, bz = bz0 + gz;
                double wuXc = bx / hs, wuZc = bz / hs;
                HydrologyBlockCarvedColumn col = HydrologyBlockCarver.carveColumnAt(
                        engine, bx, bz, gen.sample(wuXc, wuZc).height, hs);
                boolean hasNode = col != null && col.lakeNode() != null;
                RiverLineRegion.LakeNode node = hasNode ? col.lakeNode() : null;
                String cat;
                if (col == null) cat = "A无命中";
                else if (!hasNode) cat = "B无湖节点";
                else if (!col.lakePlan()) cat = "C被拒列";
                else cat = "D湖列";
                catCount.merge(cat, 1, Integer::sum);
                long ck = ((long) (bx >> 4) << 32) ^ ((bz >> 4) & 0xFFFFFFFFL);
                int seeds = col == null ? 0
                        : seedCache.computeIfAbsent(ck, k -> countSeeds(gt, gen, engine, bx >> 4, bz >> 4, hs));
                // 粗格对齐特征：wu 坐标对 6 / 12 取余
                int m6 = (int) Math.floor(((wuXc % 6.0) + 6.0) % 6.0);
                int m12 = (int) Math.floor(((wuXc % 12.0) + 12.0) % 12.0);
                mod6.merge("x%" + m6, 1, Integer::sum);
                mod12.merge("x%" + m12, 1, Integer::sum);
                if (shown++ < 45) {
                    System.out.printf("(%6d,%6d) (%2d,%2d)      %9.3f %9.3f %9.3f  %-4s %-8s %-6s %9s %9s %9s %6d%n",
                            bx, bz, bx & 15, bz & 15, h[i], L, L - h[i], cat,
                            hasNode ? "有" : "-", col != null && col.lakePlan() ? "湖列" : "-",
                            col == null ? "-" : String.format("%.3f", col.lakeLevelY()),
                            node == null ? "-" : (node.inFlood(wuXc, wuZc) ? "true" : "false"),
                            node == null ? "-" : String.format("%.3f", node.floodLevel()),
                            seeds);
                }
            }
        }
        System.out.println();
        System.out.printf("窗口内【真·干墙】总数 = %d（上表只列前 45 个）%n", walls);
        System.out.printf("（另跳过 %d 个『浅水墙』：深度 < %.1f 块 = 放不下任何一个水块，物理上本就该是干的）%n",
                shallow, 0.5);
        System.out.println("按闸门分类：" + catCount);
        System.out.println("干墙格 wu.x mod 6 分布：" + mod6);
        System.out.println("干墙格 wu.x mod 12 分布：" + mod12);
    }

    /** 统计某 chunk 内"湖种子"列数（粗格已判出水：lakePlan && 高度 < 水位−0.5）。 */
    private static int countSeeds(GeoGenesisTerrain gt, CellGenerator gen,
                                  HydrologyExperimentEngine engine, int cx, int cz, double hs) {
        Cell[] cs = gt.getChunkCells(cx, cz);
        int c = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int bx = cx * 16 + lx, bz = cz * 16 + lz;
                double wuX = bx / hs, wuZ = bz / hs;
                HydrologyBlockCarvedColumn col = HydrologyBlockCarver.carveColumnAt(
                        engine, bx, bz, gen.sample(wuX, wuZ).height, hs);
                if (col == null || !col.lakePlan()) continue;
                if (cs[lx * 16 + lz].height < col.waterSurfaceY() - 0.5) c++;
            }
        }
        return c;
    }
}
