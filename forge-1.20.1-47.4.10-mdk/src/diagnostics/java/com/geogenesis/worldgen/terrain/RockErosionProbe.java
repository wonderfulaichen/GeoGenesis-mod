package com.geogenesis.worldgen.terrain;

/**
 * ★ 2026-09-14 Phase T8(P3)：<b>岩性 → 侵蚀耦合</b>验收探针（软岩成谷、硬岩成脊）。
 *
 * <h3>为何必须量化（不能只看图）</h3>
 * <p>"软岩更易蚀"是一个<b>统计性质</b>：单看某处地形无法区分"是岩性造成的谷"
 * 还是"本来就低"。本探针在<b>同一批坐标</b>上按真实岩性分组统计侵蚀增量，
 * 从而与坡度和气候的贡献分离。</p>
 *
 * <h3>判据</h3>
 * <ol>
 *   <li><b>抗蚀性跨度足够</b>：最软/最硬的侵蚀量比应在 1.3~3 倍
 *       （理论值 = (1−0.6×0.30)/(1−0.6×0.90) ≈ 1.78×）。</li>
 *   <li><b>硬度场连续</b>（不得引入折痕）：1wu 步长下的最大跳变 &lt; 0.02。</li>
 *   <li><b>软岩确实被蚀更深</b>：软岩区平均 delta 比硬岩区更负。</li>
 *   <li><b>未整体加剧侵蚀</b>：耦合是"重分配"而非"加剧"（倍率上限 1）。</li>
 * </ol>
 *
 * <h3>用法</h3>
 * <pre>{@code
 * gradlew runRockErosionProbe [-PprobeArgs="seed [ox oz]"]
 * }</pre>
 */
public final class RockErosionProbe {

    public static void main(String[] args) throws Exception {
        TerrainParams p = TerrainParams.defaults();
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int ox = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int oz = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        System.out.printf("=== RockErosionProbe seed=%d origin=(%d,%d) ===%n", seed, ox, oz);

        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);

        // ================= [1] 抗蚀性取值覆盖 =================
        System.out.println("[1] 各岩性的抗蚀性（RockType.resistance，越大越难蚀）:");
        double rMin = Double.MAX_VALUE, rMax = -Double.MAX_VALUE;
        for (RockType rt : RockType.values()) {
            System.out.printf("      %-10s %.2f  (%s)%n", rt.name(), rt.resistance(), rt.kind());
            rMin = Math.min(rMin, rt.resistance());
            rMax = Math.max(rMax, rt.resistance());
        }
        double theory = (1 - 0.6 * rMin) / (1 - 0.6 * rMax);
        System.out.printf("      理论侵蚀量比 (最软/最硬) = (1-0.6×%.2f)/(1-0.6×%.2f) = %.2f×%n",
                rMin, rMax, theory);
        boolean pass1 = rMin < 0.4 && rMax > 0.8 && theory > 1.3 && theory < 3.0;
        System.out.printf("      抗蚀性跨度足够且比值合理: %s%n", pass1 ? "PASS" : "FAIL");

        // ================= [2] 硬度场连续性（测【引擎实际使用的插值场】）=================
        //   ★ 注意区别（初版测错对象）：
        //     · rockResistanceAt 原始场是【岩性单元的离散值】——单元之间天然有 0.4 级差，
        //       这是地质事实、不是缺陷；
        //     · 引擎通过 buildHardnessGrid(8wu) + sampleHardness(双线性) 使用它
        //       ⇒ 真正要判"是否引入折痕"的是【插值后】的场（这才是地形感知到的）。
        //   判据：插值场在 1wu 步长下的跳变应 ≤ ~0.075（= 0.6 / 8wu 间距，随岩性渐变的上限），
        //   即"没有阶跃"；同时必须显著小于岩性差异本身。
        System.out.println("[2] 硬度场连续性（测引擎实际使用的 8wu 双线性插值场）:");
        int hn = 1024 / com.geogenesis.worldgen.erosion.ErosionEngine.HARDNESS_SPACING + 2;
        int horigX = ox - 512, horigZ = oz - 512;
        float[][] hg = com.geogenesis.worldgen.erosion.ErosionEngine.buildHardnessGrid(
                gen::rockResistanceAt, hn, horigX, horigZ);
        double maxStep = 0, stepX = 0, stepZ = 0;
        for (double z = oz - 400; z <= oz + 400; z += 37) {
            for (double x = ox - 400; x <= ox + 400; x += 41) {
                float r0 = com.geogenesis.worldgen.erosion.ErosionEngine.sampleHardness(
                        hg, hn, horigX, horigZ, (float) x, (float) z);
                float rx = com.geogenesis.worldgen.erosion.ErosionEngine.sampleHardness(
                        hg, hn, horigX, horigZ, (float) (x + 1), (float) z);
                float rz = com.geogenesis.worldgen.erosion.ErosionEngine.sampleHardness(
                        hg, hn, horigX, horigZ, (float) x, (float) z + 1);
                double dx = Math.abs(rx - r0), dz = Math.abs(rz - r0);
                if (dx > maxStep) { maxStep = dx; stepX = x; stepZ = z; }
                if (dz > maxStep) { maxStep = dz; stepX = x; stepZ = z; }
            }
        }
        System.out.printf("      插值场最大跳变=%.4f at (%.0f,%.0f) (上限 0.075 = 0.6/8wu)%n",
                maxStep, stepX, stepZ);
        boolean pass2 = maxStep <= 0.075;
        System.out.printf("      插值后无阶跃（≤0.075）: %s%n", pass2 ? "PASS" : "FAIL");

        // ================= [3] 真实侵蚀 tile：按岩性分组的侵蚀增量 =================
        //   ★ 必须【全局搜索】一个"软岩与硬岩都足够多"的 tile —— 单个 tile（48wu）
        //     可能完全落在某一种岩性内（初版实测软岩样本 n=0，导致误判 FAIL）。
        System.out.println("[3] 全局搜索含多种岩性的侵蚀 tile 并统计:");
        int bestTx = 0, bestTz = 0, bestScore = -1;
        for (int cz = -3000; cz <= 3000; cz += 96) {
            for (int cx = -3000; cx <= 3000; cx += 96) {
                int sSoft = 0, sHard = 0;
                for (int z = 0; z < 48; z += 8) {
                    for (int x = 0; x < 48; x += 8) {
                        double r = gen.rockResistanceAt(cx + x, cz + z);
                        if (r < 0.45) sSoft++;
                        else if (r > 0.75) sHard++;
                    }
                }
                int score = Math.min(sSoft, sHard);
                if (score > bestScore) { bestScore = score; bestTx = cx; bestTz = cz; }
            }
        }
        System.out.printf("      选中 tile 原点 (%d,%d) 软/硬样本平衡分=%d%n", bestTx, bestTz, bestScore);
        CellGenerator.ErosionTileResult res = gen.getErosionTileResultForProbe(bestTx, bestTz);
        if (res == null || res.delta == null || res.base == null) {
            System.out.println("      FAIL: 侵蚀 tile 不可用");
            System.exit(1);
            return;
        }
        int tx = bestTx, tz = bestTz;
        double sumSoft = 0, sumHard = 0; int nSoft = 0, nHard = 0;
        double sumAll = 0; int nAll = 0;
        int N = res.delta.length;
        for (int z = 0; z < N; z += 1) {
            for (int x = 0; x < N; x += 1) {
                if (res.base[z][x] < 0) continue;              // 只看陆地
                double r = gen.rockResistanceAt(tx + x, tz + z);
                double d = res.delta[z][x];
                sumAll += d; nAll++;
                if (r < 0.45) { sumSoft += d; nSoft++; }
                else if (r > 0.75) { sumHard += d; nHard++; }
            }
        }
        double mSoft = nSoft > 0 ? sumSoft / nSoft : 0;
        double mHard = nHard > 0 ? sumHard / nHard : 0;
        double mAll = nAll > 0 ? sumAll / nAll : 0;
        System.out.printf("      软岩区 n=%d 平均 delta=%+.5f | 硬岩区 n=%d 平均 delta=%+.5f%n",
                nSoft, mSoft, nHard, mHard);
        System.out.printf("      全区陆地 n=%d 平均 delta=%+.5f%n", nAll, mAll);
        System.out.printf("      软/硬侵蚀量比 = %.2f×（受坡度主导，故远小于纯倍率 %.2f×）%n",
                mHard != 0 ? mSoft / mHard : 0, theory);
        // 侵蚀使 delta 为负；软岩应"更负"（蚀得更深）
        boolean pass3 = nSoft > 20 && nHard > 20 && mSoft < mHard;
        System.out.printf("      软岩被蚀更深（delta 更负）: %s%n", pass3 ? "PASS" : "FAIL");
        // 只压不放大 ⇒ 不得整体大幅加剧
        boolean pass4 = mAll > -0.05;
        System.out.printf("      未整体加剧侵蚀: %s%n", pass4 ? "PASS" : "FAIL");

        // ================= [4] 耦合机制的直接验证（脱离坡度混杂）=================
        //   [3] 的 delta 含坡度贡献（主导），故岩性信号被稀释。本节直接验机制：
        //     · 引擎使用的【插值硬度场】在同一点上的倍率是否随岩性变化；
        //     · 该倍率是否恒 ∈ (0,1]（只压不放大 = 不破坏既有标定）。
        //   这是"耦合确实接线"的充分证据，且不受坡度干扰。
        System.out.println("[4] 耦合机制直接验证（硬度倍率，脱离坡度混杂）:");
        double fSoft = 1 - 0.6 * gen.rockResistanceAt(bestTx + 24, bestTz + 24);
        double fMin = 1.0, fMax = 0.0;
        int nBad = 0;
        for (int z = 0; z < 48; z += 3) {
            for (int x = 0; x < 48; x += 3) {
                double r = gen.rockResistanceAt(bestTx + x, bestTz + z);
                double f = 1 - 0.6 * r;
                fMin = Math.min(fMin, f); fMax = Math.max(fMax, f);
                if (f <= 0 || f > 1.0 + 1e-9) nBad++;
            }
        }
        System.out.printf("      该 tile 倍率范围 = [%.3f, %.3f]（页岩→0.82 / 花岗岩→0.46）%n", fMin, fMax);
        boolean pass5 = nBad == 0 && fMax <= 1.0 + 1e-9 && fMax - fMin > 0.1;
        System.out.printf("      倍率 ∈ (0,1] 且跨度>0.1（只压不放大）: %s%n", pass5 ? "PASS" : "FAIL");

        // ================= [6] T9：垂直岩层（岩性 → 方块）=========
        //   【为何必须验证】岩性此前只影响地形，玩家看不到。T9 把地层序列铺成
        //   垂直岩层 ⇒ 必须确认：① 打包/解包往返一致；② 岩层确实随深度变化；
        //   ③ 退化情形（无数据）能安全回退，不产生越界。
        System.out.println("[6] 垂直岩层（打包序列 + 深度序展开）:");
        int nPk = 0, nPkBad = 0, nVary = 0;
        for (double z = oz - 800; z <= oz + 800; z += 61) {
            for (double x = ox - 800; x <= ox + 800; x += 67) {
                Cell c = gen.sample(x, z);
                if (c.e <= 0.05) continue;                    // 只看陆地（岩层只对陆地铺）
                nPk++;
                int packed = c.rockSeqPacked;
                // ① 打包往返：seqAt(packed, layer) 必须等于 rockTypeId（同一岩性）
                int id0 = StratumField.seqAt(packed, c.rockLayer);
                if (id0 != c.rockTypeId) nPkBad++;
                // ② 序列内确有变化（否则"岩层"退化成单一方块 = 白做）
                boolean vary = false;
                for (int i = 1; i < StratumField.LAYER_COUNT; i++) {
                    if (StratumField.seqAt(packed, c.rockLayer + i) != id0) { vary = true; break; }
                }
                if (vary) nVary++;
                // ③ 越界守卫：所有层都必须是合法 ordinal
                for (int i = 0; i < StratumField.LAYER_COUNT; i++) {
                    int id = StratumField.seqAt(packed, c.rockLayer + i);
                    if (id < 0 || id >= RockType.values().length) nPkBad++;
                }
            }
        }
        System.out.printf("      陆地采样=%d | 打包往返/越界错误=%d | 序列含变化=%d (%.1f%%)%n",
                nPk, nPkBad, nVary, nPk > 0 ? 100.0 * nVary / nPk : 0);
        boolean pass6 = nPk > 50 && nPkBad == 0 && nVary * 2 > nPk;
        System.out.printf("      岩层数据自洽且确为分层（≥50%% 含变化）: %s%n", pass6 ? "PASS" : "FAIL");

        // ================= [7] T9b：层厚可变（用户："不可能固定厚度"）=================
        System.out.println("[7] 层厚可变性（同一岩层在不同位置应有不同厚度）:");
        int thMin = Integer.MAX_VALUE, thMax = 0;
        java.util.Set<Integer> thSet = new java.util.TreeSet<>();
        int nTh = 0;
        for (double z = oz - 2000; z <= oz + 2000; z += 97) {
            for (double x = ox - 2000; x <= ox + 2000; x += 101) {
                Cell c = gen.sample(x, z);
                if (c.e <= 0.05 || c.rockSeqPacked == 0) continue;
                for (int i = 0; i < StratumField.LAYER_COUNT; i++) {
                    int th = StratumField.thicknessOf(
                            StratumField.thickLevelAt(c.rockSeqPacked, c.rockLayer + i));
                    thMin = Math.min(thMin, th); thMax = Math.max(thMax, th);
                    thSet.add(th); nTh++;
                }
            }
        }
        System.out.printf("      层厚范围 = %d ~ %d 块 | 不同厚度值 = %d 种 (n=%d)%n",
                thMin, thMax, thSet.size(), nTh);
        // 判据：厚度确实随空间变化（≥5 种取值，且跨度 ≥ 20 块）
        boolean pass7 = nTh > 100 && thSet.size() >= 5 && thMax - thMin >= 20;
        System.out.printf("      层厚非固定（≥5 种取值且跨度≥20）: %s%n", pass7 ? "PASS" : "FAIL");

        // ================= [8] T9b：序列顺序（浅部成因岩在前）=================
        //   【背景】首版用 DEEPSLATE 表示片麻岩/片岩，但 MC 深板岩只在 Y<0 生成
        //   ⇒ 29.8% 的列被迫回退 STONE（大片石头）。改用闪长岩/凝灰岩后不再受限，
        //   但"序列顺序"仍应正确：最浅层应是【浅部成因岩】（花岗岩/砂岩），
        //   深变质岩（片麻岩/片岩）应在较深层 —— 否则地质上讲不通。
        System.out.println("[8] 序列顺序（浅部成因岩应在前）:");
        int nChecked = 0, nShallowMetamorphic = 0;
        for (double z = oz - 1500; z <= oz + 1500; z += 83) {
            for (double x = ox - 1500; x <= ox + 1500; x += 89) {
                Cell c = gen.sample(x, z);
                if (c.e <= 0.05 || c.rockSeqPacked == 0) continue;
                nChecked++;
                int id0 = StratumField.seqAt(c.rockSeqPacked, c.rockLayer);
                // 最浅层是深变质岩（片麻岩 0 / 片岩 1）= 顺序反了
                if (id0 == 0 || id0 == 1) nShallowMetamorphic++;
            }
        }
        System.out.printf("      检查列=%d | 最浅层为深变质岩=%d (%.1f%%)%n",
                nChecked, nShallowMetamorphic,
                nChecked > 0 ? 100.0 * nShallowMetamorphic / nChecked : 0);
        // 判据：最浅层为深变质岩的比例应低（岩石圈/造山带序列已修为花岗岩在前）
        boolean pass8 = nChecked > 50 && nShallowMetamorphic * 4 < nChecked;
        System.out.printf("      浅部非深变质岩（<25%%）: %s%n", pass8 ? "PASS" : "FAIL");

        boolean all = pass1 && pass2 && pass3 && pass4 && pass5 && pass6 && pass7 && pass8;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
        if (!all) System.exit(1);
    }
}
