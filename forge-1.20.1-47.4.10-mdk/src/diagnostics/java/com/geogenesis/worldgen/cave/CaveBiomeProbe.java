package com.geogenesis.worldgen.cave;

import com.geogenesis.worldgen.climate.WhittakerType;

/**
 * 洞穴群系诊断探针（配套 {@link CaveBiomeSelector}）。
 *
 * <h3>测什么（纯函数部分）</h3>
 * <ol>
 *   <li><b>群系能出现</b>：洞穴内必须真的产出 DRIPSTONE/LUSH 群系（不能恒为 NONE）；</li>
 *   <li><b>深度带</b>：紧贴地表（&lt; {@code MIN_DEPTH}）的洞穴<b>必须</b>返回 NONE
 *       —— 否则会出现"洞穴植被长到地表"；</li>
 *   <li><b>气候耦合</b>：受控对照 —— 同一点只换 {@link WhittakerType}。
 *       成林气候（各类森林）应出 LUSH，干旱/极寒应出 DRIPSTONE，且
 *       <b>干旱气候不得出 LUSH</b>（这是"繁茂洞穴需要水"的直接检验）；</li>
 *   <li><b>与洞穴几何一致</b>：返回非 NONE 的位置<b>必须</b>确实是洞穴
 *       （否则"群系漂到岩石里"）；反向：洞穴内应有高比例被赋群系；</li>
 *   <li><b>性能</b>：洞穴群系裁定在 {@code getNoiseBiome} 热路径上
 *       （BIOMES 阶段被<b>极频繁</b>调用）⇒ 必须量化每调用成本。</li>
 * </ol>
 *
 * <h3>数据来源：合成网格</h3>
 * <p>洞穴噪声特征尺度上百块、而真实 chunk 窗口只有数十块（不足一个特征）
 * ⇒ 统计会剧烈波动（本项目在洞穴密度/矿脉成矿带上踩过<b>两次</b>同型的坑）。
 * 故用大范围合成扫描：固定地表高度 + 指定气候/岩性。</p>
 *
 * <pre>{@code gradlew runCaveBiomeProbe [-PprobeArgs="seed spanXY"]}</pre>
 */
public final class CaveBiomeProbe {

    private CaveBiomeProbe() { }

    /** 世界最低 Y（与 {@code GeoGenesisGenerator.WORLD_MIN_Y} 一致）。 */
    private static final int WORLD_MIN_Y = -64;
    /** 合成扫描固定地表高度。 */
    private static final int SYN_SURFACE = 120;
    /** 中性岩性系数（不放大也不缩小洞穴，隔离"气候决策"这一变量）。 */
    private static final double NEUTRAL_LITHO = 1.0;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int N = args.length > 1 ? Integer.parseInt(args[1]) : 160;

        System.out.printf("=== CaveBiomeProbe seed=%d spanXY=%d surface=%d ===%n",
                seed, N, SYN_SURFACE);

        CaveShape.setSeed(seed);
        // ★ 必须播种洞穴群系的"交错"噪声 —— 否则 isSeeded()=false 会走退化分支
        //   （成林气候 100% 繁茂），统计看起来正常却测不到真实行为。
        CaveBiomeSelector.setSeed(seed);
        System.out.printf("[0] 播种: 洞穴几何=%s 群系交错=%s%n",
                CaveShape.isSeeded() ? "OK" : "FAIL",
                CaveBiomeSelector.isSeeded() ? "OK" : "FAIL");

        // ---------- 1) 分布统计（三种气候并行扫描）----------
        //   气候按 x 分三段轮换 ⇒ 一次扫描同时覆盖"干旱/成林/极寒"三类对照。
        WhittakerType[] climates = {WhittakerType.DESERT, WhittakerType.TEMPERATE_FOREST,
                WhittakerType.ICE};
        long[] none = new long[climates.length];
        long[] drip = new long[climates.length];
        long[] lush = new long[climates.length];
        long caveVoxels = 0, biomeAssigned = 0;
        // 深度带统计（洞穴体素距地表的深度 → 是否被赋群系）
        int minAssignedDepth = Integer.MAX_VALUE;
        int maxAssignedDepth = Integer.MIN_VALUE;

        // ★ 2026-09-16：「启用率 vs 深度」分档（验证过渡带是【渐变】而非【阶跃】）。
        //   分母必须是"该深度的【洞穴】体素数" —— 用"被赋群系数 / 总洞穴数"，
        //   而不是 / 总体素数（那样会被"各深度洞穴多少"的地形因素污染）。
        final int DEPTH_BINS = 64;
        long[] caveAtDepth = new long[DEPTH_BINS];
        long[] assignedAtDepth = new long[DEPTH_BINS];

        long t0 = System.nanoTime();
        for (int wx = 0; wx < N; wx++) {
            for (int wz = 0; wz < N; wz++) {
                int ci = (wx * 3 / N) % climates.length;
                WhittakerType ct = climates[ci];
                for (int wy = WORLD_MIN_Y + 1; wy < SYN_SURFACE; wy++) {
                    CaveBiomeSelector.CaveBiome cb = CaveBiomeSelector.select(
                            wx, wy, wz, SYN_SURFACE, WORLD_MIN_Y, NEUTRAL_LITHO, ct);
                    int d = SYN_SURFACE - wy;
                    if (cb == CaveBiomeSelector.CaveBiome.NONE) {
                        none[ci]++;
                        // ★ 补算分母：该体素是否【在洞穴里】。
                        //   只在 NONE 分支补算 ⇒ 对"已赋群系"的体素不重复求值。
                        if (d < DEPTH_BINS
                                && CaveShape.components(wx, wy, wz, SYN_SURFACE,
                                        WORLD_MIN_Y, NEUTRAL_LITHO) != 0) {
                            caveAtDepth[d]++;
                        }
                        continue;
                    }
                    caveVoxels++;
                    biomeAssigned++;
                    if (d < DEPTH_BINS) {
                        caveAtDepth[d]++;
                        assignedAtDepth[d]++;
                    }
                    if (cb == CaveBiomeSelector.CaveBiome.DRIPSTONE) drip[ci]++;
                    else lush[ci]++;
                    minAssignedDepth = Math.min(minAssignedDepth, d);
                    maxAssignedDepth = Math.max(maxAssignedDepth, d);
                }
            }
        }
        long el = System.nanoTime() - t0;
        long total = (long) N * N * (SYN_SURFACE - WORLD_MIN_Y - 1);
        System.out.printf("[1] 扫描: 体素=%d  被赋洞穴群系=%d  (%.1f%%)%n",
                total, biomeAssigned, 100.0 * biomeAssigned / total);
        System.out.printf("    耗时=%dms  单次调用=%.3f us%n",
                el / 1_000_000, el / 1000.0 / total);

        // ---------- 判据1：洞穴群系确实产生 ----------
        boolean pass1 = biomeAssigned > 0;
        System.out.printf("[判据1] 洞穴内确实产出洞穴群系（非恒 NONE）: %s（赋=%d）%n",
                pass1 ? "PASS" : "FAIL", biomeAssigned);

        // ---------- 判据2：深度带（不破地表）----------
        boolean pass2 = minAssignedDepth >= CaveBiomeSelector.MIN_DEPTH;
        System.out.printf("[判据2] 赋群系的最小深度 ≥ MIN_DEPTH(%d)（不破地表）: %s"
                        + "（实测最小深度=%d，最大=%d）%n",
                CaveBiomeSelector.MIN_DEPTH, pass2 ? "PASS" : "FAIL",
                minAssignedDepth == Integer.MAX_VALUE ? -1 : minAssignedDepth,
                maxAssignedDepth == Integer.MIN_VALUE ? -1 : maxAssignedDepth);

        // ---------- 判据3：气候耦合（受控对照）----------
        System.out.println("[3] 气候耦合（受控：同点只换 WhittakerType）:");
        boolean pass3 = true;
        for (int i = 0; i < climates.length; i++) {
            long tot = drip[i] + lush[i];
            double lushPct = tot == 0 ? 0 : 100.0 * lush[i] / tot;
            System.out.printf("    %-20s 滴水石=%d 繁茂=%d  繁茂占比=%.1f%%%n",
                    climates[i], drip[i], lush[i], lushPct);
        }
        // 干旱与极寒【不得】出 LUSH；成林【必须交错】（两种都出现，繁茂占少数）
        int dryIdx = 0, forestIdx = 1, iceIdx = 2;
        boolean dryOk = lush[dryIdx] == 0;
        boolean iceOk = lush[iceIdx] == 0;
        long fTot = drip[forestIdx] + lush[forestIdx];
        double fLushPct = fTot == 0 ? 0 : 100.0 * lush[forestIdx] / fTot;
        // ★ 交错判据（首版是硬二值 ⇒ 成林 100% 繁茂，已修）：
        //   成林气候须两种都出现，且繁茂为【少数】（预期约 30%，宽容取 5%~60%）。
        //   若变成 0% 或 100% 都说明交错失效（退回硬切换）。
        boolean forestMixed = drip[forestIdx] > 0 && lush[forestIdx] > 0
                && fLushPct >= 5.0 && fLushPct <= 60.0;
        System.out.printf("    干旱(DESERT) 不得出繁茂: %s（实测 %d）%n",
                dryOk ? "OK" : "★FAIL", lush[dryIdx]);
        System.out.printf("    极寒(ICE) 不得出繁茂: %s（实测 %d）%n",
                iceOk ? "OK" : "★FAIL", lush[iceIdx]);
        System.out.printf("    成林(TEMP_FOREST) 须【交错】: %s"
                        + "（滴水石=%d 繁茂=%d，繁茂占比 %.1f%%，预期 5~60%%）%n",
                forestMixed ? "OK" : "★FAIL", drip[forestIdx], lush[forestIdx], fLushPct);
        pass3 = dryOk && iceOk && forestMixed;
        System.out.printf("[判据3] 气候耦合 + 交错生效（繁茂只在成林且为少数）: %s%n",
                pass3 ? "PASS" : "FAIL");

        // ---------- 判据4：与洞穴几何一致 ----------
        //   反向检查：随机采样点，凡 select 返回非 NONE 的，其 CaveShape.components 必须非 0。
        //   （正向"洞穴内有多少被赋群系"不必 100%：太浅的不赋，是有意为之。）
        long inconsistent = 0, sampled = 0;
        for (int i = 0; i < 20000; i++) {
            int wx = (i * 37) % N, wz = (i * 53) % N;
            int wy = WORLD_MIN_Y + 1 + ((i * 29) % (SYN_SURFACE - WORLD_MIN_Y - 2));
            WhittakerType ct = climates[(i % 3)];
            sampled++;
            CaveBiomeSelector.CaveBiome cb = CaveBiomeSelector.select(
                    wx, wy, wz, SYN_SURFACE, WORLD_MIN_Y, NEUTRAL_LITHO, ct);
            if (cb != CaveBiomeSelector.CaveBiome.NONE) {
                int comp = CaveShape.components(wx, wy, wz, SYN_SURFACE, WORLD_MIN_Y, NEUTRAL_LITHO);
                if (comp == 0) inconsistent++;
            }
        }
        System.out.printf("[4] 一致性（非 NONE ⇒ 确为洞穴）: 采样=%d 不一致=%d%n",
                sampled, inconsistent);
        boolean pass4 = inconsistent == 0;
        System.out.printf("[判据4] 群系与洞穴几何一致（不漂到岩石里）: %s%n",
                pass4 ? "PASS" : "FAIL");

        // ---------- 判据5：性能 ----------
        //   参考基准：洞穴几何自身约 2.48 ms/chunk（runCavePerfProbe）。
        //   本判据只要"单次调用成本"在同一量级（不因群系判定而显著变慢）。
        double usPerCall = el / 1000.0 / total;
        boolean pass5 = usPerCall <= 2.0;      // us/次（洞穴几何约 0.1~0.5 us/次量级）
        System.out.printf("[判据5] 单次裁定 ≤ 2.0 us（与洞穴几何同量级）: %s（实测 %.3f us）%n",
                pass5 ? "PASS" : "FAIL", usPerCall);

        // ---------- ★ 判据6（2026-09-16 新增）：过渡带是【渐变】而非【阶跃】----------
        //   ★ 为何要这条："硬截断"与"渐变"在旧判据下<b>都能通过</b> ——
        //     判据2 只查"最小深度 ≥ 12"，两者都满足；判据1 只查"有产出"，两者都满足。
        //     必须按【深度分档的启用率】才区分得开（与紫晶洞"判据8 占比"同源思路）。
        //   期望：硬壳(12)之下，启用率随深度由 ~0 渐升到 ~1。
        System.out.printf("%n[2b] 启用率 vs 深度（分母=该深度的【洞穴】体素数）:%n");
        int minD = CaveBiomeSelector.MIN_DEPTH;
        int transD = CaveBiomeSelector.TRANSITION_DEPTH;
        double[] rate = new double[DEPTH_BINS];
        for (int d = 0; d < DEPTH_BINS; d++) {
            rate[d] = caveAtDepth[d] == 0 ? Double.NaN
                    : 100.0 * assignedAtDepth[d] / caveAtDepth[d];
        }
        for (int d = minD - 4; d < Math.min(DEPTH_BINS, minD + transD + 8); d += 2) {
            if (d < 0) continue;
            System.out.printf("    深度 %3d  洞穴体素=%8d  赋群系=%8d  启用率=%s%n",
                    d, caveAtDepth[d], assignedAtDepth[d],
                    Double.isNaN(rate[d]) ? "  (无样本)"
                            : String.format("%5.1f%%", rate[d]));
        }
        // ① 过渡带内启用率【单调不减】（容忍 3 个百分点的采样抖动）
        boolean monotone = true;
        double prevRate = -1;
        for (int d = minD; d < Math.min(DEPTH_BINS, minD + transD); d++) {
            if (caveAtDepth[d] < 200) continue;          // 样本太少不判
            if (Double.isNaN(rate[d])) continue;
            if (rate[d] < prevRate - 3.0) { monotone = false; break; }
            prevRate = Math.max(prevRate, rate[d]);
        }
        // ② 确实存在【中间值】（渐变 vs 阶跃的判据：阶跃时中间档几乎不存在）
        int intermediate = 0;
        for (int d = minD; d < Math.min(DEPTH_BINS, minD + transD); d++) {
            if (caveAtDepth[d] >= 200 && !Double.isNaN(rate[d])
                    && rate[d] > 5.0 && rate[d] < 95.0) intermediate++;
        }
        boolean pass6 = monotone && intermediate >= 3;
        System.out.printf("[判据6] 过渡带为渐变（启用率单调不减 且 中间档>=3）: %s"
                        + "（单调=%s 中间档=%d，硬壳%d + 过渡%d）%n",
                pass6 ? "PASS" : "FAIL", monotone ? "是" : "否", intermediate,
                minD, transD);
        System.out.println("     改前为硬截断时：启用率在深度 12 处由 0% 直接跳到 100%"
                + " ⇒ 本条会 FAIL（中间档=0）");

        int failures = (pass1 ? 0 : 1) + (pass2 ? 0 : 1) + (pass3 ? 0 : 1)
                + (pass4 ? 0 : 1) + (pass5 ? 0 : 1) + (pass6 ? 0 : 1);
        System.out.println(failures == 0 ? "ALL PASS" : ("FAILURES=" + failures));
    }
}
