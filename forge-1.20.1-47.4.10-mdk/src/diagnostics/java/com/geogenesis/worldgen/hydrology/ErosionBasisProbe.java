package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 【基准一致性】审计（2026-10）。
 *
 * <h2>待验证的假设（用户提出）</h2>
 * <p>"河流根本没跑在侵蚀后的地形上" —— 若成立，则河网用的地形基准（无侵蚀）
 * 与最终放置地形（含侵蚀）不一致，导致河床/水面关系错乱。</p>
 *
 * <h2>要量的三个地形基准</h2>
 * <ol>
 *   <li>{@code planOriginal} = {@code gen.sample()}（无侵蚀）—— 河网/雕刻用的基准；</li>
 *   <li>{@code eroded} = {@code gen.sampleWu()}（含侵蚀 tile）—— 真实地表；</li>
 *   <li>{@code placed} = {@code getChunkCells().height} —— 最终放置。</li>
 * </ol>
 * <p>三者应满足：{@code placed ≈ eroded}（若河网正确作用在侵蚀后地形上）。</p>
 *
 * <pre>{@code gradlew runErosionBasisProbe [-PprobeArgs="seed blockX blockZ radius"]}</pre>
 */
public final class ErosionBasisProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -313;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : -165;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 64;

        System.out.printf("=== ErosionBasisProbe seed=%d centre=(%d,%d) r=%d ===%n",
                seed, bx, bz, radius);
        System.out.printf("  skeletonRouting(生效) = %s%n", RiverLineNetwork.flowSkeletonRouting);

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);
        double hs = tp.horizontalScale();

        int n = 0, hasErosion = 0;
        double sumAbsDelta = 0, maxAbsDelta = 0;
        double sumPlacedMinusEroded = 0, maxPlacedMinusEroded = 0, minPlacedMinusEroded = 0;
        int shown = 0;

        for (int x = bx - radius; x <= bx + radius; x += 2) {
            for (int z = bz - radius; z <= bz + radius; z += 2) {
                Cell c = cellAt(gt, x, z);
                double plan = gen.sample(x / hs, z / hs).height;          // 无侵蚀
                double eroded = gen.sampleWu(x / hs, z / hs).height;      // 含侵蚀
                double placed = c.height;                                  // 最终
                n++;
                double delta = eroded - plan;      // 侵蚀增量
                if (Math.abs(delta) > 0.5) hasErosion++;
                sumAbsDelta += Math.abs(delta);
                if (Math.abs(delta) > maxAbsDelta) maxAbsDelta = Math.abs(delta);

                // placed 与 eroded 的差 = 雕刻量 + 基准错位
                double pm = placed - eroded;
                sumPlacedMinusEroded += pm;
                if (pm > maxPlacedMinusEroded) maxPlacedMinusEroded = pm;
                if (pm < minPlacedMinusEroded) minPlacedMinusEroded = pm;

                if (shown < 14 && Math.abs(delta) > 2.0) {
                    System.out.printf("    (%d,%d) 无侵蚀=%.2f 含侵蚀=%.2f ⇒ 侵蚀增量=%+.2f"
                                    + " | 放置=%.2f 水面=%.2f rt=%d isLake=%b%n",
                            x, z, plan, eroded, delta, placed, c.riverSurfaceY,
                            c.riverType, c.isLake);
                    shown++;
                }
            }
        }

        System.out.printf("%n采样列 = %d%n", n);
        System.out.printf("  · 侵蚀增量 |Δ|>0.5 的列 = %d (%.1f%%)%n",
                hasErosion, 100.0 * hasErosion / Math.max(1, n));
        System.out.printf("    平均 |侵蚀增量| = %.2f 块，最大 = %.2f 块%n",
                sumAbsDelta / Math.max(1, n), maxAbsDelta);
        System.out.printf("%n  【关键】placed − eroded（最终 vs 真实地表）%n");
        System.out.printf("    平均 = %+.2f 块，范围 = [%+.2f, %+.2f]%n",
                sumPlacedMinusEroded / Math.max(1, n), minPlacedMinusEroded, maxPlacedMinusEroded);
        System.out.println("    判读：若 |平均| ≫0 ⇒ 最终地形与真实侵蚀地表不一致");
        System.out.println("          （河道雕刻作用于【侵蚀前】基准 ⇒ 河床/水面关系错位）");
        System.out.printf("%nstatus=%s erosionDeltaAvg=%.2f placedMinusErodedAvg=%+.2f maxAbsErosion=%.2f%n",
                Math.abs(sumPlacedMinusEroded / Math.max(1, n)) > 2.0 ? "BASIS_MISMATCH" : "OK",
                sumAbsDelta / Math.max(1, n), sumPlacedMinusEroded / Math.max(1, n), maxAbsDelta);
    }

    private static Cell cellAt(GeoGenesisTerrain gt, int x, int z) {
        Cell[] cells = gt.getChunkCells(x >> 4, z >> 4);
        return cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
    }

    private ErosionBasisProbe() { }
}
