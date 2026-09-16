package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 统一水图【差异账单】探针（2026-09-17，水文统一化 M1）。
 *
 * <h2>口径修正史（务必保留，勿重犯）</h2>
 * <p>水格掩膜<b>不能从汇流场反推</b>：</p>
 * <ul>
 *   <li>用 {@code isBasinCell}（填入深度 &gt;0.05 块）⇒ 起伏地形上 76% 的格都是局部低点；</li>
 *   <li>改用 {@code basinDepthAt ≥ 0.5} + {@code accum ≥ riverAccumThreshold(2304)} ⇒ <b>仍 76%</b>
 *       （D8 网格里多数格确有 ≥4 个上游格）。</li>
 * </ul>
 * <p>⇒ 生产里河网稀疏靠的是<b>源点筛选</b>（{@code sourceMinE}/{@code sourceSpacingCells}/
 * {@code riverCount}/{@code minRiverNodes}），不是汇流阈值。
 * ⇒ 本探针改为<b>直接从真实河线取水格</b>（{@link RiverLineNetwork#sample} 的命中，
 * 口径 = {@code distToCenter ≤ width×1.5}，与 {@code RiverLineMultiSeedProbe} 一致）。</p>
 *
 * <h2>账单三判据</h2>
 * <ol>
 *   <li><b>单调</b>：沿 flowTo 下游水位不增 ⇒ 违反边应为 0；</li>
 *   <li><b>短板</b>：水位 ≤ 紧邻最低旱地（旧基线：3 个水体 / 最坏 +26.076 块）；</li>
 *   <li><b>水深</b>：{@code level − ground} 的分布（旧基线：最深 20~53 块）。</li>
 * </ol>
 *
 * <pre>{@code gradlew runWaterGraphProbe [-PprobeArgs="seed regionX regionZ"]}</pre>
 */
public final class WaterGraphProbe {

    private WaterGraphProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int rx = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int rz = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        RiverLineParams rp = RiverLineParams.defaults();
        double regionSize = rp.regionSize();
        double cell = rp.gridCell();
        double margin = regionSize * 0.5;
        double minX = rx * regionSize - margin, maxX = rx * regionSize + regionSize + margin;
        double minZ = rz * regionSize - margin, maxZ = rz * regionSize + regionSize + margin;
        double seaLevel = gen.heightCurve().seaLevelY();
        double hs = tp.horizontalScale();

        System.out.printf("=== WaterGraphProbe seed=%d region(%d,%d) ===%n", seed, rx, rz);
        System.out.printf("网格 %.0f..%.0f × %.0f..%.0f，格距 %.0f wu，海平面 %.1f%n",
                minX, maxX, minZ, maxZ, cell, seaLevel);

        // ★ 地面口径开关（第 4 参数 "quick" / "real"，默认 quick）：
        //   quick = terrainEQuick（廉价、**不触发侵蚀 tile 生成**）；
        //   real  = sampleWu（最终地形口径，但会为整片 region 触发侵蚀 tile 冷生成，
        //           实测数分钟 —— 生产环境 tile 已缓存，故只影响探针）。
        //   ⚠ quick 下【绝对值不可与生产比对】，仅用于验证解算器的**结构与相对改善**。
        boolean quick = args.length <= 3 || !"real".equalsIgnoreCase(args[3]);
        final float[] groundCall = new float[1];
        // ⚠ terrainEQuick 返回【归一化 e（0~1）】而非块高程！必须经 heightFromE 换算
        //   （与 RiverLineNetwork.groundYAt 同口径）。曾直接当高程用 ⇒ 全部 ≤ 海平面
        //   ⇒ 实测"水格 100% 是海"。
        java.util.function.BiFunction<Double, Double, Double> groundFn = quick
                ? (wx, wz) -> gen.heightCurve().heightFromE(gen.terrainEQuick(wx, wz))
                : (wx, wz) -> gen.sampleWu(wx, wz).height;
        System.out.printf("地面口径 = %s%n", quick ? "terrainEQuick（廉价，仅验结构）" : "sampleWu（最终口径，慢）");

        // ===== 0) 河线网络（选线/填洼/Hit 均用同一 groundFn，保证自洽）=====
        System.out.println("构建 RiverLineNetwork…");
        long tn = System.nanoTime();
        RiverLineNetwork net = new RiverLineNetwork(
                (wx, wz) -> gen.terrainEQuick(wx, wz),
                (wx, wz) -> groundFn.apply(wx, wz),
                gen.heightCurve(), seed, hs, rp);
        net.region(rx, rz);
        System.out.printf("  河网就绪：%d ms，缓存 region=%d%n",
                (System.nanoTime() - tn) / 1_000_000, net.cachedRegions());

        // ===== 1) 汇流场 + 填洼层 =====
        FlowField field = new FlowField(minX, minZ, maxX, maxZ, cell,
                (wx, wz) -> gen.terrainEQuick(wx, wz));
        field.computeFill((wx, wz) -> groundFn.apply(wx, wz), seaLevel);

        // ===== 2) 逐格：地面 / 旧水位（河线命中）/ 水格判定 =====
        final int nx = field.cols(), nz = field.rows();
        final int n = nx * nz;
        double[] gnd = new double[n];
        double[] oldLevel = new double[n];
        boolean[] water = new boolean[n];
        int riverCells = 0, oceanCells = 0;
        for (int i = 0; i < n; i++) {
            double wx = field.cellCenterX(i), wz = field.cellCenterZ(i);
            gnd[i] = groundFn.apply(wx, wz);
            oldLevel[i] = Double.NaN;
            if (gnd[i] <= seaLevel) {                       // 海
                water[i] = true;
                oldLevel[i] = seaLevel;
                oceanCells++;
                continue;
            }
            RiverLineNetwork.RiverLineHit hit = net.sample(wx, wz);
            if (hit != null && hit.distToCenter() <= Math.max(1.0, hit.width() * 1.5)) {
                water[i] = true;                            // 河/湖（命中河道内）
                oldLevel[i] = hit.surfaceY();
                riverCells++;
            }
        }
        System.out.printf("水格：河/湖 %d + 海 %d = %d / %d（%.1f%%）%n",
                riverCells, oceanCells, riverCells + oceanCells, n,
                100.0 * (riverCells + oceanCells) / n);

        // ===== 3) 统一解算 =====
        long t0 = System.nanoTime();
        double[] level = WaterLevelSolver.solve(field, idx -> gnd[idx], idx -> water[idx], seaLevel);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        // ===== 4) 账单 =====
        int monoViol = 0, perchNew = 0, perchOld = 0, diffCells = 0;
        double maxPerchNew = 0, maxPerchOld = 0, maxDepthNew = 0, maxDepthOld = 0;
        double sumAbsDiff = 0;
        for (int i = 0; i < n; i++) {
            if (!water[i]) continue;
            // 短板（新）
            double rimMin = rimMin(field, water, gnd, i);
            if (rimMin != Double.MAX_VALUE) {
                double v = level[i] - rimMin;
                if (v > 0.5) perchNew++;
                maxPerchNew = Math.max(maxPerchNew, v);
                if (!Double.isNaN(oldLevel[i])) {
                    double vo = oldLevel[i] - rimMin;
                    if (vo > 0.5) perchOld++;
                    maxPerchOld = Math.max(maxPerchOld, vo);
                }
            }
            maxDepthNew = Math.max(maxDepthNew, level[i] - gnd[i]);
            if (!Double.isNaN(oldLevel[i])) {
                maxDepthOld = Math.max(maxDepthOld, oldLevel[i] - gnd[i]);
                double d = Math.abs(level[i] - oldLevel[i]);
                sumAbsDiff += d;
                if (d > 0.5) diffCells++;
            }
            int dn = field.flowTo(i);
            if (dn >= 0 && dn < n && water[dn] && level[i] < level[dn] - 1e-6) monoViol++;
        }

        System.out.println();
        System.out.printf("[账单] 统一解算 %d ms（%d 格）%n", ms, n);
        System.out.printf("  ① 单调违反边 = %d（应 0）%n", monoViol);
        System.out.printf("  ② 短板违反格：新 = %d（最坏 %+.3f 块）；旧 = %d（最坏 %+.3f 块）%n",
                perchNew, maxPerchNew, perchOld, maxPerchOld);
        System.out.printf("  ③ 最深水深：新 = %.3f 块；旧 = %.3f 块%n", maxDepthNew, maxDepthOld);
        System.out.printf("  ④ 新旧水位不同的格 = %d / %d；平均差 %.3f 块%n",
                diffCells, riverCells, riverCells == 0 ? 0 : sumAbsDiff / riverCells);
        System.out.println();
        System.out.println("  对照（旧系统独立审计）：河悬空 58% ≥5 块 / 最坏 36.6 块；湖短板最坏 +26.076 块");
        System.out.println("  判读：②③ 的『新』显著小于『旧』⇒ 统一解算对两类缺陷有效。");
    }

    /** 紧邻最低旱地（格）高度。 */
    private static double rimMin(FlowField f, boolean[] water, double[] gnd, int i) {
        int ci = i % f.cols(), cj = i / f.cols();
        double best = Double.MAX_VALUE;
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                int ii = ci + di, jj = cj + dj;
                if (ii < 0 || ii >= f.cols() || jj < 0 || jj >= f.rows()) continue;
                int j = jj * f.cols() + ii;
                if (water[j]) continue;
                best = Math.min(best, gnd[j]);
            }
        }
        return best;
    }
}
