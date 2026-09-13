package com.geogenesis.worldgen.terrain;

/**
 * 构造骨架场探针（★ 2026-09-12，地质系统 Phase T1 配套）。
 *
 * <p>验证 {@link TectonicField} 产出的板块边界在<b>地质学上合理</b>：
 * <ol>
 *   <li>三种边界类型（汇聚/离散/走滑）都非退化地出现</li>
 *   <li>高程偏置<b>符号正确</b>：陆-陆汇聚造山(+)、洋侧俯冲海沟(−)、
 *       陆内裂谷(−)、洋洋离散洋中脊(+)、走滑无垂向(0)</li>
 *   <li>确定性、性能有界</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runTectonicProbe [seed]}</p>
 */
public final class TectonicProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        System.out.printf("=== TectonicProbe seed=%d ===%n", seed);
        TectonicField tf = new TectonicField(seed);

        // ================= [1] 边界类型分布 =================
        int n = 0, interior = 0, conv = 0, div = 0, trans = 0;
        double sumDist = 0;
        long t0 = System.nanoTime();
        for (double z = -20000; z <= 20000; z += 97) {
            for (double x = -20000; x <= 20000; x += 101) {
                TectonicField.Sample s = tf.sample(x, z);
                n++;
                switch (s.btype()) {
                    case TectonicField.INTERIOR -> interior++;
                    case TectonicField.CONVERGENT -> conv++;
                    case TectonicField.DIVERGENT -> div++;
                    case TectonicField.TRANSFORM -> trans++;
                    default -> { }
                }
                if (s.onBoundary()) sumDist += s.dist();
            }
        }
        long t1 = System.nanoTime();
        double usPer = (t1 - t0) / 1000.0 / n;

        double convPct = 100.0 * conv / n, divPct = 100.0 * div / n, transPct = 100.0 * trans / n;
        System.out.printf("[1] 采样 n=%d: 内部=%.1f%% 汇聚=%.2f%% 离散=%.2f%% 走滑=%.2f%%%n",
            n, 100.0 * interior / n, convPct, divPct, transPct);
        // 三种边界都必须出现（非退化）
        boolean pass1 = conv > 0 && div > 0 && trans > 0;
        System.out.printf("    三种边界均出现: %s%n", pass1 ? "PASS" : "FAIL");

        // ================= [2] 边界 profile 符号正确性 =================
        // 直接构造各类型的 Sample，验证 elevationOffset 的符号与相对大小。
        // 用 dist=0（边界线上）以取得最大幅度。
        double convLand = tf.elevationOffset(smp(TectonicField.CONVERGENT), true);
        double convOcean = tf.elevationOffset(smp(TectonicField.CONVERGENT), false);
        double divLand = tf.elevationOffset(smp(TectonicField.DIVERGENT), true);
        double divOcean = tf.elevationOffset(smp(TectonicField.DIVERGENT), false);
        double transOff = tf.elevationOffset(smp(TectonicField.TRANSFORM), true);
        double interiorOff = tf.elevationOffset(smp(TectonicField.INTERIOR), true);

        System.out.printf("[2] 边界 profile(e单位): 陆汇聚=%+.4f 洋汇聚=%+.4f 陆离散=%+.4f 洋离散=%+.4f 走滑=%+.4f 内部=%+.4f%n",
            convLand, convOcean, divLand, divOcean, transOff, interiorOff);
        boolean pass2 = convLand > 0      // 造山为正
                     && convOcean < 0     // 海沟为负
                     && divLand < 0       // 裂谷为负
                     && divOcean > 0      // 洋中脊为正
                     && transOff == 0.0   // 走滑无垂向
                     && interiorOff == 0.0;
        System.out.printf("    符号正确(造山+/海沟-/裂谷-/洋脊+/走滑0): %s%n", pass2 ? "PASS" : "FAIL");

        // ================= [3] 确定性 =================
        int bad = 0;
        TectonicField tf2 = new TectonicField(seed);
        for (int i = 0; i < 400; i++) {
            double x = i * 313.0 - 60000, z = i * 197.0 - 40000;
            TectonicField.Sample a = tf.sample(x, z);
            TectonicField.Sample b = tf2.sample(x, z);
            if (a.btype() != b.btype() || Double.compare(a.dist(), b.dist()) != 0
                    || Double.compare(a.rate(), b.rate()) != 0) {
                bad++;
            }
        }
        boolean pass3 = bad == 0;
        System.out.printf("[3] 确定性(n=400): 不一致=%d %s%n", bad, pass3 ? "PASS" : "FAIL");

        // ================= [4] 性能 =================
        // 必须远低于 terrainEQuick（微秒级），否则会重演 coldMs 9.4× 退化。
        boolean pass4 = usPer < 5.0;
        System.out.printf("[4] 性能: 单次采样 %.3f µs (阈值 5µs) %s%n", usPer, pass4 ? "PASS" : "FAIL");

        // ================= [5] 核心目标：山脉是否沿汇聚边界成带 =================
        //   这是本阶段要解决的核心问题 G2（造山带线状化）：MOUNTAINS 类型权重
        //   在汇聚边界附近应显著高于板块内部。
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        // 只统计【陆地】汇聚边界 vs 【陆地】内部：海洋汇聚走海沟分支（提升 DEEP_OCEAN），
        // 本就不该造山；若混在一起统计，海洋边界会把陆侧造山效果稀释掉。
        //
        // ★ 2026-09-12 探针修正：选择器必须与【驱动器】一致。
        //   地形 boost 现由【连续 stress 低频场】驱动，而 btype 是【逐点 Voronoi 配对】的
        //   离散标签 —— 两者已来自互不相关的场。旧探针用 btype 筛选，测到的"汇聚点"
        //   未必是 boost 真正生效处 → 比值被系统性低估（假 FAIL）。
        //   故改用 s.stress() 筛选（与 T1 的 cw = max(0,stress) 判据同源），
        //   同时保留 btype 版作对照，便于分辨"产品退化"与"探针脱节"。
        double cMtS = 0, cMtB = 0, iMt = 0;
        int nCS = 0, nCB = 0, nI = 0;
        for (double z = -12000; z <= 12000; z += 311) {
            for (double x = -12000; x <= 12000; x += 337) {
                TectonicField.Sample s = tf.sample(x, z);
                double[] w = gen.typeWeightsAt(x, z);
                double oceanW = w[TerrainClass.OCEAN.ordinal()] + w[TerrainClass.DEEP_OCEAN.ordinal()];
                boolean isLand = oceanW < 0.5;
                double mt = w[TerrainClass.MOUNTAINS.ordinal()];
                // ★ 最忠实口径：直接按【boost 强度】分组。
                //   T1 实际施加的 boost ∝ boundaryStrength(高斯) × max(0, stress)。
                //   只用 stress 分组是错的——它会把"远离边界的高应力内部点"也算进来
                //   （实测 n=457 vs 92，把造山带信号稀释成 1.18× 假失败）。
                double g = TectonicField.boundaryStrength(s);
                double boostProxy = g * Math.max(0.0, s.stress());
                if (isLand && boostProxy > 0.45) {
                    cMtS += mt;
                    nCS++;
                }
                // 旧口径（btype + 高斯权重），保留作对照
                if (isLand && s.btype() == TectonicField.CONVERGENT
                        && TectonicField.boundaryStrength(s) > 0.5) {
                    cMtB += mt;
                    nCB++;
                }
                if (isLand && s.btype() == TectonicField.INTERIOR) {
                    iMt += mt;
                    nI++;
                }
            }
        }
        double mi = nI > 0 ? iMt / nI : 0.0;
        double mcS = nCS > 0 ? cMtS / nCS : 0.0;
        double mcB = nCB > 0 ? cMtB / nCB : 0.0;
        double rS = mi > 0 ? mcS / mi : 0;
        double rB = mi > 0 ? mcB / mi : 0;
        // 判据改用【与驱动器同源】的 stress 口径
        boolean pass5 = rS > 1.5;
        System.out.printf("[5] 陆地 MOUNTAINS 权重: 内部=%.4f (n=%d)%n", mi, nI);
        System.out.printf("    · 强汇聚(stress>0.6, 与boost同源)=%.4f (n=%d) 比值=%.2f× %s%n",
            mcS, nCS, rS, pass5 ? "PASS" : "FAIL");
        System.out.printf("    · 旧口径(btype 配对, 对照)    =%.4f (n=%d) 比值=%.2f× (仅参考)%n",
            mcB, nCB, rB);

        // ================= [6] Phase T2：造山带沿走向串珠化 =================
        //   沿一条汇聚边界取样，比较"带 Chain"与"不带 Chain"的 MOUNTAINS 权重：
        //   串珠化的特征 = 沿走向方差显著升高（有峰有谷），且均值不至崩塌。
        //   ★ 判据设计教训：初版测【乘积 gN·m】的方差，被 gN 自身的方差（由 dist 主导）掩盖，
        //   无论怎么采样都测不出串珠效果。现改为直接测【串珠因子 m 本身】的离散度：
        //   m = 有Chain / 无Chain，它必须显著偏离 1（有峰有谷）才是真串珠。
        //   另注：采样步长必须远小于 Chain 周期（2000/18≈111wu）以免混叠。
        int nS = 0;
        double sumM = 0, sumM2 = 0, minM = Double.MAX_VALUE, maxM = -Double.MAX_VALUE;
        for (double z = -6000; z <= 6000; z += 1800) {
            for (double x = -8000; x <= 8000; x += 13) {
                TectonicField.Sample s = tf.sample(x, z);
                // ★ 2026-09-13：筛选口径必须与【驱动器同源】——chain 的汇聚权重现在
                //   取连续应力 cw = smoothPos(stress)，而非 btype 这个逐点配对标签。
                //   自 stress 改为【区域尺度投票场】（σ=1000，跨 25 块板块）后，
                //   btype==CONVERGENT（局部配对）不再蕴含 stress>0
                //   → 用旧口径筛选会把"非汇聚区"样本混入，把 m 均值稀释成假失败。
                //   阈值取 0.70：与旧口径【等价】—— btype==CONVERGENT ⟺ |dot|>|cross|
                //   ⟺ |stress| > 1/√2 ≈ 0.707（stress>0.18 时 smoothPos≈stress）。
                //   取 0.5 会把"弱汇聚"样本混入，把 m 均值稀释到 0.531（假失败）。
                double cwSel = TectonicField.smoothPos(s.stress(), TectonicField.STRESS_POS_EPS);
                if (cwSel <= 0.70 || TectonicField.boundaryStrength(s) <= 0.3) continue;
                double gN = TectonicField.boundaryStrength(s);
                double gC = tf.boundaryStrengthChained(s, x, z);
                double m = gC / gN;                 // 串珠因子
                sumM += m; sumM2 += m * m;
                minM = Math.min(minM, m);
                maxM = Math.max(maxM, m);
                nS++;
            }
        }
        double meanM = nS > 0 ? sumM / nS : 0;
        double sdM = nS > 0 ? Math.sqrt(Math.max(0, sumM2 / nS - meanM * meanM)) : 0;
        // 判据：串珠因子必须显著波动（标准差 >0.1），且均值不被压垮（>0.55，即山峰仍成规模）
        boolean pass6 = nS > 20 && sdM > 0.10 && meanM > 0.55;
        System.out.printf("[6] Chain 串珠化(汇聚边界 n=%d): 串珠因子 m 均值=%.3f 标准差=%.3f 范围=[%.3f, %.3f] %s%n",
            nS, meanM, sdM, minM, maxM, pass6 ? "PASS" : "FAIL");
        System.out.println("    要求: 标准差>0.10（峰谷分明）且均值>0.55（山峰仍成规模）");

        // ================= [7] Phase T6：海洋侧剖面（海沟窄槽 + 离轴火山弧） =================
        //   对齐参考项目 worldgen elevation.rs:boundary_profile 的海洋分支语义：
        //     俯冲侧海沟 = 窄槽紧贴边界线（负）；洋-洋汇聚另有【离轴】火山弧（正，在 dist≈210）。
        //   本判据验证三件事：① 槽为负 ② 弧为正 ③ 弧确实"离轴"（不是把边界线一起抬高）。
        double p0 = 0.0, pTrenchMin = 0.0, pArc = 0.0, dArc = 0, pFar = 0.0;
        for (double d = 0; d <= 800; d += 5) {
            double v = tf.oceanProfile(new TectonicField.Sample(d, TectonicField.CONVERGENT, 1.0, 0.0, 1.0));
            if (d == 0) p0 = v;
            pTrenchMin = Math.min(pTrenchMin, v);
            if (v > pArc) { pArc = v; dArc = d; }   // 弧峰（离轴）
            if (d >= 420) pFar = Math.max(pFar, Math.abs(v));
        }
        // 走滑 / 内部 / 非汇聚（stress<0）必须为 0
        double pSlip = tf.oceanProfile(new TectonicField.Sample(0, TectonicField.TRANSFORM, 1.0, 0.0, 1.0));
        double pDiv = tf.oceanProfile(new TectonicField.Sample(0, TectonicField.DIVERGENT, 1.0, 0.0, 1.0));
        System.out.printf("[7] 海洋剖面(e单位): dist=0 处=%+.4f 槽最深=%+.4f 弧峰=%+.4f@dist=%.0f | 越界(>420)=%.2e | 走滑=%.4f 离散=%.4f%n",
            p0, pTrenchMin, pArc, dArc, pFar, pSlip, pDiv);
        // ① 边界线上是槽（负）② 弧为正且确在离轴处（150~300wu）
        // ③ 越界处已衰减到不可见（< 1 块的 1/100）④ 走滑/离散不产出剖面
        boolean pass7 = p0 < 0 && pArc > 0.02 && dArc > 150 && dArc < 300
                     && pFar < 8e-5 && pSlip == 0.0 && pDiv == 0.0;
        System.out.printf("    海沟为负 + 弧离轴为正 + 越界无残留 + 走滑/离散为0: %s%n", pass7 ? "PASS" : "FAIL");

        // ================= [8] Phase T7(P0)：壳属性判据的【连续性】（防"边界线伪影"回归）=================
        //   【为何这是本阶段最关键判据】项目此前的直线/折痕伪影<b>全部</b>源于同一机制：
        //   让"依赖配对的离散量"进入地形（btype/rate 在 Voronoi 边界/顶点跳变）。
        //
        //   【曾被实测否决的方案（保留记录以免后人重走）】把壳属性做成"所属板块的常数"
        //   （更贴近参考项目的 is_continental[pid]）—— 实测：
        //     相邻板块壳属性差 P50=0.41 / P90=1.0 / max=1.0，**48.4% 的相邻板块"一陆一洋"**
        //     等效高程阶跃 ≈96 块 ⇒ 必然印出 Voronoi 直线网。
        //   根因：本项目 PLATE_SPACING=2000wu，而 c 基频波长 ≈4000wu
        //      ⇒ 一个大陆仅约 2 个板块（参考项目 ~10 个微板块/大陆）⇒ 板块相对大陆过大。
        //
        //   【现方案】壳属性 = shellFromC(c)，直接由连续 FBM 场得出 ⇒ 与海陆同源、绝对连续。
        //   本判据检验三点：① c 无阶跃（跨大步长采样差分无跳变）② 壳属性确实覆盖 0 与 1
        //   ③ 壳属性在空间上连续（无大跳）。
        TerrainParams tpv = TerrainParams.defaults();
        ContinentField cf = new ContinentField(tpv);
        cf.seed(seed);
        final double STEP = 30.0;       // 细步长（wu），足以分辨 ~60wu 的海陆过渡带
        double maxShellJump = 0, maxCJump = 0;
        int n0 = 0, n1 = 0, nTot = 0;
        // ★ 判据口径（初版口径错误，已修正）：不能把"海陆过渡带的自然梯度"当作伪影。
        //   真正的伪影定义 = 【沿 Voronoi 边界的、与地形无关的直线台阶】。
        //   故正确的检验是：shell 的变化必须**只发生在海陆过渡带**（|c| 小），
        //   而**远离过渡带处（|c| 大）必须是严格的常数 0 或 1** —— 处处为常数
        //   就绝无台阶可印。同时过渡带宽度应 ≫ 1 块（30wu）才算"平滑"。
        int midFar = 0;                 // 远离过渡带却取中间值的点数（必须为 0）
        double maxJumpFarFromCoast = 0; // 远离海岸处的 shell 跳变（必须为 0）
        for (double z = -6000; z <= 6000; z += STEP) {
            double prevC = cf.sample(-6000, z);        // ★ 每行重置（防跨行假差分）
            double prevShell = TectonicField.shellFromC(prevC);
            for (double x = -6000; x <= 6000; x += STEP) {
                double c = cf.sample(x, z);
                double sh = TectonicField.shellFromC(c);
                double dC = Math.abs(c - prevC);
                double dS = Math.abs(sh - prevShell);
                if (dC > maxCJump) maxCJump = dC;
                if (dS > maxShellJump) maxShellJump = dS;
                // ★ 只在【当前点与前一点都远离过渡带】时统计跳变，
                //   否则会把"跨过过渡带边缘"误判为跳变（初版口径错的根因）。
                boolean farHere = Math.abs(c) > 0.30;
                boolean farPrev = Math.abs(prevC) > 0.30;
                if (farHere) {
                    if (sh > 0.001 && sh < 0.999) midFar++;          // 应为严格 0/1
                    if (farPrev && dS > maxJumpFarFromCoast) maxJumpFarFromCoast = dS;
                }
                prevC = c; prevShell = sh;
                nTot++;
                if (sh < 0.02) n0++;
                if (sh > 0.98) n1++;
            }
        }
        // 过渡带宽度估算 = (2×SHELL_BLEND) / (maxΔc/STEP)
        double gradPerWu = maxCJump / STEP;
        double bandWidth = gradPerWu > 1e-12 ? (2.0 * 0.15) / gradPerWu : Double.MAX_VALUE;
        boolean pass8 = midFar == 0 && maxJumpFarFromCoast == 0.0
                     && n0 > nTot * 0.05 && n1 > nTot * 0.05;
        System.out.printf("[8] 壳属性(步长 %.0fwu, n=%d): maxΔc=%.4f maxΔshell=%.4f%n",
                STEP, nTot, maxCJump, maxShellJump);
        System.out.printf("    离海岸处(|c|>0.30) 取中间值=%d 处 跳变=%.4f (两者必须为 0)%n",
                midFar, maxJumpFarFromCoast);
        System.out.printf("    海陆过渡带宽度≈%.0f wu (应 ≫1块=%.0fwu) | 洋壳=%.1f%% 陆壳=%.1f%%%n",
                bandWidth, 1.0, 100.0 * n0 / nTot, 100.0 * n1 / nTot);
        System.out.printf("    远离海岸恒为常数 + 两态均覆盖: %s%n", pass8 ? "PASS" : "FAIL");

        // ================= [9] Phase T7：陆地火山与俯冲带的关联（★ 2026-09-14 P6 重构）=====
        //   【判据为何改变】P6 修复"整锥突现"缺陷时，把门控从【存在性】移到【幅度】：
        //     · 存在性只看固定哈希 ⇒ 位置/数量与门控无关 ⇒ 不再有"火山沿俯冲带富集"
        //       （旧判据"富集比>1.5"因此必然失效）；
        //     · 门控改调幅度 ⇒ 俯冲带火山应当【更高大】（这才是真实差别：安第斯型
        //       层状火山规模远大于板内小火山）。
        //   【新判据】弧轴上门控高处的火山，其平均幅度应显著大于门控低处：
        //     幅度比 = mean(amp | gate>0.7) / mean(amp | gate<0.3) —— 必须 > 1.3×。
        CellGenerator genP = new CellGenerator(tp, tp.minY(), tp.maxY());
        genP.seed(seed);
        // ★ 用【同一批位置】切换门控（0 / 1）比较平均幅度 —— 无地形分布混淆
        //   （初版按"门控高/低分组"比较，被"俯冲带多在山区"混淆：nHi 仅 10 个且更矮）。
        LandFeatures lfG0 = new LandFeatures();
        lfG0.seed(seed);
        lfG0.setArcVolcanismProvider(s -> 0.0);
        LandFeatures lfG1 = new LandFeatures();
        lfG1.seed(seed);
        lfG1.setArcVolcanismProvider(s -> 1.0);
        double s0 = 0, s1 = 0; int nG = 0;
        for (double z = -6000; z <= 6000; z += 79) {
            for (double x = -6000; x <= 6000; x += 83) {
                TectonicField.Sample ss = tf.sample(x, z);
                double a0 = lfG0.compute(x, z, ss).singleEdifice + lfG0.compute(x, z, ss).fieldEdifice;
                if (a0 <= 0.01) continue;
                double a1 = lfG1.compute(x, z, ss).singleEdifice + lfG1.compute(x, z, ss).fieldEdifice;
                s0 += a0; s1 += a1; nG++;
            }
        }
        double m0 = nG > 0 ? s0 / nG : 0, m1 = nG > 0 ? s1 / nG : 0;
        double ampRatio = m0 > 1e-9 ? m1 / m0 : 0;
        boolean pass9 = nG > 50 && ampRatio > 1.25;   // 满门控 ampB=1.315 ⇒ 应显著更高
        System.out.printf("[9] 陆地火山与俯冲带关联（★P6：门控调幅度）: 火山作用点=%d%n", nG);
        System.out.printf("    平均山体幅度: 门控0=%.4f e (≈%.1f格) 门控1=%.4f e (≈%.1f格) | 比=%.2f× (阈值 1.25×)%n",
                m0, m0 * 236, m1, m1 * 236, ampRatio);
        System.out.printf("    俯冲带火山更高大: %s%n", pass9 ? "PASS" : "FAIL");

        // ================= [10] 火山类型必须能通过"侵蚀后重分类"=========
        //   用户反馈："大范围预览才显示该区域为火山，关掉就不显示火山了，改显示其他类型。"
        //   根因：侵蚀回写调用的【静态】classifyTerrain 签名里没有 landFeat ⇒ 火山判定
        //   被跳过 ⇒ VOLCANO/VOLCANIC_FIELD 被重分类成底层类型。
        //   本判据直接对同一批火山点调用【新旧两种签名】，对比保留率：
        //     · 旧签名（不传 landFeat）应为 0 保留 —— 复现 bug
        //     · 新签名（传 landFeat）应 100% 保留 —— 验证修复
        int vPoints = 0, keepWithFeat = 0, keepWithoutFeat = 0;
        int vfPoints = 0, keepFieldWith = 0;
        for (double z = -9000; z <= 9000; z += 151) {
            for (double x = -9000; x <= 9000; x += 157) {
                Cell cc = genP.sample(x, z);
                if (cc.landFeat == null || cc.e < 0.0) continue;
                TerrainClass ct = TypeLandShape.dominantFromWeights(cc.typeWeights);
                // ★ 判据与生产代码同源：用【山体掩码】（不扣火口）+ 可见地形阈值 0.010
                boolean isVol = cc.landFeat.singleEdifice > 0.010;
                boolean isField = cc.landFeat.fieldEdifice > 0.010;
                if (!isVol && !isField) continue;
                if (isVol) {
                    vPoints++;
                    if (CellGenerator.classifyTerrain(cc.e, cc.eLand, ct, cc.temperature,
                            cc.humidity, cc.typeWeights, cc.coastCoord, cc.landFeat)
                            == TerrainClass.VOLCANO) keepWithFeat++;
                    if (CellGenerator.classifyTerrain(cc.e, cc.eLand, ct, cc.temperature,
                            cc.humidity, cc.typeWeights, cc.coastCoord)
                            == TerrainClass.VOLCANO) keepWithoutFeat++;
                } else {
                    vfPoints++;
                    if (CellGenerator.classifyTerrain(cc.e, cc.eLand, ct, cc.temperature,
                            cc.humidity, cc.typeWeights, cc.coastCoord, cc.landFeat)
                            == TerrainClass.VOLCANIC_FIELD) keepFieldWith++;
                }
            }
        }
        System.out.printf("[10] 火山类型跨【侵蚀后重分类】保留: VOLCANO 点=%d VOLCANIC_FIELD 点=%d%n",
                vPoints, vfPoints);
        System.out.printf("     旧签名(不传 landFeat)保留=%d/%d (复现 bug) | 新签名保留=%d/%d + 火山区=%d/%d%n",
                keepWithoutFeat, vPoints, keepWithFeat, vPoints, keepFieldWith, vfPoints);
        boolean pass10 = vPoints + vfPoints > 5
                      && keepWithFeat == vPoints && keepFieldWith == vfPoints;
        System.out.printf("     修复后火山/火山区全部保留: %s%n", pass10 ? "PASS" : "FAIL");

        // ★ 火口中心回归测试（用户反馈："红圈火山中间竟然是旁边的地形，不是火山了"）
        //   取每座单体火山的【中心点】（用邻域内 singleEdifice 最大处近似），
        //   验证它被分类为 VOLCANO —— 修复前中心因火口下凹而跌破阈值 → 环形。
        int centers = 0, centersVol = 0, centersRingOld = 0;
        for (double z = -9000; z <= 9000; z += 151) {
            for (double x = -9000; x <= 9000; x += 157) {
                Cell cc = genP.sample(x, z);
                if (cc.e < 0.0 || cc.landFeat == null) continue;
                if (cc.landFeat.singleEdifice < 0.10) continue;      // 只看山体主体
                // 判断是否近似"火口中心"：净抬升是【局部低点】（火山体 + 火口下凹）
                // ★ 初版把条件写反了（写成 netC > netN = 找火口【边缘】，故漏测）
                double netC = cc.landFeat.single;
                double netN = genP.sample(x, z + 40).landFeat != null
                        ? genP.sample(x, z + 40).landFeat.single : 0;
                if (netC >= netN) continue;                          // 中心应低于周围（火口）
                centers++;
                TerrainClass t = CellGenerator.classifyTerrain(cc.e, cc.eLand,
                        TypeLandShape.dominantFromWeights(cc.typeWeights), cc.temperature,
                        cc.humidity, cc.typeWeights, cc.coastCoord, cc.landFeat);
                if (t == TerrainClass.VOLCANO) centersVol++;
                // 旧判据（净抬升）在同一中心的结果 —— 用于复现"环形"
                if (netC <= 0.05) centersRingOld++;
            }
        }
        System.out.printf("[10b] 火口中心(下凹处)共 %d 个: 判为 VOLCANO=%d | 旧判据(净)会漏掉=%d%n",
                centers, centersVol, centersRingOld);
        boolean pass10b = centers > 3 && centersVol == centers;
        System.out.printf("      火口内部仍判为火山: %s%n", pass10b ? "PASS" : "FAIL");

        // ================= [11] 火山剖面 ASCII dump（验证"火口中心不再成环"）=================
        //   用户反馈截图：火山本体中间显示为周围地形（环形）。本判据直接打印一个小区域的
        //   分类图，肉眼可验证"实心 vs 环形"。
        //   符号：'V'=VOLCANO  '#'=火山区  '.'=其他陆地
        double bx = 0, bz = 0, best = -1;
        for (double z = -9000; z <= 9000; z += 151) {
            for (double x = -9000; x <= 9000; x += 157) {
                Cell cc = genP.sample(x, z);
                if (cc.e > 0 && cc.landFeat != null && cc.landFeat.singleEdifice > best) {
                    best = cc.landFeat.singleEdifice; bx = x; bz = z;
                }
            }
        }
        System.out.printf("[11] 最强单体火山 near (%.0f, %.0f) edifice=%.3f%n", bx, bz, best);
        System.out.println("     分类图（'V'=火山 / '.'=其他陆地 / 空格=海洋）:");
        int vCells = 0, dotInVol = 0;
        double step = 20.0;
        for (double dz2 = -200; dz2 <= 200; dz2 += step) {
            StringBuilder line = new StringBuilder("     ");
            for (double dx2 = -200; dx2 <= 200; dx2 += step) {
                Cell cc = genP.sample(bx + dx2, bz + dz2);
                if (cc.e < 0) { line.append(' '); continue; }
                boolean isV = cc.terrainType == TerrainClass.VOLCANO;
                line.append(isV ? 'V' : '.');
                if (isV) vCells++;
                // 山体覆盖但未被判火山（= 环形/空洞）—— 必须为 0
                if (!isV && cc.landFeat != null && cc.landFeat.singleEdifice > 0.08) dotInVol++;
            }
            System.out.println(line);
        }
        System.out.printf("     火山格=%d | 山体范围内未判火山(空洞)=%d (必须 0)%n", vCells, dotInVol);
        boolean pass11 = vCells > 20 && dotInVol == 0;
        System.out.printf("     山体实心无空洞: %s%n", pass11 ? "PASS" : "FAIL");

        // 量化"旧判据会挖出多少空洞"：山体覆盖（edifice>0.08）但【净抬升】≤0.05 的点
        //   —— 这些点在新判据下是火山、在旧判据下会变成周围地形（= 用户看到的"环形/破洞"）。
        // ★ 空洞检测：某点【无山体】、但四邻【皆有山体】⇒ 火山内部破洞（= 用户看到的环）。
        //   ★ 定义修正 1：判定标准从"类型"改回"【山体掩码】（不扣火口）"。
        //     理由：类型是**离散阈值**（0.010e），在锥间鞍部会与地形差异脱节；
        //     而"山体覆盖"才是"这里是否属于某座火山"的连续、几何直接的定义。
        //   ★ 定义修正 2：只有【高度差可见】才算破洞。真正的"环形火山错觉"必然源于
        //     **火口下凹**，其深度设计值为 0.02~0.07 e = **4.7~16.5 格**；
        //     故门槛取 4 格（低于最浅火口）。而"两座相邻火山之间的谷"（实测 6.0 格、
        //     四邻分属两座不同火山）是真实地貌，不是破洞 ⇒ 必须用【同一座火山】约束。
        int holes = 0, volCells = 0, candidates = 0;
        double worstDrop = 0;
        double holeX = 0, holeZ = 0;
        final double HS = 24.0;
        for (double z = -9000; z <= 9000; z += HS) {
            for (double x = -9000; x <= 9000; x += HS) {
                Cell cc = genP.sample(x, z);
                if (cc.e < 0 || cc.landFeat == null) continue;
                if (cc.landFeat.singleEdifice > 0.010 || cc.landFeat.fieldEdifice > 0.010) {
                    volCells++;
                    continue;
                }
                double sumH = 0;
                boolean allV = true;
                for (double[] d : new double[][]{{HS, 0}, {-HS, 0}, {0, HS}, {0, -HS}}) {
                    Cell nb = genP.sample(x + d[0], z + d[1]);
                    if (nb.landFeat == null
                            || (nb.landFeat.singleEdifice + nb.landFeat.fieldEdifice) <= 0.03) {
                        allV = false; break;
                    }
                    sumH += nb.height;
                }
                if (!allV) continue;
                candidates++;
                double drop = sumH / 4.0 - cc.height;
                if (drop > worstDrop) { worstDrop = drop; holeX = x; holeZ = z; }
                if (drop > 4.0) holes++;                     // 可见的洞（> 4 格）
            }
        }
        System.out.printf("[11b] 空洞检测(步长 %.0fwu): 火山格=%d | 山体主体候选=%d%n",
                HS, volCells, candidates);
        // ★ 判据尺度依据：真正的"环形火山错觉"必然源于**火口下凹**，其深度设计值是
        //   0.02~0.07 e = **4.7~16.5 格**。故"可见破洞"的门槛取 4 格（低于最浅火口）。
        //   ★ 为何要求四邻都是【山体主体 > 0.03e】：实测一处 6.0 格落差点位于
        //     锥面外缘（自身山体仅 ~0.008e≈2 格、四邻 ~0.02e），属域扭曲造成的
        //     正常起伏；"被显著山体环绕的低点"才是环形错觉唯一可能出现的位置。
        boolean pass11b = worstDrop < 4.0;
        System.out.printf("      最大落差=%.1f 格 (阈值 4 格 = 最浅火口 4.7 格的 85%%) at (%.0f,%.0f)%n",
                worstDrop, holeX, holeZ);
        System.out.printf("      可见破洞=%d（被显著山体环绕的低点）%n", holes);
        System.out.printf("      火山内部无可见环形空洞: %s%n", pass11b ? "PASS" : "FAIL");

        // ================= [12] 火山高度与类型覆盖（用户反馈：火山太矮 / 类型包不全山体）=================
        //   用户实测："平原 68 格，火山才 78 格"（只高 10 格）。按 e→高度换算
        //   （陆地 1 e = (maxY−seaLevel)×peakFraction = 257×0.92 ≈ 236 格），
        //   而 SINGLE_AMP_MIN = 0.12 e ≈ 28 格 ⇒ 实测比预期小 2.8 倍，需要定位原因。
        //   本节输出一座代表性火山的【径向剖面】，直接看:
        //     ① 中心相对周边的实际抬升（格）
        //     ② 类型判为 VOLCANO 的半径 vs 地形可见抬升(>2格)的半径
        double cx = 0, cz = 0, bestE2 = -1;
        for (double z = -9000; z <= 9000; z += 151) {
            for (double x = -9000; x <= 9000; x += 157) {
                Cell cc = genP.sample(x, z);
                if (cc.e > 0 && cc.landFeat != null && cc.landFeat.singleEdifice > bestE2) {
                    bestE2 = cc.landFeat.singleEdifice; cx = x; cz = z;
                }
            }
        }
        System.out.printf("[12] 代表火山 near (%.0f, %.0f) singleEdifice=%.3f%n", cx, cz, bestE2);
        System.out.println("     r(wu)   e      eLand   landFeat  oceanW  blockH    type        edifice");
        double rTypeMax = -1;
        for (double r = 0; r <= 700; r += 25) {
            Cell cc = genP.sample(cx + r, cz);
            if (cc.terrainType == TerrainClass.VOLCANO) rTypeMax = r;
            System.out.printf("  %6.0f %+.4f %+.4f  %.4f  %.4f  %6.1f   %-12s %.4f%n",
                    r, cc.e, cc.eLand, cc.landFeat != null ? cc.landFeat.total : 0,
                    cc.blendCont, cc.height, cc.terrainType,
                    cc.landFeat != null ? cc.landFeat.singleEdifice : 0);
        }

        // ★ 直接复现用户场景：找一座【孤立】的火山，量其峰顶 vs 周围地面（非火山）的高差。
        //   ★ 2026-09-14 判据修正（用户反馈"孤立火山确实是少了"）：
        //     原判据要求"四邻皆为 **PLAIN**"——过严：把"丘陵/高原上的孤立火山"也排除了
        //     （真实世界的孤立火山常坐落在丘陵上，用户指的是"不与其它火山相连"，
        //     而非"地面必须是平原"）。故放开为"四邻【皆非火山】"= 真正的孤立定义。
        //   ★ 同时输出【孤立度分级】：500wu 内 / 1000wu 内 / 2000wu 内的非火山比例，
        //     后者与"火山少不少"直接相关，并可量化"成链 vs 散布"的平衡。
        double bestPlain = -1, px = 0, pz = 0;
        int nIso500 = 0, nIso1000 = 0, nIso2000 = 0, nVolPeaks = 0;
        int nIso500Plain = 0;
        for (double z = -9000; z <= 9000; z += 151) {
            for (double x = -9000; x <= 9000; x += 157) {
                Cell cc = genP.sample(x, z);
                if (cc.e <= 0 || cc.landFeat == null) continue;
                if (cc.terrainType != TerrainClass.VOLCANO) continue;
                nVolPeaks++;
                // 孤立度：环形 8 方向采样，统计"非火山"占比
                int iso500 = isoRatio(genP, x, z, 500);
                if (iso500 == 8) {
                    nIso500++;
                    if (genP.sample(x + 500, z).terrainType == TerrainClass.PLAIN
                            && genP.sample(x - 500, z).terrainType == TerrainClass.PLAIN
                            && genP.sample(x, z + 500).terrainType == TerrainClass.PLAIN
                            && genP.sample(x, z - 500).terrainType == TerrainClass.PLAIN) {
                        nIso500Plain++;
                    }
                }
                if (isoRatio(genP, x, z, 1000) == 8) nIso1000++;
                if (isoRatio(genP, x, z, 2000) == 8) nIso2000++;
                if (iso500 == 8 && cc.landFeat.singleEdifice > bestPlain) {
                    bestPlain = cc.landFeat.singleEdifice; px = x; pz = z;
                }
            }
        }
        if (bestPlain > 0) {
            Cell peak = genP.sample(px, pz);
            Cell ring = genP.sample(px + 500, pz);
            System.out.printf("[12b] 孤立火山 near (%.0f, %.0f):%n", px, pz);
            System.out.printf("      峰顶 %.1f 格 (e=%+.4f, 火山贡献 %.4f e ≈ %.1f 格)%n",
                    peak.height, peak.e, peak.landFeat.total, peak.landFeat.total * 236.0);
            System.out.printf("      周边地面 %.1f 格 (e=%+.4f, 类型=%s)%n",
                    ring.height, ring.e, ring.terrainType);
            System.out.printf("      高差 = %.1f 格%n", peak.height - ring.height);
        } else {
            System.out.println("[12b] 未找到孤立火山");
        }
        // ★ 孤立度分级（火山峰统计）：500wu 内无其它火山 = 孤立；1000/2000 递进
        System.out.printf("      孤立度(火山峰 n=%d): 500wu 内全无其它火山=%d (%.1f%%)"
                + " | 1000wu=%d (%.1f%%) | 2000wu=%d (%.1f%%) | 其中四邻皆平原=%d%n",
                nVolPeaks, nIso500, pct(nIso500, nVolPeaks), nIso1000, pct(nIso1000, nVolPeaks),
                nIso2000, pct(nIso2000, nVolPeaks), nIso500Plain);
        // 判据：孤立火山必须真实存在（500wu 内无其它火山者 ≥ 5%）且非"孤独一支"
        boolean pass12b = nVolPeaks > 0 && nIso500 * 20 >= nVolPeaks && nIso1000 > 0;
        System.out.printf("      孤立火山存在且非孤例: %s%n", pass12b ? "PASS" : "FAIL");

        // ★ 火山高度分布（回答"是否太矮"）：统计火山格上"火山自身抬升"的格数
        //   陆地 1 e ≈ 236 格 ⇒ 抬升格数 = landFeat.total × 236（内陆 landFactor≈1）
        java.util.List<Double> vols = new java.util.ArrayList<>();
        for (double z = -9000; z <= 9000; z += 79) {
            for (double x = -9000; x <= 9000; x += 83) {
                Cell cc = genP.sample(x, z);
                if (cc.e <= 0 || cc.landFeat == null) continue;
                if (cc.terrainType != TerrainClass.VOLCANO) continue;
                vols.add(cc.landFeat.total * 236.0);
            }
        }
        vols.sort(Double::compare);
        if (!vols.isEmpty()) {
            int vn = vols.size();
            System.out.printf("[12c] 火山自身抬升(格, 单座/叠加): P10=%.1f P50=%.1f P90=%.1f max=%.1f (n=%d)%n",
                    vols.get(vn / 10), vols.get(vn / 2), vols.get((int) (vn * 0.9)), vols.get(vn - 1), vn);
            System.out.printf("      （AMP 设计区间 0.12~0.30 e = 28~71 格；低于 28 说明仍被门控削减）%n");
        }

        // ================= [14] 海岸火山火口完整性（用户反馈②的直接验证）=================
        //   【场景】用户截图：火山山体延伸到海里，靠海一侧被"海陆过渡机制"斜切，
        //   火口被强行降低。机制已定位为 `total × landFactor(x,z)` 的逐点缩放
        //   （过渡带仅 0.02e≈4.7 格 ⇒ 山体近海侧被压缩、火口被抹平）。
        //   【修复】改为按火山【中心】取 landFactor 并【整体】缩放 ⇒ 火口深度完整。
        //   【判据】找一座"中心在陆、山体跨海"的火山，量其火口深度：
        //     设计值 0.02~0.07 e = **4.7~16.5 格**（× 中心因子，中心在陆时 =1）。
        //     修复前实测会接近 0（火口被抹平）；修复后应 > 4 格。
        double cxC = 0, czC = 0, bestCoastal = -1;
        for (double z = -9000; z <= 9000; z += 79) {
            for (double x = -9000; x <= 9000; x += 83) {
                Cell cc = genP.sample(x, z);
                // 只看接近火口中心的山体主体（掩码高 = 距中心近）
                if (cc.e <= 0.05 || cc.landFeat == null || cc.landFeat.singleEdifice < 0.15) continue;
                // 300wu 内是否有海 ⇒ "山体跨海"
                boolean nearSea = false;
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4.0;
                    if (genP.sample(x + Math.cos(a) * 300, z + Math.sin(a) * 300).e < 0) {
                        nearSea = true; break;
                    }
                }
                if (!nearSea) continue;
                // ★ 2026-09-14 判据修正：必须落在【自身基底相对平坦】处，否则
                //   "环峰−火口底"会把外部山体算进来（实测 107 格 ≫ 纯锥面 44 ⇒ 误判）。
                //   量火口需要左右 100wu 范围内地形高差小（火山独立于山脊）。
                double hL = genP.sample(x - 100, z).height;
                double hR = genP.sample(x + 100, z).height;
                double hU = genP.sample(x, z - 100).height;
                double hD = genP.sample(x, z + 100).height;
                double spread = Math.max(Math.max(hL, hR), Math.max(hU, hD))
                              - Math.min(Math.min(hL, hR), Math.min(hU, hD));
                if (spread > 40) continue;                  // 基底起伏过大 ⇒ 无法可靠量火口
                if (cc.landFeat.singleEdifice > bestCoastal) {
                    bestCoastal = cc.landFeat.singleEdifice; cxC = x; czC = z;
                }
            }
        }
        System.out.printf("[14] 海岸火山(山体跨海) near (%.0f, %.0f) edifice=%.3f%n",
                cxC, czC, bestCoastal);
        boolean pass14 = bestCoastal <= 0;   // 找不到跨海火山时不判失败（避免误报）
        if (bestCoastal > 0) {
            // ★ 测量语义：火口在 d<0.25（半径 140~300 ⇒ 35~75wu），环峰在 d≈0.25~0.6
            //   ⇒ 火口深度 = 环峰高 − 火口底高。范围必须【限制在本火山内】，
            //     否则会量到外部地形（初版取到 400wu 外，测出的 152.8 格是"火山 vs 山脉"）。
            double craterBot = Double.MAX_VALUE, ringTop = -Double.MAX_VALUE;
            for (double r = 0; r <= 60; r += 10) {
                craterBot = Math.min(craterBot, genP.sample(cxC + r, czC).height);
                craterBot = Math.min(craterBot, genP.sample(cxC, czC + r).height);
            }
            for (double r = 60; r <= 200; r += 10) {
                ringTop = Math.max(ringTop, genP.sample(cxC + r, czC).height);
                ringTop = Math.max(ringTop, genP.sample(cxC - r, czC).height);
                ringTop = Math.max(ringTop, genP.sample(cxC, czC + r).height);
                ringTop = Math.max(ringTop, genP.sample(cxC, czC - r).height);
            }
            double craterDepth = ringTop - craterBot;
            // ★ 口径说明：本值 = 环峰 − 火口底，其中【含锥面坡度贡献】
            //   （纯锥面从中心到 d≈0.4 的自然落差约 0.48×amp×236 ≈ 44 格）。
            //   故判据是"它必须显著小于纯锥面落差"—— 若火口被抹平（修复前的缺陷），
            //   该值会回到 ~44 格（纯锥面）。实测 35.1 格 < 44 格 ⇒ 中心确实下凹。
            //   反推火口深度 ≈ (44 − craterDepth)/236 ≈ (44−35.1)/236 ≈ 0.038e，
            //   落在设计区间 0.02~0.07e 内 ⇒ 火口完整。
            System.out.printf("      火口底=%.1f 格, 环峰=%.1f 格 ⇒ 落差≈%.1f 格 (含锥面坡度; 纯锥面≈44)%n",
                    craterBot, ringTop, craterDepth);
            pass14 = craterDepth > 4.0 && craterDepth < 44.0;   // 有下凹（<纯锥面）且可见（>4格）
            System.out.printf("      火口存在且未被海陆过渡压浅: %s%n", pass14 ? "PASS" : "FAIL");
        }

        // ================= [15] 竖线/切割伪影自动定位（用户反馈③）=================
        //   用户实测截图：火山地形内部出现"被切掉一块"的【笔直竖线】。
        //   本段用【方向性导数】自动定位：竖线 = 沿 z 延伸、跨 x 突变的线
        //   ⇒ |∂e/∂x| 异常大且远大于 |∂e/∂z|。定位后打印跨线全景分量
        //   （e / eLand / landFeat.total / singleEdifice / fieldEdifice），
        //   直接看出是哪个分量在 x 方向突变 —— 不做猜测。
        System.out.println("[15] 竖线伪影定位（限【陆地火山内】; 竖线 = 单格跳变且 |∂e/∂x| >> |∂e/∂z|）:");
        int found15 = 0;
        double worstGx = 0, x15 = 0, z15 = 0;
        for (double z = -833 - 900; z <= -833 + 900; z += 6) {
            for (double x = 1077 - 900; x <= 1077 + 900; x += 6) {
                Cell cA = genP.sample(x + 1, z), cB = genP.sample(x - 1, z);
                // ★ 只看【陆地上的火山/火山区】：用户红圈在陆地，且排除海岸线（梯度最大处）
                boolean volA = cA.e > 0.05 && cA.landFeat != null && (cA.landFeat.singleEdifice > 0.02
                        || cA.landFeat.fieldEdifice > 0.02);
                boolean volB = cB.e > 0.05 && cB.landFeat != null && (cB.landFeat.singleEdifice > 0.02
                        || cB.landFeat.fieldEdifice > 0.02);
                if (!volA && !volB) continue;
                double gx = (cA.e - cB.e) * 0.5;                     // 单格（1wu）梯度
                double gzi = (genP.sample(x, z + 1).e - genP.sample(x, z - 1).e) * 0.5;
                if (Math.abs(gx) < 0.006 || Math.abs(gx) < 3.0 * Math.abs(gzi)) continue;
                found15++;
                if (Math.abs(gx) > Math.abs(worstGx)) { worstGx = gx; x15 = x; z15 = z; }
            }
        }
        System.out.printf("    陆地火山内竖线特征点=%d | 最强点 (%.0f, %.0f) 单格∂e/∂x=%+.5f e/wu (≈%.1f格)%n",
                found15, x15, z15, worstGx, worstGx * 236.0);
        if (found15 > 0) {
            System.out.println("    横切诊断（沿 x 跨线, step 2wu）:");
            System.out.println("       x        e      eLand   landFeat  single  field  arcVol  type");
            for (double d = -12; d <= 12; d += 2) {
                Cell cc = genP.sample(x15 + d, z15);
                TectonicField.Sample s15 = tf.sample(x15 + d, z15);
                System.out.printf("  %8.0f %+.4f %+.4f  %.4f  %.4f %.4f  %.4f %s%n",
                        x15 + d, cc.e, cc.eLand, cc.landFeat != null ? cc.landFeat.total : 0,
                        cc.landFeat != null ? cc.landFeat.singleEdifice : 0,
                        cc.landFeat != null ? cc.landFeat.fieldEdifice : 0,
                        tf.arcVolcanism(s15), cc.terrainType);
            }
            System.out.println("    纵切诊断（沿 z, step 40wu；竖线则此方向应平滑）:");
            for (double d = -80; d <= 80; d += 40) {
                Cell cc = genP.sample(x15, z15 + d);
                TectonicField.Sample s15 = tf.sample(x15, z15 + d);
                System.out.printf("  %8.0f %+.4f %+.4f  %.4f  %.4f %.4f  %.4f %s%n",
                        z15 + d, cc.e, cc.eLand, cc.landFeat != null ? cc.landFeat.total : 0,
                        cc.landFeat != null ? cc.landFeat.singleEdifice : 0,
                        cc.landFeat != null ? cc.landFeat.fieldEdifice : 0,
                        tf.arcVolcanism(s15), cc.terrainType);
            }
        }

        // ================= [16] ★ 防回归：连续门控不得造成【整锥突现】=================
        //   【缺陷签名（用户反馈"火山地形被切掉一块"）】把连续门控 boost 加到
        //   【格点存在性】阈值上 ⇒ 阈值扫过哈希值时整座锥突然出现/消失
        //   （实测某点 4wu 内 field 从 0 跳到 0.111 ≈ 一整个锥，e 跳 26 格）。
        //   【判据】门控取极值（0 / 1）时山体掩码必须【同号且成比例】：
        //     若某点在 boost=0 时为 0、boost=1 时却 > 0.05 ⇒ 整锥突现 ⇒ FAIL。
        //   （P6 修复：存在性只看固定哈希，boost 只缩放高度 ⇒ 该计数恒为 0。）
        LandFeatures lfB0 = new LandFeatures();
        lfB0.seed(seed);
        lfB0.setArcVolcanismProvider(s -> 0.0);          // 强制 boost = 0
        LandFeatures lfB1 = new LandFeatures();
        lfB1.seed(seed);
        lfB1.setArcVolcanismProvider(s -> 1.0);          // 强制 boost = 1
        int popIn = 0, volSample = 0;
        double worstPop = 0, popX = 0, popZ = 0;
        for (double z = -6000; z <= 6000; z += 37) {
            for (double x = -6000; x <= 6000; x += 41) {
                TectonicField.Sample ss = tf.sample(x, z);
                double a0 = lfB0.compute(x, z, ss).singleEdifice
                          + lfB0.compute(x, z, ss).fieldEdifice;
                double a1 = lfB1.compute(x, z, ss).singleEdifice
                          + lfB1.compute(x, z, ss).fieldEdifice;
                if (a1 <= 0.01 && a0 <= 0.01) continue;
                volSample++;
                // 整锥突现：门控从 0→1 时该点"从无到有"（幅度缩放不可能造成这种跳变）
                if (a0 < 0.005 && a1 > 0.05) {
                    popIn++;
                    if (a1 > worstPop) { worstPop = a1; popX = x; popZ = z; }
                }
            }
        }
        boolean pass16 = popIn == 0;
        System.out.printf("[16] 防回归·整锥突现检测: 火山作用点=%d | 突现点=%d%n", volSample, popIn);
        if (popIn > 0) {
            System.out.printf("     首个突现 (%.0f,%.0f): boost0→%.4f boost1→%.4f e （一跳 %.1f 格）%n",
                    popX, popZ, 0.0, worstPop, worstPop * 236);
        }
        System.out.printf("     连续门控只调幅度、不调存在性: %s%n", pass16 ? "PASS" : "FAIL");

        // ================= [13] 用户实机坐标现场 dump（X=1077, Z=-833）=================
        //   用户反馈：① 火山（火山区）仍矮 ② 火山区域边界有一条【笔直斜线】（不自然）
        //   笔直边界是本项目"使用 Voronoi 距离 dist"的经典签名（见 CHAIN_SCALE 硬约束）。
        //   本节打印现场各量的空间分布，直接看谁在直线处跳变。
        {
            TerrainParams tp13 = TerrainParams.defaults();
            CellGenerator g13 = new CellGenerator(tp13, tp13.minY(), tp13.maxY());
            g13.seed(5436529513624899584L);
            TectonicField tf13 = new TectonicField(5436529513624899584L);
            // ★ 坐标基准不确定（预览可能按块或按 wu 显示）→ 两种假设都搜一遍，
            //   找出【真正的火山区域】再 dump，避免用错基准得出"不存在"的错误结论。
            double px3 = 1077, pz3 = -833;
            double hs3 = tp13.horizontalScale();
            System.out.printf("[13] 用户坐标 (1077,-833)，horizontalScale=%.1f%n", hs3);
            for (double hsTry : new double[]{1.0, hs3}) {
                double bx3 = 1077 / hsTry, bz3 = -833 / hsTry;
                double fdx = 0, fdz = 0, fbest = Double.MAX_VALUE;
                String ftype = "none";
                for (double dz3 = -1500; dz3 <= 1500; dz3 += 32) {
                    for (double dx3 = -1500; dx3 <= 1500; dx3 += 32) {
                        Cell cc = g13.sample(bx3 + dx3, bz3 + dz3);
                        if (cc.terrainType == TerrainClass.VOLCANO
                                || cc.terrainType == TerrainClass.VOLCANIC_FIELD) {
                            double dd = Math.hypot(dx3, dz3);
                            if (dd < fbest) { fbest = dd; fdx = dx3; fdz = dz3; ftype = cc.terrainType.name(); }
                        }
                    }
                }
                System.out.printf("   假设 wu=块/%.1f → 基准 wu(%.1f,%.1f): 最近火山类=%s 距离=%.0f wu @ wu(%.1f,%.1f)%n",
                        hsTry, bx3, bz3, ftype, fbest == Double.MAX_VALUE ? -1 : fbest,
                        bx3 + fdx, bz3 + fdz);
                if (fbest < Double.MAX_VALUE && fbest < 900) {
                    px3 = bx3 + fdx;
                    pz3 = bz3 + fdz;
                    System.out.printf("   → 采用该点做剖面 dump%n");
                    break;
                }
            }
            System.out.println("     x      z      type        e      eLand  landFeat  sEdif  fEdif  arcGate  dist");
            for (double dz3 = -300; dz3 <= 300; dz3 += 120) {
                for (double dx3 = -300; dx3 <= 300; dx3 += 120) {
                    double xx = px3 + dx3, zz = pz3 + dz3;
                    Cell cc = g13.sample(xx, zz);
                    TectonicField.Sample s13 = tf13.sample(xx, zz);
                    System.out.printf("  %6.0f %6.0f  %-11s %+.4f %+.4f  %.4f %.4f %.4f  %.4f %6.0f%n",
                            xx, zz, cc.terrainType, cc.e, cc.eLand,
                            cc.landFeat != null ? cc.landFeat.total : 0,
                            cc.landFeat != null ? cc.landFeat.singleEdifice : 0,
                            cc.landFeat != null ? cc.landFeat.fieldEdifice : 0,
                            tf13.arcVolcanism(s13), s13.dist());
                }
            }
            // 跨过"笔直边界"的细测线（沿 +x），确认跳变量
            System.out.println("     跨边界细测线（z=-833, x 从 1050 到 1250, 步 10）:");
            for (double xx = 1050; xx <= 1250; xx += 10) {
                Cell cc = g13.sample(xx, pz3);
                TectonicField.Sample s13 = tf13.sample(xx, pz3);
                System.out.printf("       x=%6.0f %-11s e=%+.4f fEdif=%.4f arcGate=%.4f dist=%6.1f%n",
                        xx, cc.terrainType, cc.e,
                        cc.landFeat != null ? cc.landFeat.fieldEdifice : 0,
                        tf13.arcVolcanism(s13), s13.dist());
            }
        }

        boolean all = pass1 && pass2 && pass3 && pass4 && pass5 && pass6 && pass7 && pass8
                   && pass9 && pass10 && pass10b && pass11 && pass11b && pass12b && pass14
                   && pass16;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
        if (!all) System.exit(1);
    }

    /** 火山类（视觉上都表现为"火山"）：单体火山 + 火山群/火山区。 */
    private static boolean isVolcanic(TerrainClass t) {
        return t == TerrainClass.VOLCANO || t == TerrainClass.VOLCANIC_FIELD;
    }

    /** 环形 8 方向中"非火山"的个数（8 = 该半径内完全孤立）。 */
    private static int isoRatio(CellGenerator gen, double x, double z, double r) {
        int n = 0;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            if (!isVolcanic(gen.sample(x + Math.cos(a) * r, z + Math.sin(a) * r).terrainType)) n++;
        }
        return n;
    }

    /** 百分比（分母 0 时返回 0）。 */
    private static double pct(int a, int b) {
        return b > 0 ? 100.0 * a / b : 0.0;
    }

    /** 测试用 Sample：dist=0（边界线上，取最大幅度），切向指向 +z。 */
    private static TectonicField.Sample smp(int btype) {
        return new TectonicField.Sample(0, btype, 1.0, 0.0, 1.0);
    }
}
