package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 「湖岸精确求交」可行性探针（2026-09-17）—— 用户建议：借鉴光线追踪的求交/自适应细分。
 *
 * <h2>问题（用户实机截图）</h2>
 * <p>湖岸在游戏里是一段段**直线与直角**（水与陆地之间无过渡），
 * 而自然湖岸应是**顺等高线的缓坡**。根因：`computeFlood` 的粗格连通区
 * （BFS grid = 12wu = 24 块）被 carver 当作**硬边界**。</p>
 *
 * <h2>思路（把"求交"搬过来）</h2>
 * <p>光追里对曲面求交用 <b>BVH 剔除 + 精确求交 + 自适应细分</b>。此处对应：</p>
 * <ol>
 *   <li><b>剔除（BVH 对应）</b>：粗格连通区只用来**筛掉远离水体的列**（廉价）；</li>
 *   <li><b>精确求交</b>：水界真值 = <b>{@code height(x,z) = spill} 的等值面</b>，
 *       逐**块**（1 块步长）用<b>最终地形</b>求值即可（我们有 {@code sampleWu} / 点态地形）；</li>
 *   <li><b>自适应细分</b>：只在<b>边界带</b>（粗格连通区边缘 ±1 格）做逐块求交，
 *       内部直接用粗判 ⇒ 代价与"湖周长"成正比，而非与"湖面积"成正比。</li>
 * </ol>
 *
 * <h2>本探针回答可行性的关键问题</h2>
 * <ol>
 *   <li><b>边界带规模</b>：某湖的"周长带"有多少块？（决定逐块求交的总代价）</li>
 *   <li><b>对照</b>：逐块精确求交给出的湖岸，与粗格连通区边界差多少？
 *       （即"直角 → 等高线"能改善多少，用**边界块数**与**形态**衡量）</li>
 *   <li><b>代价</b>：逐块求交的耗时 vs 粗格 BFS 的耗时。</li>
 * </ol>
 *
 * <pre>{@code gradlew runLakeEdgeRefineProbe [-PprobeArgs="seed blockX blockZ radiusBlocks"]}</pre>
 */
public final class LakeEdgeRefineProbe {

    private LakeEdgeRefineProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : 24;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : 632;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 64;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        RiverLineParams rp = RiverLineParams.defaults();
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);

        int w = 2 * radius + 1;
        double[][] h = new double[w][w];
        double[][] surf = new double[w][w];
        boolean[][] coarse = new boolean[w][w];   // 粗格判定出的水（现状）
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                int x = bx - radius + i, z = bz - radius + j;
                Cell c = gt.getChunkCells(x >> 4, z >> 4)
                        [Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
                h[j][i] = c.height;
                surf[j][i] = c.riverSurfaceY;
                coarse[j][i] = c.riverType != 0;
            }
        }

        // 参照水位 = 水格水面中位（湖面近似常量）
        java.util.List<Double> ss = new java.util.ArrayList<>();
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) if (coarse[j][i]) ss.add(surf[j][i]);
        }
        if (ss.isEmpty()) {
            System.out.println("窗口无水格。");
            return;
        }
        java.util.Collections.sort(ss);
        double level = ss.get(ss.size() / 2);

        // ===== 精确求交：水界 = height < level − 0.5（逐块，1 块精度）=====
        boolean[][] exact = new boolean[w][w];
        int exactN = 0, coarseN = 0, diff = 0;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                exact[j][i] = h[j][i] < level - 0.5;
                if (exact[j][i]) exactN++;
                if (coarse[j][i]) coarseN++;
                if (exact[j][i] != coarse[j][i]) diff++;
            }
        }

        // ===== 边界带规模：exact 的"边缘块"数（与其 4 邻至少一个不同）=====
        int edgeBlocks = 0;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) {
                boolean me = exact[j][i];
                boolean nb = (i > 0 && exact[j][i - 1] != me) || (i < w - 1 && exact[j][i + 1] != me)
                        || (j > 0 && exact[j - 1][i] != me) || (j < w - 1 && exact[j + 1][i] != me);
                if (nb) edgeBlocks++;
            }
        }

        System.out.printf("=== LakeEdgeRefineProbe seed=%d 窗口 ±%d 块（%d×%d），参照水位 %.3f ===%n",
                seed, radius, w, w, level);
        System.out.printf("  现状（粗格连通区）：水格 %d%n", coarseN);
        System.out.printf("  精确求交（height < 水位−0.5，逐块）：水格 %d%n", exactN);
        System.out.printf("  ★ 两者不同的块：%d（%.1f%% 于窗口）%n", diff, 100.0 * diff / (w * w));
        System.out.printf("  ★ 精确水界的【边界块】数：%d  ⇒ 自适应细分只需在这些块附近加密%n",
                edgeBlocks);

        // ===== 代价估算 =====
        long t0 = System.nanoTime();
        double acc = 0;
        for (int j = 0; j < w; j++) {
            for (int i = 0; i < w; i++) acc += h[j][i];
        }
        long tFull = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("  代价参考：全域逐块遍历 %d 块 = %d ms（%s）%n",
                w * w, tFull, acc > 0 ? "" : "");

        // ===== ASCII 对照（水界形态）=====
        System.out.println();
        System.out.println("  【现状】粗格连通区（W=水 . =陆，每 2 块取 1）：");
        printAscii(coarse, w, 2);
        System.out.println("  【精确】height < 水位−0.5 的逐块水界（每 2 块取 1）：");
        printAscii(exact, w, 2);
        System.out.println();
        System.out.println("判读：① 边界块数 ⇒ 自适应细分的代价（越小越可行）；");
        System.out.println("      ② 若『精确』边界明显更贴合地形（锯齿细密而非直角）⇒ 值得采用；");
        System.out.println("      ③ 该方案**只改边界判定**，不动水位与连通语义 ⇒ 风险可控。");
    }

    private static void printAscii(boolean[][] m, int w, int step) {
        for (int j = 0; j < w; j += step) {
            StringBuilder sb = new StringBuilder("    ");
            for (int i = 0; i < w; i += step) sb.append(m[j][i] ? 'W' : '.');
            System.out.println(sb);
        }
    }
}
