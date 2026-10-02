package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 水量平衡对照：同一地形、同一平衡水面，比较【几何蓄水】与【收支平衡后】的湖。
 *
 * <p>判据是<b>方向性</b>而非数值贴合（无实验数据可比）：</p>
 * <ol>
 *   <li><b>干旱化单调性</b>：降水降低、蒸发升高 ⇒ 湖面积必须单调不增。
 *       若湖面积在干旱时反而变大，判据失败（说明收支符号或聚合写反）。</li>
 *   <li><b>零来水 ⇒ 无湖</b>：降水=0 时不得存在任何非海湖面。</li>
 *   <li><b>极端湿润 ⇒ 回到几何上限</b>：来水充足时湖面积必须接近几何蓄水面积
 *       （否则说明限幅把水凭空丢掉了）。</li>
 * </ol>
 *
 * <p><b>声明的边界</b>：汇水面积只由窗口内排水树给出，靠近边框的盆地会在窗口外
 * 继续获得来水 ⇒ 标 {@code catchmentTruncated}，不声称是全流域结果。</p>
 */
public final class LakeBalanceProbe {

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 256;
        int step = 4, cx = -140, cz = 137;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double sea = gen.seaLevel(), hs = tp.horizontalScale();
        int n = 2 * radius / step + 1;

        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                bed[i * n + j] = gen.erodedHeightForRouting(
                        (cx - radius + i * step) / hs, (cz - radius + j * step) / hs);
            }
        }
        boolean[] seaMask = new boolean[n * n];
        boolean[] border = new boolean[n * n];
        double[] exterior = new double[n * n];
        java.util.Arrays.fill(exterior, Double.NaN);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                seaMask[k] = bed[k] < sea;
                if (i == 0 || j == 0 || i == n - 1 || j == n - 1) {
                    border[k] = true;
                    int oi = i == 0 ? -1 : i == n - 1 ? n : i;
                    int oj = j == 0 ? -1 : j == n - 1 ? n : j;
                    exterior[k] = Math.max(gen.erodedHeightForRouting(
                            (cx - radius + oi * step) / hs, (cz - radius + oj * step) / hs), sea);
                }
            }
        }
        EquilibriumWater.Surface surface = EquilibriumWater.compute(bed, n, n, seaMask, exterior);
        LakeWaterBalance balance = new LakeWaterBalance(n, n, step, bed, surface);

        // ★ 几何上限必须用【与水量平衡同一套过滤】的洼地，否则两者口径不同、不可比：
        //   过滤前 105 格 vs 过滤后 51 格曾被我直接相比 ⇒ 假 FAIL（已踩）。
        //   做法：把同样输入交给 balance 求解，用"无蒸发（蓄满）"作为几何上限。
        int geometricCells = 0;
        {
            var unlimited = balance.solve(new LakeWaterBalance.Climate(1.0, 0.0, 1.0), seaMask, border);
            for (int k = 0; k < n * n; k++) if (unlimited.lake()[k]) geometricCells++;
        }
        // 未过滤的原始几何面积（仅作背景参考，不参与判据）。
        int rawGeometricCells = 0;
        for (int k = 0; k < n * n; k++) {
            if (!seaMask[k] && surface.resolved()[k] && surface.level()[k] - bed[k] > 1e-6) rawGeometricCells++;
        }

        Path dir = Path.of("build/hydro-reassessment/balance-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        StringBuilder log = new StringBuilder();
        log.append("WATER BALANCE vs GEOMETRY; no time stepping.\n");
        log.append(String.format("seed=%d centre=(%d,%d) radius=%d step=%d cells=%d sea=%.1f%n",
                seed, cx, cz, radius, step, n * n, sea));
        log.append(String.format("geometricLakeCells(after pseudo-depression filter)=%d ; rawGeometric=%d ; unresolved=%d%n",
                geometricCells, rawGeometricCells, surface.unresolvedCells()));
        // ★ 诊断：湖盆规模分布。若大量湖盆只有 1~2 格，说明"分组"把本应连通的湖切碎了，
        //   那样"最大水深"必然很小且不真实。必须先看清再解释数值。
        {
            int big = 0, single = 0;
            java.util.List<Integer> list = new java.util.ArrayList<>();
            var probe = balance.solve(new LakeWaterBalance.Climate(0.002, 0.002, 0.3), seaMask, border);
            for (var b : probe.basins()) { list.add((int) b.areaCells()); if (b.areaCells() <= 2) single++; if (b.areaCells() >= 20) big++; }
            java.util.Collections.sort(list);
            log.append(String.format("basinAreaDistribution cells=%s%n", list));
            log.append(String.format("basins=%d  singleOrTiny(<=2 cells)=%d  large(>=20 cells)=%d%n",
                    list.size(), single, big));
        }
        log.append("cases: (precip, evap, runoff)  -> lakeCells  basins  overflowBasins  dryBasins\n");

        double[][] cases = {
                {0.000, 0.002, 0.3},   // 零降水 ⇒ 必须无湖
                {0.001, 0.002, 0.3},   // 极干
                {0.002, 0.002, 0.3},   // 干
                {0.004, 0.002, 0.3},   // 半湿
                {0.010, 0.002, 0.3},   // 湿
                {10.0, 0.002, 0.3},    // 极湿 ⇒ 应回到几何上限
        };
        int previousCells = Integer.MAX_VALUE;
        boolean monotone = true;
        boolean zeroRainMeansNoLake = false;
        double wetCells = 0;
        int resolvedLakeCells = 0, unresolvedLakeCells = 0;
        BufferedImage strip = new BufferedImage(n, n * cases.length, BufferedImage.TYPE_INT_RGB);

        for (int c = 0; c < cases.length; c++) {
            LakeWaterBalance.Climate climate =
                    new LakeWaterBalance.Climate(cases[c][0], cases[c][1], cases[c][2]);
            LakeWaterBalance.Result r = balance.solve(climate, seaMask, border);
            int lakeCells = 0, overflow = 0, truncated = 0;
            double maxDepth = 0;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    int k = i * n + j;
                    int gray = shade(bed, n, i, j);
                    int color;
                    if (seaMask[k]) color = 0x1F4E79;
                    else if (r.lake()[k]) {
                        lakeCells++;
                        double d = r.depth()[k];
                        maxDepth = Math.max(maxDepth, d);
                        color = d > 5 ? 0x123A80 : d > 1 ? 0x1F6FD0 : 0x39B7E0;
                        // 同一批格按窗口可信度分开统计（避免把未决格算成结论）。
                        if (c == 1) {
                            if (surface.resolved()[k]) resolvedLakeCells++; else unresolvedLakeCells++;
                        }
                    } else color = gray;
                    strip.setRGB(i, c * n + j, color);
                }
            }
            for (LakeWaterBalance.Basin b : r.basins()) {
                if (b.overflow()) overflow++;
                if (b.catchmentTruncated()) truncated++;
            }            log.append(String.format("  p=%.4f e=%.4f k=%.2f -> %6d  %5d  %5d  %5d  maxDepth=%.2f%n",
                    cases[c][0], cases[c][1], cases[c][2], lakeCells,
                    r.basins().size(), overflow, r.dryBasins(), maxDepth));
            // ★ 判据方向：案例序列是【由干到湿】，故降水增加时湖面积必须【单调不减】。
            //   之前写成"不得增加"，方向写反 ⇒ 假 FAIL（本轮实测踩到）。
            if (c > 0 && lakeCells < previousCells) monotone = false;
            if (c == 0) zeroRainMeansNoLake = lakeCells == 0;   // ★ 真正检查零降水，不是恒真式
            previousCells = lakeCells;
            if (c == cases.length - 1) wetCells = lakeCells;
        }
        // 判据 3：极湿时应达几何上限（允许 2% 差，因"湖 ≥ 0.05 块"与几何阈值口径略异）。
        boolean reachesUpperBound = Math.abs(wetCells - geometricCells) <= 0.02 * geometricCells + 5;
        log.append(String.format("criterion1 wetter-never-shrinks lakes is monotone = %s%n", monotone));
        log.append(String.format("criterion2 zero precipitation gives no lake = %s%n", zeroRainMeansNoLake));
        log.append(String.format("criterion3 very wet reaches geometric bound = %s (wet=%d geometric=%d)%n",
                reachesUpperBound, (int) wetCells, geometricCells));
        log.append(String.format("NOTE: %d of %d geometric lake cells are window-dependent (unresolved=%d).%n",
                unresolvedLakeCells, unresolvedLakeCells + resolvedLakeCells, surface.unresolvedCells()));

        ImageIO.write(strip, "png", dir.resolve("balance.png").toFile());
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.print(log);
        System.out.println("IMAGE=" + dir.resolve("balance.png").toAbsolutePath());
    }

    private static int shade(double[] bed, int n, int i, int j) {
        int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
        int j0 = Math.max(0, j - 1), j1 = Math.min(n - 1, j + 1);
        double dx = (bed[i1 * n + j] - bed[i0 * n + j]) / (i1 - i0);
        double dz = (bed[i * n + j1] - bed[i * n + j0]) / (j1 - j0);
        double light = (0.5 * dx + 0.7 - 0.51 * dz) / Math.sqrt((dx * dx + 1 + dz * dz) * 1.0001);
        int gray = (int) (60 + 175 * Math.max(0, light));
        return (gray << 16) | (gray << 8) | gray;
    }

    private LakeBalanceProbe() { }
}
