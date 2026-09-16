package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 统一水图【差异账单】探针（2026-09-17，水文统一化 M1）。
 *
 * <h2>任务</h2>
 * <p>用 {@link WaterLevelSolver} 在 region 网格上算一遍统一水位，**与"旧水位"对照**，
 * 输出可判断的账单。本探针<b>不接管产出</b>（M1 阶段）：只量化"统一后会变成什么样"。</p>
 *
 * <h2>三条硬不变式（统一设计 §2.3）与判据</h2>
 * <ol>
 *   <li><b>单调</b>：沿 {@code flowTo} 下游，水位单调不减
 *       ⇒ 判据：违反边数 = 0（本解算器由构造保证）；</li>
 *   <li><b>短板</b>：水位不得超过"该点紧邻最低旱地"
 *       ⇒ 判据：违反格数 / 最坏违反量（<b>旧基线：3 个水体 / 最坏 +26.076 块</b>）；</li>
 *   <li><b>水深合理</b>：水格 {@code level − ground} 不得异常深
 *       ⇒ 判据：>20 块的格数（<b>旧基线：河中 20 块水深</b>、水体最深处 20 块）。</li>
 * </ol>
 *
 * <pre>{@code gradlew runWaterGraphProbe [-PprobeArgs="seed regionX regionZ"]}</pre>
 */
public final class WaterGraphProbe {

    /** 海平面以下的格视为海（图的根）。 */
    private static final double NO_MARGIN = 0.0;

    private WaterGraphProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 5436529513624899584L;
        int rx = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int rz = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        // ★ 第 4 参数：拓扑采样场 —— "quick"（terrainEQuick，生产现状）或 "real"（sampleWu）。
        //   根因假设：D8 拓扑若建在【虚构场】上，而水位/地面上用【真实地形】，
        //   则"下游"在真实地形上可能更高 ⇒ max(自身地面,下游水位) 把水位抬高。
        //   本参数用于验证"拓扑与地形必须同源"。
        boolean useReal = args.length > 3 && "real".equalsIgnoreCase(args[3]);

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

        System.out.printf("=== WaterGraphProbe seed=%d region(%d,%d) ===%n", seed, rx, rz);
        System.out.printf("网格 %.0f..%.0f × %.0f..%.0f，格距 %.0f wu，海平面 %.1f%n",
                minX, maxX, minZ, maxZ, cell, seaLevel);

        // ===== 1) 建汇流场 + 填洼层 =====
        //   生产现状：选线/D8 用 terrainEQuick（虚构场，压低山地）；填洼用真实地形。
        //   本探针可用 "real" 让 D8 也建在真实地形上（验证"拓扑与地形同源"）。
        System.out.printf("拓扑采样场 = %s%n", useReal ? "sampleWu（真实地形）" : "terrainEQuick（生产现状）");
        FlowField field = new FlowField(minX, minZ, maxX, maxZ, cell, useReal
                ? (wx, wz) -> gen.sampleWu(wx, wz).height
                : (wx, wz) -> gen.terrainEQuick(wx, wz));
        field.computeFill((wx, wz) -> gen.sampleWu(wx, wz).height, seaLevel);

        // ★ 地面高程只算一次（原实现每格多次 sampleWu ⇒ 实测 129 秒）
        int nCells = field.cols() * field.rows();
        double[] gnd = new double[nCells];
        long tg = System.nanoTime();
        for (int i = 0; i < nCells; i++) {
            gnd[i] = gen.sampleWu(field.cellCenterX(i), field.cellCenterZ(i)).height;
        }
        System.out.printf("地面高程预采样 %d 格：%d ms%n", nCells,
                (System.nanoTime() - tg) / 1_000_000);

        // ===== 2) 水格判定（海 ∪ 真有积水的洼地 ∪ 汇流达阈值的河）=====
        // ⚠ 修正（2026-09-17）：`isBasinCell` 是"填入深度 >0.05 块"⇒ 起伏地形上
        //   **绝大多数格都是局部低点**（实测 76% 被判为水，水面自然悬空 53 块）。
        //   生产里湖是从"洼地格【连通分量】"经多重过滤选出的；此处用**最小积水深度**
        //   近似（≥0.5 块，与落块侧 `height < spill-0.5` 同口径），排除浅坑。
        final double accumThr = rp.riverAccumThreshold();
        final double minLakeDepth = 0.5;
        WaterLevelSolver.WaterMask mask = idx -> {
            if (gnd[idx] <= seaLevel) return true;                       // 海
            if (field.basinDepthAt(idx) >= minLakeDepth) return true;    // 湖（确有积水）
            return field.accumAt(idx) >= accumThr;                       // 河
        };

        // ===== 3) 统一解算 =====
        long t0 = System.nanoTime();
        double[] level = WaterLevelSolver.solve(field, idx -> gnd[idx], mask, seaLevel);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        // ===== 4) 账单 =====
        int n = nCells;
        int waterCells = 0;
        int deepCells = 0, deeper20 = 0, perchViol = 0;
        double maxDepth = 0, maxPerch = 0;
        double px = 0, pz = 0, plv = 0, pg = 0;
        int monoViol = 0;
        for (int i = 0; i < n; i++) {
            if (!mask.isWater(i)) continue;
            waterCells++;
            double g = ground(gen, field, i);
            double d = level[i] - g;
            if (d > 5) deepCells++;
            if (d > 20) deeper20++;
            if (d > maxDepth) maxDepth = d;
            // 短板：水位 vs 紧邻最低旱地
            double rimMin = Double.MAX_VALUE;
            int ci = i % field.cols(), cj = i / field.cols();
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    int ii = ci + di, jj = cj + dj;
                    if (ii < 0 || ii >= field.cols() || jj < 0 || jj >= field.rows()) continue;
                    int j = jj * field.cols() + ii;
                    if (mask.isWater(j)) continue;
                    rimMin = Math.min(rimMin, ground(gen, field, j));
                }
            }
            if (rimMin != Double.MAX_VALUE) {
                double v = level[i] - rimMin;
                if (v > 0.5) {
                    perchViol++;
                    if (v > maxPerch) { maxPerch = v; px = field.cellCenterX(i); pz = field.cellCenterZ(i);
                        plv = level[i]; pg = rimMin; }
                }
            }
            // 单调：本格水位不得低于下游
            int dn = field.flowTo(i);
            if (dn >= 0 && dn < n && mask.isWater(dn) && level[i] < level[dn] - 1e-6) monoViol++;
        }

        System.out.println();
        System.out.printf("[账单] 解算耗时 %d ms（%d 格，%d 水格）%n", ms, n, waterCells);
        System.out.printf("  ① 单调违反边 = %d（应为 0；由构造保证）%n", monoViol);
        System.out.printf("  ② 短板违反格 = %d（>0.5 块）  最坏 = %+.3f 块 @ wu(%.0f,%.0f)（水位 %.3f / 旱地 %.3f）%n",
                perchViol, maxPerch, px, pz, plv, pg);
        System.out.printf("  ③ 水深 >5 块 = %d 格；>20 块 = %d 格；最深 = %.3f 块%n",
                deepCells, deeper20, maxDepth);
        System.out.println();
        System.out.println("  对照基线（旧系统，同种子实测）：");
        System.out.println("    · 河流悬空：58% 河格悬空 ≥5 块，最坏 36.6 块");
        System.out.println("    · 湖泊短板：3 个水体违反，最坏 +26.076 块");
        System.out.println("  判读：②③ 若显著小于基线 ⇒ 统一解算对两类缺陷都有效。");
    }

    /** 格 → 地面高程（最终地形口径 sampleWu；与落块同源）。 */
    private static double ground(CellGenerator gen, FlowField field, int idx) {
        return gen.sampleWu(field.cellCenterX(idx), field.cellCenterZ(idx)).height;
    }
}
