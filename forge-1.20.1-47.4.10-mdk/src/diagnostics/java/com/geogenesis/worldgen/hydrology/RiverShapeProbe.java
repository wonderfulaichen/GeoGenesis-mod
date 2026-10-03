package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 河道形态对比探针（2026-10）—— 量化"方块阶梯 vs 自然曲线"。
 *
 * <h2>指标</h2>
 * <ul>
 *   <li><b>网格占比</b>：节点落在 SKELETON_CELL_BLOCKS 网格线上的比例（越高越"方块"）</li>
 *   <li><b>轴向段 / 45°段占比</b>：线段方向的量化程度</li>
 *   <li><b>节点数/原节点数</b>：Catmull-Rom 细分倍率（&gt;1 ⇒ 已曲线化）</li>
 * </ul>
 *
 * <pre>{@code gradlew runRiverShapeProbe [-PprobeArgs="seed regionX regionZ radius"]}</pre>
 */
public final class RiverShapeProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int rx0 = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        int rz0 = args.length > 2 ? Integer.parseInt(args[2]) : -2;
        int rad = args.length > 3 ? Integer.parseInt(args[3]) : 1;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        HydrologyExperimentEngine engine = new HydrologyExperimentEngine(gen, seed);
        RiverLineNetwork net = engine.network();
        double hs = tp.horizontalScale();

        System.out.printf("=== RiverShapeProbe seed=%d region(%d,%d)+-%d ===%n", seed, rx0, rz0, rad);
        System.out.printf("  skeletonRouting=%s  SKELETON_SMOOTH=%s  CELL_BLOCKS=%d%n",
                RiverLineNetwork.flowSkeletonRouting, RiverLineNetwork.SKELETON_SMOOTH,
                RiverLineNetwork.SKELETON_CELL_BLOCKS);

        int rivers = 0, nodes = 0, onGrid = 0, axis = 0, diag = 0;
        int cell = RiverLineNetwork.SKELETON_CELL_BLOCKS;
        for (int rz = rz0 - rad; rz <= rz0 + rad; rz++) {
            for (int rx = rx0 - rad; rx <= rx0 + rad; rx++) {
                for (RiverLineRegion.RiverPolyline pl : net.region(rx, rz).rivers) {
                    rivers++;
                    int n = pl.nodes.length;
                    nodes += n;
                    for (int k = 0; k < n; k++) {
                        double bx = pl.nodes[k].x() * hs, bz = pl.nodes[k].z() * hs;
                        double mx = Math.abs(bx / cell - Math.rint(bx / cell));
                        double mz = Math.abs(bz / cell - Math.rint(bz / cell));
                        if (mx < 1e-6 || mz < 1e-6) onGrid++;
                        if (k > 0) {
                            double dx = Math.abs(bx - pl.nodes[k - 1].x() * hs);
                            double dz = Math.abs(bz - pl.nodes[k - 1].z() * hs);
                            if (dx < 1e-6 || dz < 1e-6) axis++;
                            else if (Math.abs(dx - dz) < 1e-6) diag++;
                        }
                    }
                }
            }
        }
        int segs = Math.max(1, nodes - rivers);
        System.out.printf("%n河流 %d 条，节点 %d 个%n", rivers, nodes);
        System.out.printf("  ★ 节点落在 %d 块网格线上 = %d (%.1f%%)  ← 越低越自然%n",
                cell, onGrid, 100.0 * onGrid / Math.max(1, nodes));
        System.out.printf("  · 轴向线段(0/90)     = %d (%.1f%%)%n", axis, 100.0 * axis / segs);
        System.out.printf("  · 45° 线段            = %d (%.1f%%)%n", diag, 100.0 * diag / segs);
        System.out.printf("%nstatus=onGrid=%.1f%% axis=%.1f%% diag45=%.1f%%%n",
                100.0 * onGrid / Math.max(1, nodes), 100.0 * axis / segs, 100.0 * diag / segs);
    }

    private RiverShapeProbe() { }
}