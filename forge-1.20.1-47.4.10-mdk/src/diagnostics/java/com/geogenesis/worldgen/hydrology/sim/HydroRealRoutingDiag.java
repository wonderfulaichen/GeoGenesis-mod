package com.geogenesis.worldgen.hydrology.sim;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 诊断：<b>真实地形上的 D8 流向与笔直游程</b>（定位"平原网格直线"的确切来源）。
 *
 * <p><b>背景</b>：生产出图（seed 9139912035078620160 @(-140,137) r=700）中出现
 * "网格状笔直长直线"（像素实测最长竖直 74 块、水平 26 块、≥20 块的竖直段 24 段）。
 * 已排除"窗口边界 ε 阶梯"这一条（修复后数字一字未变）⇒ <b>必须先在真实地形上量</b>，
 * 而不是继续在合成地形上推测。</p>
 *
 * <h3>本探针回答三个问题</h3>
 * <ol>
 *   <li><b>盆地占比</b>：真实地形里 {@code fill − h > levelEps} 的格有多少？
 *       （若极少 ⇒ ε 阶梯不是主因，直线另有来源）</li>
 *   <li><b>流向分布</b>：D8 是否高度集中于少数方向？（合成平原上实测 100% 单方向）</li>
 *   <li><b>笔直游程</b>：沿 {@code down} 链的连续同向步数有多长？
 *       这是中心线笔直度的<b>直接来源</b>（{@code HydroFlowGeometry} 走的就是这条链）。</li>
 * </ol>
 *
 * <p>运行：{@code gradlew runHydroRealRoutingDiag [-PprobeArgs="seed tx tz nd"]}</p>
 */
public final class HydroRealRoutingDiag {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        int tx0 = args.length > 1 ? Integer.parseInt(args[1]) : -2;
        int tz0 = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        int nd = args.length > 3 ? Integer.parseInt(args[3]) : 1;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        final double sea = 63.0;

        HydroSampler sampler = new HydroSampler() {
            @Override public double height(double x, double z) {
                return gen.erodedHeightForRouting(x, z);
            }
        };
        HydroConfig cfg = HydroConfig.defaults().withSeaLevel(sea);
        HydroWorldSolver solver = new HydroWorldSolver(seed, cfg, sampler);

        System.out.printf("=== HydroRealRoutingDiag seed=%d tiles (%d,%d)+%d  sea=%.0f ===%n",
                seed, tx0, tz0, nd, sea);
        System.out.printf("CELL_BLOCKS=%d TILE_CORE_CELLS=%d HALO=%d FILL_EPS=%.1e levelEps=%.1e%n",
                HydroContract.CELL_BLOCKS, HydroContract.TILE_CORE_CELLS,
                HydroContract.TILE_HALO_CELLS, HydroContract.FILL_EPS, HydroContract.LEVEL_EPS);
        System.out.printf("窗口 = %d 格 = %d 块；tile 步进 = %d 块%n",
                HydroTileKey.windowCells(),
                HydroTileKey.windowCells() * HydroContract.CELL_BLOCKS,
                HydroContract.TILE_CORE_CELLS * HydroContract.CELL_BLOCKS);

        int totalCore = 0, totalBasin = 0, totalFlow = 0;
        int[] histAll = new int[8];
        int noDownAll = 0;
        int bestRun = 0;
        String bestRunInfo = "";

        for (int a = 0; a < nd; a++) {
            for (int b = 0; b < nd; b++) {
                int tx = tx0 + a, tz = tz0 + b;
                HydroTileKey key = HydroTileKey.of(seed, tx, tz, 0);
                HydroTileResult res = solver.resolve(key);
                HydroTileTopology topo = res.topo;
                HydroTileField f = topo.field;
                int w = f.w, halo = cfg.haloCells(), core = cfg.coreCells();
                double eps = HydroContract.FILL_EPS;

                int nCore = 0, nBasin = 0, nFlow = 0, noDown = 0;
                int[] hist = new int[8];
                // 最长同向游程（沿 down 链，core 内）
                int tileBest = 0;
                for (int lx = halo; lx < halo + core; lx++) {
                    for (int lz = halo; lz < halo + core; lz++) {
                        int idx = lx * w + lz;
                        if (!topo.inCore(idx)) continue;
                        nCore++;
                        if (topo.basinId[idx] >= 0) nBasin++;
                        int d = topo.downDir[idx];
                        if (d < 0) { noDown++; continue; }
                        nFlow++;
                        hist[d]++;
                        // 沿链数同向游程
                        int run = 1, cur = idx, dir = d, guard = 0;
                        while (guard++ < 400) {
                            int nxt = topo.down[cur];
                            if (nxt < 0 || topo.downDir[nxt] != dir) break;
                            if (!topo.inCore(nxt)) break;
                            run++;
                            cur = nxt;
                        }
                        if (run > tileBest) tileBest = run;
                    }
                }
                double fillRaised = 0;
                // fill−h 的分位（只看 core）
                double[] dd = new double[Math.max(1, nCore)];
                int di = 0;
                for (int lx = halo; lx < halo + core; lx++) {
                    for (int lz = halo; lz < halo + core; lz++) {
                        int idx = lx * w + lz;
                        if (!topo.inCore(idx)) continue;
                        dd[di++] = (topo.fill[idx] - f.height[idx]) / eps;
                    }
                }
                java.util.Arrays.sort(dd);
                if (di > 0) fillRaised = dd[di / 2];

                StringBuilder hb = new StringBuilder();
                for (int d = 0; d < 8; d++) {
                    if (hist[d] > 0) hb.append(String.format("%s=%.0f%% ",
                            HydroContract.DIR_NAME[d], 100.0 * hist[d] / Math.max(1, nFlow)));
                }
                System.out.printf("%n--- tile(%d,%d) core=%d 盆地格=%d(%.1f%%) 有流向=%d down=-1=%d%n",
                        tx, tz, nCore, nBasin, 100.0 * nBasin / Math.max(1, nCore), nFlow, noDown);
                System.out.printf("    fill−h 中位 = %.1f ε（>1 = 被填起 ⇒ 属盆地）%n", fillRaised);
                System.out.printf("    流向：%s%n", hb);
                System.out.printf("    ★ 最长同向游程 = %d 格（= %d 块）%n",
                        tileBest, tileBest * HydroContract.CELL_BLOCKS);
                if (tileBest > bestRun) {
                    bestRun = tileBest;
                    bestRunInfo = String.format("tile(%d,%d)", tx, tz);
                }

                totalCore += nCore; totalBasin += nBasin; totalFlow += nFlow; noDownAll += noDown;
                for (int d = 0; d < 8; d++) histAll[d] += hist[d];
            }
        }

        System.out.printf("%n======== 汇总 ========%n");
        System.out.printf("core 格 %d · 盆地格 %d (%.1f%%) · 有流向 %d · down=-1 %d%n",
                totalCore, totalBasin, 100.0 * totalBasin / Math.max(1, totalCore),
                totalFlow, noDownAll);
        StringBuilder hb = new StringBuilder();
        for (int d = 0; d < 8; d++) {
            if (histAll[d] > 0) hb.append(String.format("%s=%.1f%% ",
                    HydroContract.DIR_NAME[d], 100.0 * histAll[d] / Math.max(1, totalFlow)));
        }
        System.out.printf("总流向：%s%n", hb);
        int mx = 0;
        for (int v : histAll) mx = Math.max(mx, v);
        System.out.printf("最大方向占比 = %.1f%%（均匀 12.5%%）%n",
                100.0 * mx / Math.max(1, totalFlow));
        System.out.printf("★ 全局最长同向游程 = %d 格（%d 块）@ %s%n",
                bestRun, bestRun * HydroContract.CELL_BLOCKS, bestRunInfo);
        System.out.printf("   判读：若游程 ≫ 10 格 ⇒ D8 链本身就笔直 ⇒ 中心线必然笔直%n");
    }
}
