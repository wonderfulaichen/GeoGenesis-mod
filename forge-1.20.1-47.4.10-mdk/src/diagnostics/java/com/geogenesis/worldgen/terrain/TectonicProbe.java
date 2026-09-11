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
        double convMt = 0, intMt = 0;
        int nC = 0, nI = 0;
        for (double z = -12000; z <= 12000; z += 311) {
            for (double x = -12000; x <= 12000; x += 337) {
                TectonicField.Sample s = tf.sample(x, z);
                double[] w = gen.typeWeightsAt(x, z);
                double oceanW = w[TerrainClass.OCEAN.ordinal()] + w[TerrainClass.DEEP_OCEAN.ordinal()];
                boolean isLand = oceanW < 0.5;
                double mt = w[TerrainClass.MOUNTAINS.ordinal()];
                if (isLand && s.btype() == TectonicField.CONVERGENT && TectonicField.boundaryStrength(s) > 0.5) {
                    convMt += mt;
                    nC++;
                } else if (isLand && s.btype() == TectonicField.INTERIOR) {
                    intMt += mt;
                    nI++;
                }
            }
        }
        double mc = nC > 0 ? convMt / nC : 0.0;
        double mi = nI > 0 ? intMt / nI : 0.0;
        boolean pass5 = mc > mi * 1.5;
        System.out.printf("[5] 陆地 MOUNTAINS 权重: 汇聚边界=%.4f (n=%d) vs 板块内部=%.4f (n=%d) 比值=%.2f× %s%n",
            mc, nC, mi, nI, mi > 0 ? mc / mi : 0, pass5 ? "PASS" : "FAIL");

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
                if (s.btype() != TectonicField.CONVERGENT || TectonicField.boundaryStrength(s) <= 0.3) continue;
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

        boolean all = pass1 && pass2 && pass3 && pass4 && pass5 && pass6;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
        if (!all) System.exit(1);
    }

    /** 测试用 Sample：dist=0（边界线上，取最大幅度），切向指向 +z。 */
    private static TectonicField.Sample smp(int btype) {
        return new TectonicField.Sample(0, btype, 1.0, 0.0, 1.0);
    }
}
