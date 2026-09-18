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
        boolean[] river = new boolean[n];   // ★ 河/湖命中列（供 [5] 前置测量）
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
                river[i] = true;
                riverCells++;
            }
        }
        System.out.printf("水格：河/湖 %d + 海 %d = %d / %d（%.1f%%）%n",
                riverCells, oceanCells, riverCells + oceanCells, n,
                100.0 * (riverCells + oceanCells) / n);

        // ===== 3) 统一解算（数据源 = priority-flood 溢流高程 filledAt）=====
        long t0 = System.nanoTime();
        double[] level = WaterLevelSolver.solve(field, idx -> gnd[idx], idx -> water[idx], seaLevel);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        // ===== 3b) ★ T1.8：数据源 = 闭式水位场 W =====
        WaterField wf = new WaterField(gen::terrainEQuick, gen.heightCurve(),
                WaterField.DEFAULT_SMOOTH_WU, WaterField.DEFAULT_OFFSET);
        double[] levelW = WaterLevelSolver.solve(idx -> water[idx],
                idx -> wf.waterYAt(field.cellCenterX(idx), field.cellCenterZ(idx)), n, seaLevel);
        System.out.printf("闭式水位场 W：smoothWu=%.0f wu  offset=%.1f 块%n",
                wf.smoothWu(), wf.offset());

        // ===== 4) 账单 =====
        int monoViol = 0, monoViolW = 0;
        int perchNew = 0, perchOld = 0, perchW = 0;
        int diffCells = 0, diffWCells = 0;
        // ★ 2026-09-19 修正：④ 的分母必须是【参与比较的格数】（河+海，见下方计数），
        //   旧代码用 riverCells(88) 作分母，而计数含海洋格(1923) ⇒ 实测打出 "90/88" 这种坏数。
        int cmpCells = 0;
        double maxPerchNew = 0, maxPerchOld = 0, maxPerchW = 0, sumPerchW = 0;
        double maxDepthNew = 0, maxDepthOld = 0, maxDepthW = 0;
        double sumAbsDiff = 0, sumAbsDiffW = 0;
        for (int i = 0; i < n; i++) {
            if (!water[i]) continue;
            // 短板：水位 ≤ 紧邻最低旱地（三种口径同测）
            double rimMin = rimMin(field, water, gnd, i);
            if (rimMin != Double.MAX_VALUE) {
                double v = level[i] - rimMin;
                if (v > 0.5) perchNew++;
                maxPerchNew = Math.max(maxPerchNew, v);

                double vw = levelW[i] - rimMin;
                if (vw > 0.5) perchW++;
                maxPerchW = Math.max(maxPerchW, vw);
                sumPerchW += vw;

                if (!Double.isNaN(oldLevel[i])) {
                    double vo = oldLevel[i] - rimMin;
                    if (vo > 0.5) perchOld++;
                    maxPerchOld = Math.max(maxPerchOld, vo);
                }
            }
            maxDepthNew = Math.max(maxDepthNew, level[i] - gnd[i]);
            maxDepthW = Math.max(maxDepthW, levelW[i] - gnd[i]);
            if (!Double.isNaN(oldLevel[i])) {
                cmpCells++;
                maxDepthOld = Math.max(maxDepthOld, oldLevel[i] - gnd[i]);
                double d = Math.abs(level[i] - oldLevel[i]);
                sumAbsDiff += d;
                if (d > 0.5) diffCells++;
                double dw = Math.abs(levelW[i] - oldLevel[i]);
                sumAbsDiffW += dw;
                if (dw > 0.5) diffWCells++;
            }
            int dn = field.flowTo(i);
            if (dn >= 0 && dn < n && water[dn]) {
                if (level[i] < level[dn] - 1e-6) monoViol++;
                if (levelW[i] < levelW[dn] - 1e-6) monoViolW++;
            }
        }

        System.out.println();
        System.out.printf("[账单] 统一解算 %d ms（%d 格）%n", ms, n);
        System.out.printf("  ① 单调违反边：filledAt 源 = %d；W 源 = %d（应 0）%n", monoViol, monoViolW);
        System.out.printf("  ② 短板违反格（水位 − 紧邻最低旱地 > 0.5 块）：%n");
        System.out.printf("       河线包络（旧生产）  = %4d   最坏 %+.3f 块%n", perchOld, maxPerchOld);
        System.out.printf("       filledAt（统一解算）= %4d   最坏 %+.3f 块%n", perchNew, maxPerchNew);
        System.out.printf("       W（闭式，T1.8）     = %4d   最坏 %+.3f 块   平均 %+.3f%n",
                perchW, maxPerchW, sumPerchW / Math.max(1, riverCells));
        System.out.printf("  ③ 最深水深（水位 − 地面）：河线包络 %.3f；filledAt %.3f；W %.3f 块%n",
                maxDepthOld, maxDepthNew, maxDepthW);
        System.out.printf("  ④ 与旧生产的差异（分母 = 可比较水格 %d = 河/湖 %d + 海 %d）：%n",
                cmpCells, riverCells, oceanCells);
        System.out.printf("       filledAt：差 %d/%d 格（%.1f%%），平均 %.3f 块%n",
                diffCells, cmpCells, 100.0 * diffCells / Math.max(1, cmpCells),
                cmpCells == 0 ? 0 : sumAbsDiff / cmpCells);
        System.out.printf("       W       ：差 %d/%d 格（%.1f%%），平均 %.3f 块%n",
                diffWCells, cmpCells, 100.0 * diffWCells / Math.max(1, cmpCells),
                cmpCells == 0 ? 0 : sumAbsDiffW / cmpCells);
        // ===== 5) ★ T3 前置测量：河线水面(surfaceY) vs 闭式 W 的逐点差 =====
        int dr = 0;
        double dSum = 0, dMin = Double.MAX_VALUE, dMax = -Double.MAX_VALUE;
        int[] buckets = new int[4];                       // |差| <1 / <3 / <10 / ≥10
        for (int i = 0; i < n; i++) {
            if (!river[i]) continue;
            double d = oldLevel[i] - levelW[i];
            dr++;
            dSum += d;
            dMin = Math.min(dMin, d);
            dMax = Math.max(dMax, d);
            double ad = Math.abs(d);
            if (ad < 1) buckets[0]++;
            else if (ad < 3) buckets[1]++;
            else if (ad < 10) buckets[2]++;
            else buckets[3]++;
        }
        double dMean = dr == 0 ? 0 : dSum / dr;
        double dSd = 0;
        for (int i = 0; i < n; i++) {
            if (!river[i]) continue;
            double e = oldLevel[i] - levelW[i] - dMean;
            dSd += e * e;
        }
        dSd = Math.sqrt(dr == 0 ? 0 : dSd / dr);
        System.out.println();
        System.out.printf("[5] ★ T3 前置：河线水面 − W 的逐点差（河/湖列 %d 个）%n", dr);
        if (dr > 0) {
            System.out.printf("    平均 %+.3f 块   标准差 %.3f   最小 %+.3f   最大 %+.3f%n",
                    dMean, dSd, dMin, dMax);
            System.out.printf("    |差|<1 块: %d（%.0f%%）  1~3: %d  3~10: %d  ≥10: %d%n",
                    buckets[0], 100.0 * buckets[0] / dr, buckets[1], buckets[2], buckets[3]);
            // 有符号分解：>0 = 河面高于 W（潜在悬空，W 作上界可治）；<0 = 河面低于 W（正常）
            int above = 0, below = 0;
            double maxExcess = 0, maxDeficit = 0;
            for (int i = 0; i < n; i++) {
                if (!river[i]) continue;
                double d = oldLevel[i] - levelW[i];
                if (d > 1.0) {
                    above++;
                    maxExcess = Math.max(maxExcess, d);
                } else if (d < -1.0) {
                    below++;
                    maxDeficit = Math.max(maxDeficit, -d);
                }
            }
            System.out.printf("    河面【高于】W+1 的列 = %d / %d（%.0f%%，最坏 +%.3f）"
                            + " ⇒ W 作上界约束可治这些%n",
                    above, dr, 100.0 * above / Math.max(1, dr), maxExcess);
            System.out.printf("    河面【低于】W−1 的列 = %d / %d（%.0f%%，最坏 −%.3f）"
                            + " ⇒ 正常（河在谷里）%n",
                    below, dr, 100.0 * below / Math.max(1, dr), maxDeficit);
        }
        System.out.println("    判读：标准差小 ⇒ 水面与 W 已自洽（锚定是小修正，T3 可低风险做）；");
        System.out.println("          标准差大 ⇒ 锚定会改动现有形态，必须先弄清差异来源再动。");
        // ===== 6) ★ 洼地掩膜候选：depthBelowSmooth > 0 vs 现有湖域 =====
        testLakeMask(net, wf, rx, rz, regionSize);

        System.out.println();
        System.out.println("  对照（旧系统独立审计）：河悬空 58% ≥5 块 / 最坏 36.6 块；湖短板最坏 +26.076 块");
        System.out.println("  ★ 判读（按实测，勿写死）：短板栏越小越好。"
                + "2026-09-19 实测 filledAt 源并未优于旧生产（与『前一版规则』同数），"
                + "故不要预设『新优于旧』。");
    }

    /**
     * [6] ★ 洼地掩膜候选（{@code depthBelowSmooth > 0}）与【现有湖域】的重合度。
     *
     * <h4>它要回答的问题</h4>
     * <p>{@code W} 相对河面偏高（平均 +5.4 块）⇒ 它不能当河道水面；
     * 但它也许能当【跨区一致的洼地掩膜】（解决"湖没到边缘就结束"）。
     * 若它与现有湖域（{@code inDomain} / {@code inFlood}）<b>重合很差</b>，
     * 则该方向<b>不成立</b>，应彻底放弃。</p>
     *
     * <h4>为何必须用湖自己的口径</h4>
     * <p>现有湖系统比表面成熟：逐格洼地轮廓（2026-09-09 B1 取代圆盘命中）+
     * 侵蚀后 rim 短板（{@code erodedWaterLevel}）+ 侵蚀后连通域（{@code inFlood}）
     * + 块级等高线精修。⇒ 用它的口径量，才不是在重造轮子。</p>
     */
    private static void testLakeMask(RiverLineNetwork net, WaterField wf,
                                     int rx, int rz, double regionSize) {
        final double step = 16.0;
        int m = (int) Math.round(regionSize / step) + 1;
        double loX = rx * regionSize, loZ = rz * regionSize;
        double tol = RiverLineParams.defaults().gridCell() * 2.0;

        long total = 0, maskCells = 0;
        long lakeHits = 0, lakeBoth = 0;
        long domHits = 0, domBoth = 0;
        long floodHits = 0, floodBoth = 0;

        for (int j = 0; j < m; j++) {
            for (int i = 0; i < m; i++) {
                double wx = loX + i * step, wz = loZ + j * step;
                total++;
                boolean inMask = wf.depthBelowSmooth(wx, wz) > 0.0;
                if (inMask) maskCells++;

                RiverLineNetwork.RiverLineHit hit = net.sample(wx, wz);
                if (hit == null) continue;
                if (hit.isLake()) {
                    lakeHits++;
                    if (inMask) lakeBoth++;
                }
                if (hit.lake() != null) {
                    if (hit.lake().inDomain(wx, wz, tol)) {
                        domHits++;
                        if (inMask) domBoth++;
                    }
                    if (hit.lake().inFlood(wx, wz)) {
                        floodHits++;
                        if (inMask) floodBoth++;
                    }
                }
            }
        }

        System.out.println();
        System.out.printf("[6] 洼地掩膜候选 vs 现有湖域（%d 点 @ %.0f wu）%n", total, step);
        System.out.printf("    掩膜 depthBelowSmooth>0 命中 = %d（%.1f%%）%n",
                maskCells, 100.0 * maskCells / Math.max(1, total));
        System.out.printf("    现有 isLake  命中 = %4d   其中也在掩膜内 = %4d（%.0f%%）%n",
                lakeHits, lakeBoth, 100.0 * lakeBoth / Math.max(1, lakeHits));
        System.out.printf("    现有 inDomain 命中 = %4d   其中也在掩膜内 = %4d（%.0f%%）%n",
                domHits, domBoth, 100.0 * domBoth / Math.max(1, domHits));
        System.out.printf("    现有 inFlood 命中  = %4d   其中也在掩膜内 = %4d（%.0f%%）%n",
                floodHits, floodBoth, 100.0 * floodBoth / Math.max(1, floodHits));
        System.out.printf("    反向：掩膜内落在湖域的比例 = %.1f%%（%d/%d）%n",
                100.0 * domBoth / Math.max(1, maskCells), domBoth, maskCells);
        System.out.println("    判读：若湖域命中【几乎不落在】掩膜内 ⇒ W 当不了洼地掩膜，"
                + "该方向放弃；若重合高 ⇒ 可用它做跨区一致的掩膜，锦上添花。");
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
