package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 河床审计（2026-10）—— 按用户定义判"河流是否真的在【原地形上切出】河道"。
 *
 * <h2>用户定义（判据来源）</h2>
 * <ul>
 *   <li><b>湖泊 = 原地形上填水</b> ⇒ 水面高于盆底地形是正常的；</li>
 *   <li><b>河流 = 在原地形上切出河道（只下挖）</b> ⇒
 *       <b>河床（放置后的地形）绝不能高于原地形</b>。</li>
 * </ul>
 *
 * <h2>判据</h2>
 * <pre>  河流列（riverType!=0 且非湖）  ⇒  placedHeight ≤ originalTerrain + ε</pre>
 * <p>违反 ⇒ <b>河床被抬高</b> = 河道不是"切"出来的，而是"垫"出来的（用户截图形态）。</p>
 *
 * <p>同时把差值分解为：{@code 河床抬高 = 放置高度 − 未侵蚀原始地形}，
 * 并报告 {@code carved − original}（carver 计划层是否已经抬高）。</p>
 *
 * <pre>{@code gradlew runRiverBedAuditProbe [-PprobeArgs="seed blockX blockZ radius"]}</pre>
 */
public final class RiverBedAuditProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -313;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : -165;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 128;

        System.out.printf("=== RiverBedAuditProbe seed=%d centre=(%d,%d) r=%d ===%n",
                seed, bx, bz, radius);
        System.out.printf("  skeletonRouting(生效) = %s%n", RiverLineNetwork.flowSkeletonRouting);
        System.out.println("  判据：河流列 placedHeight ≤ 未侵蚀原始地形（河=切出来的，不是垫出来的）");

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        GeoGenesisTerrain gt = new GeoGenesisTerrain(gen);
        gt.seed(seed);
        double hs = tp.horizontalScale();
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);

        int river = 0, raised = 0, raised1 = 0, raised4 = 0;
        double worst = 0, sum = 0;
        int planRaised = 0, planRaisedSample = 0;
        int shown = 0;
        // ★ 统计【水】的分类：真湖（inDomain）vs 非湖域却被标成水
        int water = 0, lakeByPlan = 0, lakePlanNoDomain = 0, carvedWater = 0;

        for (int x = bx - radius; x <= bx + radius; x++) {
            for (int z = bz - radius; z <= bz + radius; z++) {
                Cell c = cellAt(gt, x, z);
                if (c.riverType == 0 || !(c.riverSurfaceY > c.height)) continue;
                water++;
                double orig = gen.sample(x / hs, z / hs).height;
                HydrologyBlockCarvedColumn col =
                        HydrologyBlockCarver.carveColumnAt(engine, x, z, orig, hs);
                if (col != null && col.lakePlan()) {
                    lakeByPlan++;
                    // lakePlan=true ⇒ 声称"这是湖"；检查它是否真在某湖的域内
                    boolean inDomain = col.lakeNode() != null
                            && col.lakeNode().inDomain(x / hs, z / hs, col.lakeNode().cellHalf);
                    if (!inDomain) {
                        lakePlanNoDomain++;
                        if (shown < 10) {
                            System.out.printf("    [非湖域却标湖] (%d,%d) 水面=%.2f 地形=%.2f"
                                            + " 未侵蚀=%.2f 水面−原地面=%+.2f lakeNode=%s%n",
                                    x, z, c.riverSurfaceY, c.height, orig,
                                    c.riverSurfaceY - orig, col.lakeNode() == null ? "null" : "有");
                            shown++;
                        }
                    }
                } else if (col != null && col.carvedGroundY() < col.originalGroundY() - 1e-6) {
                    carvedWater++;
                }
                if (c.isLake) continue;                          // 湖不参与河床判据
                river++;
                double raise = c.height - orig;
                if (raise > 0.5) {
                    raised++;
                    sum += raise;
                    if (raise > 1.0) raised1++;
                    if (raise > 4.0) raised4++;
                    if (raise > worst) worst = raise;
                    if (col != null && col.carvedGroundY() > col.originalGroundY() + 0.5) {
                        planRaised++;
                        if (planRaisedSample < 8) {
                            planRaisedSample++;
                            System.out.printf("    [计划层已抬高] (%d,%d) carved=%.2f orig=%.2f"
                                            + " ⇒ +%.2f  放置=%.2f 未侵蚀=%.2f ⇒ +%.2f%n",
                                    x, z, col.carvedGroundY(), col.originalGroundY(),
                                    col.carvedGroundY() - col.originalGroundY(),
                                    c.height, orig, raise);
                        }
                    }
                    if (shown < 12) {
                        System.out.printf("    (%d,%d) 放置=%.2f 未侵蚀原地形=%.2f ⇒ 抬高=%.2f"
                                        + " 水面=%.2f lakePlan=%b%n",
                                x, z, c.height, orig, raise, c.riverSurfaceY,
                                col != null && col.lakePlan());
                        shown++;
                    }
                }
            }
        }

        System.out.printf("%n有水列 = %d%n", water);
        System.out.printf("  · lakePlan=true（按湖填水）    = %d (%.1f%%)%n",
                lakeByPlan, 100.0 * lakeByPlan / Math.max(1, water));
        System.out.printf("  ★ 其中【不在任何湖域内】       = %d (%.1f%%)  ← 被误判成湖%n",
                lakePlanNoDomain, 100.0 * lakePlanNoDomain / Math.max(1, water));
        System.out.printf("  · 走了河分支且已雕刻           = %d (%.1f%%)%n",
                carvedWater, 100.0 * carvedWater / Math.max(1, water));
        System.out.printf("%n河流列（riverType!=0 且非湖） = %d%n", river);
        System.out.printf("  ★ 河床高于未侵蚀原地形 >0.5 = %d (%.1f%%)  <- 河道被垫高%n",
                raised, 100.0 * raised / Math.max(1, river));
        System.out.printf("  ★ 其中 >1 块 = %d (%.1f%%)；>4 块 = %d (%.1f%%)%n",
                raised1, 100.0 * raised1 / Math.max(1, river),
                raised4, 100.0 * raised4 / Math.max(1, river));
        System.out.printf("    平均抬高 = %.2f 块，最大 = %.2f 块%n",
                raised == 0 ? 0 : sum / raised, worst);
        System.out.printf("  · 其中【carver 计划层 carved > original】= %d ← 根因在计划层%n", planRaised);
        System.out.printf("%nstatus=%s river=%d raised=%d(%.1f%%) raised>4=%d worst=%.2f planRaised=%d%n",
                raised4 > 0 ? "DEFECT" : (raised > 0 ? "SUSPECT" : "OK"),
                river, raised, 100.0 * raised / Math.max(1, river), raised4, worst, planRaised);
    }

    private static Cell cellAt(GeoGenesisTerrain gt, int x, int z) {
        Cell[] cells = gt.getChunkCells(x >> 4, z >> 4);
        return cells[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
    }

    private RiverBedAuditProbe() { }
}
