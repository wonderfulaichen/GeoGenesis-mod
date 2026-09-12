package com.geogenesis.worldgen.terrain;

/**
 * 陆地高度场（eLand）连续性探针（★ 2026-09-12）。
 *
 * <p><b>用户反馈</b>：<b>在"岩石类型各类型的交界边界"上</b>出现不自然的窄带/斑驳，
 * 地形"不像一个整体"。
 *
 * <p>此前我只测了形变分量（{@code TectonicDeformation.offset}）的连续性，
 * <b>没有测最终合成的 {@code eLand}</b>。本探针直接测它——沿垂直板块边界的扫描线，
 * 以 1wu 细步遍历，定位<b>真实的</b>不连续位置与幅度，并归因到具体分量。
 *
 * <p>归因手法：同时记录 {@code stress}（T1 权重调制的输入）与 {@code offset}（T5 形变），
 * 看跳变与哪一个同步。
 *
 * <p>用法：{@code gradlew runLandEConformityProbe [seed]}
 */
public final class LandEConformityProbe {

    /**
     * e 单位跳变阈值。
     *
     * <p>★ 取值说明：1wu 水平距离对应 1 块垂直变化 = 1/192 e ≈ 0.0052e（45° 坡）。
     * 故 0.005e 以内是<b>正常陡坡</b>，不算跳变。真正可疑的是 <b>&gt;0.02e（≈4 块）</b>
     * 的单步跳变——那才是"台阶/断裂"。</p>
     */
    private static final double JUMP_EPS = 0.02;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        System.out.printf("=== LandEConformityProbe seed=%d ===%n", seed);
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        TectonicField tf = new TectonicField(seed);
        TectonicDeformation td = new TectonicDeformation(seed);

        // 沿多条扫描线（固定 z，遍历 x）以 1wu 细步测量 eLand 跳变
        double maxJump = 0, sumJump = 0;
        int jumps = 0, n = 0;
        int stressJumps = 0, distJumps = 0;
        double worstX = 0, worstZ = 0;
        int shown = 0;
        for (double z0 = -6000; z0 <= 6000; z0 += 1049) {
            double prevE = Double.NaN, prevStress = 0, prevDist = 0;
            for (double x = -6000; x <= 6000; x += 1.0) {
                double e = gen.landE(x, z0);
                TectonicField.Sample s = tf.sample(x, z0);
                if (!Double.isNaN(prevE)) {
                    n++;
                    double je = Math.abs(e - prevE);
                    sumJump += je;
                    if (je > JUMP_EPS) {
                        jumps++;
                        if (je > maxJump) { maxJump = je; worstX = x; worstZ = z0; }
                        double js = Math.abs(s.stress() - prevStress);
                        double jd = Math.abs(s.dist() - prevDist);
                        if (js > 0.02) stressJumps++;
                        if (jd > 1.5) distJumps++;
                        if (shown < 8) {
                            shown++;
                            // ★ 同时看：T5 形变分量 / T1 权重调制输入（MOUNTAINS、BASIN 权重）
                            //   —— 定位 eLand 跳变的真实放大环节
                            double offNow = td.offset(s, x, z0);
                            TectonicField.Sample sp = tf.sample(x - 1.0, z0);
                            double offPrev = td.offset(sp, x - 1.0, z0);
                            double[] wNow = gen.typeWeightsAt(x, z0);
                            double[] wPrev = gen.typeWeightsAt(x - 1.0, z0);
                            int mt = TerrainClass.MOUNTAINS.ordinal();
                            int bs = TerrainClass.BASIN.ordinal();
                            int pl = TerrainClass.PLAIN.ordinal();
                            System.out.printf("    JUMP#%d x=%.1f z=%.1f dE=%.5f | dStress=%.3f dDist=%.2f "
                                + "dist=%.0f | dOffset=%.5f | dW(mt)=%+.4f dW(bs)=%+.4f dW(pl)=%+.4f%n",
                                shown, x, z0, je, js, jd, s.dist(),
                                offNow - offPrev,
                                wNow[mt] - wPrev[mt], wNow[bs] - wPrev[bs], wNow[pl] - wPrev[pl]);
                        }
                    }
                }
                prevE = e; prevStress = s.stress(); prevDist = s.dist();
            }
        }
        System.out.printf("[1] eLand 跳变(n=%d): >%.3fe 共 %d 次  最大=%.5fe @(%.0f,%.0f)  平均=%.6fe%n",
            n, JUMP_EPS, jumps, maxJump, worstX, worstZ, sumJump / n);
        System.out.printf("    归因: 与 stress 跳变同步=%d 与 dist 跳变同步=%d%n",
            stressJumps, distJumps);

        // ================= [2] 按 dist 分层：看残余跳变是否集中在边界线上 =================
        //   若集中在 dist≈0（边界线）→ 说明"配对不确定性"仍有残余；
        //   若分散在 dist 较大处 → 说明来自 T5 形变或地形配方本身。
        double[] bandMax = new double[4];
        int[] bandCnt = new int[4];
        for (double z0 = -6000; z0 <= 6000; z0 += 1049) {
            double prevE = Double.NaN;
            double prevD = 0;
            for (double x = -6000; x <= 6000; x += 1.0) {
                double e = gen.landE(x, z0);
                TectonicField.Sample s = tf.sample(x, z0);
                if (!Double.isNaN(prevE)) {
                    double je = Math.abs(e - prevE);
                    if (je > JUMP_EPS) {
                        int b = prevD < 30 ? 0 : (prevD < 150 ? 1 : (prevD < 400 ? 2 : 3));
                        bandMax[b] = Math.max(bandMax[b], je);
                        bandCnt[b]++;
                    }
                }
                prevE = e; prevD = s.dist();
            }
        }
        String[] bandName = {"dist<30(边界线)", "30~150", "150~400", ">400(内部)"};
        for (int b = 0; b < 4; b++) {
            System.out.printf("    %-16s 跳变次数=%-4d 最大=%.5fe%n", bandName[b], bandCnt[b], bandMax[b]);
        }
        // ================= [3] 内部区（dist>REACH）仍跳 → 必为非地质来源 =================
        //   T5 形变在 reach（褶皱900/断层700）外严格为 0，故 dist>1000 处的跳变
        //   不可能来自地质系统 → 若此处也跳，说明是【地形配方本身】（噪声/类型混合/
        //   海岸线/特征增量）或 A/B 之外的既有行为。
        double innerMax = 0;
        double innerX = 0, innerZ = 0;
        int innerCnt = 0;
        for (double z0 = -6000; z0 <= 6000; z0 += 1049) {
            double prevE = Double.NaN;
            double prevD = 0;
            for (double x = -6000; x <= 6000; x += 1.0) {
                double e = gen.landE(x, z0);
                TectonicField.Sample s = tf.sample(x, z0);
                if (!Double.isNaN(prevE) && prevD > 1100 && s.dist() > 1100) {
                    double je = Math.abs(e - prevE);
                    if (je > JUMP_EPS) {
                        innerCnt++;
                        if (je > innerMax) { innerMax = je; innerX = x; innerZ = z0; }
                    }
                }
                prevE = e; prevD = s.dist();
            }
        }
        System.out.printf("[3] 纯内部区(dist>1100, 地质影响=0): 跳变次数=%d 最大=%.5fe @(%.0f,%.0f)%n",
            innerCnt, innerMax, innerX, innerZ);
        System.out.println("    → 此处若仍跳，必为【地形配方本身】而非地质系统（T5 在此严格为 0）");

        // 判据：最大单步跳变 < 0.02e（≈3.8 块）。
        //   ★ 现状（2026-09-12）：已由 0.414e（80 块）→ 0.056e（≈11 块），改善 7.4×，
        //   但仍未达 0.02e 目标。残余 47 次 / 144000 点（0.03%），
        //   分布在各 dist 分层 → 来源为 Voronoi 边界/顶点处「配对不确定性」的残余，
        //   以及对 stress 做 5 点平均后的残留（步长 55wu 未完全覆盖不确定性尺度）。
        //   本探针**保持严格判据并如实报 FAIL**，作为后续继续收敛的量化靶子
        //   （不为了让 CI 变绿而放宽阈值 —— 那会掩盖"边界处仍不够自然"这一真实事实）。
        boolean pass = maxJump < 0.02;
        System.out.printf("    最大跳变 <0.02e(≈3.8块): %s%n", pass ? "PASS" : "FAIL（仍有残余，见上）");
        if (!pass) System.exit(1);
        System.out.println("=== ALL PASS ===");
    }
}
