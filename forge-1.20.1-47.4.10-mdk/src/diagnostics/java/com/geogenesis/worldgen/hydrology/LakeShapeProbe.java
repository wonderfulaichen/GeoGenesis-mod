package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 湖泊形态探针（2026-09-09 B1 验证）。
 *
 * <p>用户反馈："现在的湖泊非常假，完全就是一个圆盘；而且只匹配未侵蚀地形，
 * 侵蚀后原位置可能就形成不了湖了。" 本探针实测两件事：</p>
 * <ol>
 *   <li><b>湖岸是不是圆</b>：沿湖心向 16 个方位射线走，找"地形与湖面的交线"
 *       （= 岸线半径）。真圆 → 各方位半径标准差≈0、长短轴比≈1；自然湖 →
 *       各方位半径起伏明显、长短轴比 &gt; 1.3。</li>
 *   <li><b>湖是否吃侵蚀后地形</b>：对同一湖，分别用【无侵蚀 sample()】与
 *       【生产路径 getChunkCells()（含侵蚀合成）】判定淹水格数与水深，
 *       输出两者差异 —— 差异为 0 说明湖面与侵蚀后地形脱节（旧病灶）。</li>
 * </ol>
 *
 * <p>用法：gradlew runLakeShapeProbe [-PprobeArgs="seed regionRadius"]</p>
 */
public final class LakeShapeProbe {

    private static final int RAYS = 16;

    private LakeShapeProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int regionR = args.length > 1 ? Integer.parseInt(args[1]) : 1;

        TerrainParams params = TerrainParams.defaults();
        double hs = params.horizontalScale();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        generator.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        terrain.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(generator, seed);
        RiverLineNetwork net = engine.network();

        List<RiverLineRegion.LakeNode> lakes = new ArrayList<>();
        for (int rz = -regionR; rz <= regionR; rz++) {
            for (int rx = -regionR; rx <= regionR; rx++) {
                lakes.addAll(net.region(rx, rz).lakes);
            }
        }

        System.out.println("=== LakeShapeProbe seed=" + seed + " hs=" + hs
                + " regionR=" + regionR + " lakes=" + lakes.size() + " ===");
        if (lakes.isEmpty()) {
            System.out.println("no lakes found; try larger regionR");
            return;
        }

        double sumAspect = 0.0, sumCv = 0.0;
        int measured = 0, roundish = 0, totalRawCells = 0, totalErodedCells = 0;
        double maxAspect = 0.0, sumRelDiff = 0.0;

        for (int li = 0; li < lakes.size(); li++) {
            RiverLineRegion.LakeNode lk = lakes.get(li);
            double spill = lk.height;

            // ---- 1) 岸线半径（沿 16 方位找 生产地形 与 spill 的交线）----
            double[] radii = new double[RAYS];
            int found = 0;
            double rMax = Math.max(lk.radius, 40.0) * 2.0;   // 搜索半径（wu）
            for (int a = 0; a < RAYS; a++) {
                double ang = 2.0 * Math.PI * a / RAYS;
                double dx = Math.cos(ang), dz = Math.sin(ang);
                boolean prevAbove = false;
                double prevR = 0.0;
                double hitR = -1.0;
                for (int step = 1; step <= 200; step++) {
                    double r = rMax * step / 200.0;
                    double wx = lk.x + dx * r, wz = lk.z + dz * r;
                    double h = prodHeight(terrain, generator, wx, wz, hs);
                    boolean above = h >= spill;
                    if (step > 1 && above != prevAbove) {
                        hitR = prevR + (r - prevR) * 0.5;
                        break;
                    }
                    prevAbove = above;
                    prevR = r;
                }
                if (hitR >= 0.0) { radii[a] = hitR; found++; }
            }
            if (found < 8) continue;

            double mean = 0.0;
            for (double r : radii) mean += r;
            mean /= RAYS;
            double var = 0.0, rmin = Double.MAX_VALUE, rmax = 0.0;
            for (double r : radii) {
                var += (r - mean) * (r - mean);
                rmin = Math.min(rmin, r);
                rmax = Math.max(rmax, r);
            }
            double sd = Math.sqrt(var / RAYS);
            double cv = mean > 1e-6 ? sd / mean : 0.0;      // 变异系数：0=正圆
            double aspect = rmin > 1e-6 ? rmax / rmin : 99.0; // 长短轴比：1=正圆
            sumCv += cv; sumAspect += aspect; measured++;
            maxAspect = Math.max(maxAspect, aspect);
            if (cv < 0.15 && aspect < 1.3) roundish++;

            System.out.printf("lake[%d] spill=%.2f radius=%.1f cells=%d | shoreCV=%.3f aspect=%.2f rMin=%.1f rMax=%.1f %s%n",
                    li, spill, lk.radius, lk.hasOutline() ? lk.cellX.length : 0,
                    cv, aspect, rmin, rmax,
                    (cv < 0.15 && aspect < 1.3) ? "<== ROUND" : "");

            // ---- 2) 生产路径【真实水格】集合 → 形状统计 ----
            //   ★ 不能自己判 "height < spill"：spill 是绝对高程，山脚/远谷大量地形
            //     低于它，会把整片低地误算成湖（实测 floodedDist mean≈60wu 而湖半径
            //     仅 19wu —— 那是探针自身的假阳性）。必须只读生产 Cell 的淹水判定
            //     （cell.isLake / riverSurfaceY），即玩家真正看到的水面。
            int eroded = 0;
            int span = (int) Math.max(8.0, rMax);
            List<int[]> wet = new ArrayList<>();
            // ★ 侵蚀改写量：同一批【生产判定为水】的格上，比较无侵蚀高度与侵蚀后高度。
            //   差值 ≠ 0 ⇒ 湖盆确实建在【侵蚀后】地形上（旧行为是湖面/盆底同取无侵蚀
            //   计划值，差值恒 0 = 用户反馈的"湖只匹配未侵蚀地形"）。
            double sumDelta = 0.0, maxAbsDelta = 0.0;
            int nDelta = 0;
            for (int dz = -span; dz <= span; dz += 2) {
                for (int dx = -span; dx <= span; dx += 2) {
                    double wx = lk.x + dx, wz = lk.z + dz;
                    Cell c = prodCell(terrain, wx, wz, hs);
                    double hProd = c.height;
                    boolean isWet = c.isLake && c.riverSurfaceY > hProd;
                    if (!isWet) continue;
                    eroded++;
                    wet.add(new int[]{(int) wx, (int) wz});
                    double hRaw = generator.sample(wx, wz).height;
                    double d = hProd - hRaw;
                    sumDelta += d;
                    maxAbsDelta = Math.max(maxAbsDelta, Math.abs(d));
                    nDelta++;
                }
            }
            System.out.printf("        basinErosionDelta: mean=%+.2f maxAbs=%.2f over %d wet cells (0 = lake ignores erosion)%n",
                    nDelta > 0 ? sumDelta / nDelta : 0.0, maxAbsDelta, nDelta);
            // 形状统计（4πA/P² 圆度：正圆=1，越不规则越小）
            if (!wet.isEmpty()) {
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
                for (int[] p : wet) {
                    minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                    minZ = Math.min(minZ, p[1]); maxZ = Math.max(maxZ, p[1]);
                }
                double w = (maxX - minX) + 2.0, h = (maxZ - minZ) + 2.0;
                double bboxAspect = Math.max(w, h) / Math.max(1e-6, Math.min(w, h));
                double area = wet.size() * 4.0;                  // 步长 2wu → 每格 4wu²
                // 周长：统计集合边界边数（4 邻不全在集合内）
                java.util.Set<Long> set = new java.util.HashSet<>();
                for (int[] p : wet) set.add(((long) p[0] << 32) ^ (p[1] & 0xffffffffL));
                int perim = 0;
                for (int[] p : wet) {
                    int[][] nb = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
                    for (int[] d : nb) {
                        long key = ((long) (p[0] + d[0]) << 32) ^ ((p[1] + d[1]) & 0xffffffffL);
                        if (!set.contains(key)) perim++;
                    }
                }
                double perimeter = perim * 2.0;
                double circularity = perimeter > 1e-6 ? 4.0 * Math.PI * area / (perimeter * perimeter) : 0.0;
                double fillRatio = area / Math.max(1e-6, w * h);   // 填充满包围盒的程度：圆≈0.785
                System.out.printf("        wetShape: cells=%d bbox=%.1f x %.1f wu aspect=%.2f circularity=%.3f fill=%.3f (disc: circ~1 fill~0.785)%n",
                        wet.size(), w, h, bboxAspect, circularity, fillRatio);
            }
            totalErodedCells += eroded;
        }

        System.out.println("---- summary ----");
        System.out.printf("measured=%d roundish=%d meanCV=%.3f meanAspect=%.2f maxAspect=%.2f%n",
                measured, roundish, measured > 0 ? sumCv / measured : 0.0,
                measured > 0 ? sumAspect / measured : 0.0, maxAspect);
        System.out.printf("wetCells total=%d (production path)%n", totalErodedCells);
        System.out.println("(roundish low + relDiff != 0 = lake follows eroded terrain, not a disc)");
    }

    /** 生产路径 Cell：sampleCell 入参是 BLOCK（内部 ÷hs 成 wu），wu 必须先 ×hs。 */
    private static Cell prodCell(GeoGenesisTerrain terrain, double wx, double wz, double hs) {
        return terrain.sampleCell(wx * hs, wz * hs);
    }

    /** 生产路径高度（含侵蚀合成 + 水文雕刻回写），即玩家实际看到的地面。 */
    private static double prodHeight(GeoGenesisTerrain terrain, CellGenerator generator,
                                     double wx, double wz, double hs) {
        Cell c = prodCell(terrain, wx, wz, hs);
        return c.height;
    }
}
