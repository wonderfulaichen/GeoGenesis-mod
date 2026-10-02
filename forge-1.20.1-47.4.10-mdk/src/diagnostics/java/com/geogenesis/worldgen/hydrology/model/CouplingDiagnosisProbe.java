package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 耦合诊断：为什么"不耦合"有湖、"耦合"后湖全干？
 *
 * <p>做法：同一地形与气候，分别用两种来水方式求解，逐个盆地打印
 * {@code catchment(汇水格数)}、{@code inflow}、{@code requiredArea = inflow/(E·A)}、
 * 以及最终 {@code level}。目的是把"耦合路径把湖判干"定位到具体哪一步，
 * 而不是继续猜。</p>
 */
public final class CouplingDiagnosisProbe {

    public static void main(String[] args) {
        long seed = ViewSpec.SEED;
        int radius = 200;
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double sea = gen.seaLevel(), hs = tp.horizontalScale();
        int step = ViewSpec.STEP;
        int n = 2 * radius / step + 1;

        WindowOutlets.Terrain terrain = (bx, bz) -> gen.erodedHeightForRouting(bx / hs, bz / hs);
        var built = WindowOutlets.build(n, n, ViewSpec.CENTER_X, ViewSpec.CENTER_Z,
                radius, step, sea, terrain);
        var surface = EquilibriumWater.compute(built.bed(), n, n, built.drains(), built.exterior());
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
        boolean[] border = new boolean[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                border[i * n + j] = i == 0 || j == 0 || i == n - 1 || j == n - 1;
            }
        }
        var balance = new LakeWaterBalance(n, n, step, built.bed(), surface);

        var noCouple = balance.solve(climate, built.drains(), border);
        System.out.println("=== coupling diagnosis ===");
        System.out.printf("no-coupling: basins=%d dryBasins=%d lakeCells=%d%n",
                noCouple.basins().size(), noCouple.dryBasins(), countTrue(noCouple.lake()));

        var river = RiverNetwork.build(n, n, surface.level(), surface.parent(),
                noCouple.runoffPerCell(), noCouple.overflowAt(), surface.resolved(),
                RiverNetwork.Params.defaults(), 0);
        double maxQ = 0, sumQ = 0;
        for (double q : river.discharge()) { maxQ = Math.max(maxQ, q); sumQ += q; }
        System.out.printf("river: channelCells=%d maxDischarge=%.4f sumDischarge=%.4f%n",
                river.channelCells(), maxQ, sumQ);

        var coupled = balance.solve(climate, built.drains(), border, river.discharge());
        System.out.printf("coupled    : basins=%d dryBasins=%d lakeCells=%d%n",
                coupled.basins().size(), coupled.dryBasins(), countTrue(coupled.lake()));

        // 逐盆地对比前 6 个：catchment / inflow / 是否 fed
        System.out.println("catchmentCells  geometricInflow  riverFlowAtSeed  lakeArea(no/coupled)");
        int shown = 0;
        for (int b = 0; b < noCouple.basins().size() && shown < 6; b++) {
            var nb = noCouple.basins().get(b);
            var cb = coupled.basins().size() > b ? coupled.basins().get(b) : null;
            double flowAtSeed = river.discharge()[nb.seedCell()];
            System.out.printf("%14.0f  %15.4f  %16.4f  %8.0f / %-8s%n",
                    nb.catchmentCells(), nb.inflow(), flowAtSeed, nb.areaCells(),
                    cb == null ? "n/a" : String.format("%.0f", cb.areaCells()));
            shown++;
        }
        System.out.println("READ: 耦合后 lake 归零 ⇒ 看 riverFlowAtSeed 是否远小于 geometricInflow。");
    }

    private static int countTrue(boolean[] a) {
        int c = 0;
        for (boolean b : a) if (b) c++;
        return c;
    }

    private CouplingDiagnosisProbe() { }
}
