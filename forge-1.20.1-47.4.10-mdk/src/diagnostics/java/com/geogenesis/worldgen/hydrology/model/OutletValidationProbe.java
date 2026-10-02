package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 验证边界出口补全是否正确：<b>用"边界出口"能否替代"必须够到海"？</b>
 *
 * <h2>判据（硬）</h2>
 * <p>对同一世界点，两种构造必须给出<b>相同的稳定水位</b>：</p>
 * <ol>
 *   <li><b>A：仅真实海格为出口</b>（参考）。窗口必须大到含海；小窗口则 {@code NO-SEA}。</li>
 *   <li><b>B：边界格按窗外真实地形给外部水位</b>（{@link WindowOutlets}）。任何窗口都有定义。</li>
 * </ol>
 * <p>若 A 收敛值 == B 在小半径下的值，说明边界出口<b>没有引入虚假排水</b>，
 * 只是把"必须够到海"这个几何要求替换成了"窗外地形已知"这个更弱的要求。</p>
 *
 * <h2>同时验证收敛判据仍然有效</h2>
 * <p>补全出口后，结果可信度仍须由"计入窗外地形后是否改变"判定，
 * 不能因为"有出口"就当作可信。本探针同时报告 resolved 比例。</p>
 */
public final class OutletValidationProbe {

    private static final int STEP = 4;

    public static void main(String[] args) {
        long seed = 9139912035078620160L;
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double sea = gen.seaLevel(), hs = tp.horizontalScale();
        WindowOutlets.Terrain terrain = (bx, bz) -> gen.erodedHeightForRouting(bx / hs, bz / hs);

        // 用 InlandRadiusProbe 的实测结果作参考值：
        //   (-600,137) 仅海出口时，radius=512/1024 收敛到 136.776
        int[][] probes = {{-600, 137}, {-140, 137}, {400, 137}, {-4000, 2900}};
        int[] radii = {128, 256, 512, 1024};

        System.out.println("=== window outlets vs sea-only drains ===");
        System.out.println("probe              radius |     sea-only A |   outlets B | match | B resolved%");
        for (int p = 0; p < probes.length; p++) {
            int px = probes[p][0], pz = probes[p][1];
            for (int radius : radii) {
                int n = 2 * radius / STEP + 1;
                int center = (n / 2) * n + n / 2;

                // A：只把海格当出口
                double[] bedA = new double[n * n];
                boolean[] seaA = new boolean[n * n];
                double[] extA = new double[n * n];
                java.util.Arrays.fill(extA, Double.NaN);
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        int k = i * n + j;
                        bedA[k] = terrain.height(px - radius + i * (double) STEP,
                                pz - radius + j * (double) STEP);
                        seaA[k] = bedA[k] < sea;
                    }
                }
                double levelA = Double.NaN;
                try {
                    // A 组只喂海格；无海时 compute 会抛"没有排水终端"。
                    //   ⚠ 这正是要在 B 组解决的问题，因此这里必须捕获而不是让程序退出。
                    levelA = EquilibriumWater.compute(bedA, n, n, seaA, extA).level()[center];
                } catch (IllegalArgumentException noSea) { /* NO-SEA，预期之内 */ }

                // B：边界格按窗外真实地形给出外部水位（任何窗口都有定义）
                var built = WindowOutlets.build(n, n, px, pz, radius, STEP, sea, terrain);
                EquilibriumWater.Surface sB =
                        EquilibriumWater.compute(built.bed(), n, n, built.drains(), built.exterior());
                double levelB = sB.level()[center];
                int resolved = 0;
                for (int k = 0; k < n * n; k++) if (sB.resolved()[k]) resolved++;

                String match = Double.isNaN(levelA) ? "(A none)"
                        : (Math.abs(levelA - levelB) < 1e-9 ? "YES" : "NO  <<<");
                System.out.printf("(%5d,%5d) r=%5d | %14s | %11.3f | %-8s | %5.1f%%%n",
                        px, pz, radius,
                        Double.isNaN(levelA) ? "NO-SEA" : String.format("%.3f", levelA),
                        levelB, match, 100.0 * resolved / (n * (double) n));
            }
            System.out.println();
        }
        System.out.println("READ: 同一行内 A==B ⇒ 边界出口未引入虚假排水。");
        System.out.println("      小半径下 A 缺席但 B 仍给出与 A 大半径一致的值 ⇒ 出口补全成立。");
        System.out.println("LIMIT: 仅测 step=4、四个点位；更大半径未测。");
    }

    private OutletValidationProbe() { }
}
