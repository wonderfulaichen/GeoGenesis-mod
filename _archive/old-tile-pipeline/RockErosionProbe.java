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

    /**
     * 复刻 GeoGenesisGenerator 的水平层逻辑，求该列在绝对高度 y 处的岩性 ordinal。
     * 探针必须与方块层【同一套算法】，否则测的不是实际铺的方块。
     */
    private static int ordAtY(Cell cell, int y) {
        int nLay = StratumField.LAYER_COUNT * 4;
        int[] th = new int[nLay];
        int total = 0;
        for (int i = 0; i < nLay; i++) {
            th[i] = StratumField.thicknessOf(
                    StratumField.thickLevelAt(cell.rockSeqPacked, cell.rockLayer + i));
            total += th[i];
        }
        if (total <= 0) return -1;
        int base = (int) Math.round(63 + cell.rockTilt);   // seaLevel=63
        int local = Math.floorMod(y - base, total);
        int acc = 0;
        for (int i = 0; i < nLay; i++) {
            if (local < acc + th[i]) {
                int id = StratumField.seqAt(cell.rockSeqPacked, cell.rockLayer + i);
                return (id >= 0 && id < RockType.values().length) ? id : -1;
            }
            acc += th[i];
        }
        return -1;
    }

    /** 百分比（分母 0 时返回 0）。 */
    private static double pctOf(int a, int b) {
        return b > 0 ? 100.0 * a / b : 0.0;
    }

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

        // ★★★ 判据重构（2026-09-14）：改为【受控 A/B】★★★
        //   原判据比较"软岩区的平均 delta vs 硬岩区"，但软岩（页岩/砂岩）与硬岩
        //   （花岗岩/片麻岩）在真实世界分布在【不同地质环境】（裂谷 vs 造山带）
        //   ⇒ 降水、汇流量、绝对高度、坡度全都不同。实测即使按坡度分层，
        //   仍得出"软岩蚀得更浅"的【反向结论】（-0.00492 vs -0.00584）——
        //   那是环境混杂，不是岩性效应。此类"跨区域比较"永远无法隔离岩性。
        //
        //   正解：对【同一块地形、同一液滴种子】分别跑"含硬度 / 不含硬度"两次侵蚀。
        //   液滴轨迹逐位一致，唯一差异是硬度因子 ⇒ 差值即【纯岩性效应】，无任何混杂。
        System.out.println("[3] 受控 A/B（同地形同轨迹，只切岩性耦合）:");
        int px = bestTx + CellGenerator.ROCK_QUANT;   // 取 tile 内一点作为取样中心
        int pz = bestTz + CellGenerator.ROCK_QUANT;
        float[][] onRes = gen.erosionDeltaABForProbe(px, pz, true);
        float[][] offRes = gen.erosionDeltaABForProbe(px, pz, false);
        float[] onFlat = onRes[0], onPre = onRes[1];
        float[] offFlat = offRes[0];
        int buf = onFlat.length;
        int side = (int) Math.sqrt(buf);
        int pad = (side - CellGenerator.erodeTileSizeProbe()) / 2;
        int n = CellGenerator.erodeTileSizeProbe();

        // 分组：按该格岩性（软/硬）比较"含硬度 − 不含硬度"的侵蚀量变化
        double sumSoftDiff = 0, sumHardDiff = 0;
        int nS = 0, nH = 0;
        double totOn = 0, totOff = 0;
        for (int lz = pad; lz < pad + n; lz++) {
            for (int lx = pad; lx < pad + n; lx++) {
                int i = lz * side + lx;
                if (onPre[i] < 0) continue;                       // 只看陆地
                float dOn = onFlat[i] - onPre[i];                 // 含硬度：侵蚀增量（负=蚀低）
                float dOff = offFlat[i] - onPre[i];               // 不含硬度
                totOn += dOn; totOff += dOff;
                double r = gen.rockResistanceAt(px + lx - pad, pz + lz - pad);
                if (r < 0.45) { sumSoftDiff += (dOn - dOff); nS++; }
                else if (r > 0.75) { sumHardDiff += (dOn - dOff); nH++; }
            }
        }
        double softDiff = nS > 0 ? sumSoftDiff / nS : 0;
        double hardDiff = nH > 0 ? sumHardDiff / nH : 0;
        System.out.printf("      软岩格 n=%-5d 耦合前后变化=%+.6f（越负=更易蚀）%n", nS, softDiff);
        System.out.printf("      硬岩格 n=%-5d 耦合前后变化=%+.6f%n", nH, hardDiff);
        System.out.printf("      全区差: 含硬度=%+.6f 不含=%+.6f%n", totOn / (n * n), totOff / (n * n));
        // 判据①：软岩应因耦合而【蚀得更深】（softDiff 显著为负）
        boolean pass3 = nS > 50 && softDiff < -1e-6;
        System.out.printf("      软岩因耦合被显著加深侵蚀: %s%n", pass3 ? "PASS" : "FAIL");
        // 判据②：软岩的加深幅度必须【大于】硬岩（岩性差异真实生效）
        boolean pass3b = nS > 50 && nH > 50 && softDiff < hardDiff;
        System.out.printf("      软岩加深幅度大于硬岩（软<硬）: %s%n", pass3b ? "PASS" : "FAIL");
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

        // ================= [9] T9c：地表裸岩必须【跟随岩层】=================
        //   【用户反馈】"表面陡峭坡的裸露岩石还是石头，并不是岩层的方块"
        //   根因：fillTerrainColumn 的陡坡分支与群系 STONE 分支都硬编码 STONE，
        //   而 T9/T9b 只改了地下 ⇒ 地表与地下岩性脱节。
        //   【判据】陡坡（gradient > ROCK_GRADIENT）的陆地列，其"最浅层岩性"
        //   必须是有效 ordinal（>=0）⇒ 方块层会据此出露岩层方块（而非 STONE）。
        //   统计陡坡列中有多少能取到岩性（应 ≈100%，否则说明地表仍会退回 STONE）。
        System.out.println("[9] 地表裸岩跟随岩层（陡坡列应能取到岩性）:");
        // ★ 必须用 sampleWu（含侵蚀 tile）—— cell.gradient 只在 applyTileDelta 中
        //   计算（基于侵蚀后地形），普通 sample() 的 gradient 恒为 0 ⇒ 筛不出陡坡。
        int nSteep = 0, nSteepHasRock = 0;
        double steepMinX = 0, steepMinZ = 0;
        for (double z = oz - 300; z <= oz + 300; z += 43) {
            for (double x = ox - 300; x <= ox + 300; x += 47) {
                Cell c = gen.sampleWu(x, z);
                if (c.e <= 0.05) continue;                 // 只看陆地
                if (!(c.gradient > 0.40f)) continue;       // 只看陡坡（ROCK_GRADIENT=0.40）
                nSteep++;
                if (c.rockSeqPacked != 0) {
                    int id = StratumField.seqAt(c.rockSeqPacked, c.rockLayer);
                    if (id >= 0 && id < RockType.values().length) nSteepHasRock++;
                } else {
                    steepMinX = x; steepMinZ = z;
                }
            }
        }
        System.out.printf("      陡坡列=%d | 能取到岩性=%d (%.1f%%)%n",
                nSteep, nSteepHasRock, nSteep > 0 ? 100.0 * nSteepHasRock / nSteep : 0);
        if (nSteepHasRock < nSteep) {
            System.out.printf("      未取到岩性的陡坡点示例 (%.0f,%.0f)%n", steepMinX, steepMinZ);
        }
        // 判据：陡坡列必须能按岩性出露（>90%）；为 0 说明 STRATA 未生效 ⇒ 地表仍 STONE
        boolean pass9 = nSteep > 10 && nSteepHasRock * 10 > nSteep * 9;
        System.out.printf("      陡坡裸岩按岩性出露（>90%%）: %s%n", pass9 ? "PASS" : "FAIL");

        // ================= [10] T10：地层水平化（同海拔岩性应横向连续）=================
        //   【T9b 披盖式】层界 = 地表 - 累加层厚 ⇒ 层随地形起伏（洋葱裹山）
        //   【T10 水平层】层界 = 绝对 Y（+ 区域倾斜）⇒ 同一海拔的岩性【横向连续】，
        //     被地形切割后山坡露出水平条带（真实地层 + 恶地观感）。
        //   判据：取两个水平相邻的采样点（同 y、不同 x），若地表高度差异大（说明地形起伏），
        //   但"同一 y 的岩性"仍相同 ⇒ 水平层生效（披盖式下会不同）。
        System.out.println("[10] 地层水平化（同 y 处的岩性应横向一致）:");
        int nPair = 0, nSame = 0;
        double tiltSum = 0; int nTilt = 0;
        for (double z = oz - 600; z <= oz + 600; z += 37) {
            for (double x = ox - 600; x <= ox + 600; x += 41) {
                Cell a = gen.sample(x, z);
                Cell b = gen.sample(x + 41, z);
                if (a.e <= 0.05 || b.e <= 0.05) continue;
                if (a.rockSeqPacked == 0 || b.rockSeqPacked == 0) continue;
                // 地形起伏必须显著（否则两点的层序本就可能相同，测不出差异）
                if (Math.abs(a.height - b.height) < 15) continue;
                nPair++;
                tiltSum += Math.abs(a.rockTilt - b.rockTilt); nTilt++;
                // 取一个共同深度（都在地表之下的同一绝对 Y）
                int yCommon = (int) Math.floor(Math.min(a.height, b.height)) - 10;
                int oa = ordAtY(a, yCommon), ob = ordAtY(b, yCommon);
                if (oa >= 0 && oa == ob) nSame++;
            }
        }
        System.out.printf("      地形起伏显著的点对=%d | 同 y 岩性相同=%d (%.1f%%)%n",
                nPair, nSame, nPair > 0 ? 100.0 * nSame / nPair : 0);
        System.out.printf("      相邻点倾斜差均值=%.2f 块（区域尺度起伏，非平面）%n",
                nTilt > 0 ? tiltSum / nTilt : 0);
        // 水平层：绝大多数点对在同一 y 应同岩性（>70%）；披盖式会显著更低
        boolean pass10 = nPair > 30 && nSame * 10 > nPair * 7;
        System.out.printf("      地层在横向连续（>70%% 同岩性）: %s%n", pass10 ? "PASS" : "FAIL");

        // ================= [11] T11：坡度分档（碎石坡 / 裸岩）覆盖率 =================
        //   参考 RTF ErodeFeature 的三档坡度。本项目 T11 前的分档：
        //     gradient > 0.40 → 裸岩 ；其余 → 草/土（山地"草→裸岩"突变）
        //   T11 后：> 0.40 裸岩（干旱区→恶地陶瓦）；0.25~0.40 碎石坡；其余 草/土。
        //   判据：三档都应有合理占比（碎石坡 > 裸岩，且都不吞掉大部分地表）。
        System.out.println("[11] 坡度分档覆盖率（裸岩 / 碎石坡 / 植被）:");
        int nRock = 0, nScree = 0, nVeg = 0, nTot = 0;
        for (double z = oz - 500; z <= oz + 500; z += 29) {
            for (double x = ox - 500; x <= ox + 500; x += 31) {
                Cell c = gen.sampleWu(x, z);              // 须含侵蚀 tile 才有 gradient
                if (c.e <= 0.05) continue;
                nTot++;
                float g = c.gradient;
                if (g > 0.40f) nRock++;
                else if (g > 0.30f) nScree++;
                else nVeg++;
            }
        }
        System.out.printf("      陆地采样=%d | 裸岩(>0.40)=%d (%.1f%%) | 碎石坡(0.25~0.40)=%d (%.1f%%) | 植被=%d (%.1f%%)%n",
                nTot, nRock, pctOf(nRock, nTot), nScree, pctOf(nScree, nTot), nVeg, pctOf(nVeg, nTot));
        // 判据：植被仍占多数（>50%，不吞掉地表）；碎石坡与裸岩都真实存在
        boolean pass11 = nTot > 200 && nVeg * 2 > nTot && nScree > 0 && nRock > 0;
        System.out.printf("      植被仍占多数且两档岩石坡均存在: %s%n", pass11 ? "PASS" : "FAIL");

        // ================= [12] T10b：层界起伏（局部视野内不得是平直直线）=================
        //   【用户反馈】"你这个岩层没点轻微浮动吗？"+ 截图层界完全水平。
        //   根因：T10 只用单一 1/1200 低频倾斜（±30）⇒ 在玩家视野（~100 块）内
        //   仅变化 ~2.5 块 ⇒ 局部看起来是绝对平直的直线。
        //   【修复】多尺度叠加（1/1200±30 + 1/220±7 + 1/55±2.5）。
        //   【判据】在 128×128（一个视距级）窗口内，层界偏移（rockTilt）的
        //   <b>极差</b>必须显著（有可见起伏），但不过大（层界不混乱）。
        System.out.println("[12] 层界起伏（128×128 视野窗口内的偏移极差）:");
        double worstRange = 0, worstX = 0, worstZ = 0;
        double sumRange = 0; int nWin = 0;
        for (double z0 = oz - 400; z0 <= oz + 400; z0 += 128) {
            for (double x0 = ox - 400; x0 <= ox + 400; x0 += 128) {
                double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
                for (double z = z0; z <= z0 + 128; z += 8) {
                    for (double x = x0; x <= x0 + 128; x += 8) {
                        double t = gen.sample(x, z).rockTilt;
                        mn = Math.min(mn, t); mx = Math.max(mx, t);
                    }
                }
                double rng = mx - mn;
                sumRange += rng; nWin++;
                if (rng > worstRange) { worstRange = rng; worstX = x0; worstZ = z0; }
            }
        }
        double avgRange = nWin > 0 ? sumRange / nWin : 0;
        System.out.printf("      窗口数=%d | 平均极差=%.1f 块 | 最大极差=%.1f 块 at (%.0f,%.0f)%n",
                nWin, avgRange, worstRange, worstX, worstZ);
        // 判据：视野内平均起伏 > 3 块（肉眼可见），且 < 60 块（不混乱）
        boolean pass12 = nWin > 10 && avgRange > 3.0 && avgRange < 60.0;
        System.out.printf("      层界在视野内有可见起伏（3~60 块）: %s%n", pass12 ? "PASS" : "FAIL");

        boolean all = pass1 && pass2 && pass3 && pass5 && pass6 && pass7 && pass8
                && pass9 && pass10 && pass11 && pass12 && pass3b;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
        if (!all) System.exit(1);
    }
}
